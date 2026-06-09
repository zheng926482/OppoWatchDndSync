package com.oppowatch.dndsync;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OppoWatchDndSync";
    private static final String ZEN_MODE = "zen_mode";
    private static final int ZEN_MODE_OFF = 0;

    private static Context systemContext;
    private static ContentResolver contentResolver;
    private static NotificationManager notificationManager;
    private static AudioManager audioManager;
    
    private static AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private static AtomicBoolean syncingFromWatch = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 在系统进程中安装 Hook
        if ("android".equals(lpparam.packageName)) {
            if (hookInstalled.getAndSet(true)) {
                return;
            }
            
            XposedBridge.log(TAG + " 在系统进程中安装 Hook");
            
            // 获取系统上下文
            try {
                systemContext = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                        "currentApplication"
                );
                if (systemContext != null) {
                    contentResolver = systemContext.getContentResolver();
                    notificationManager = (NotificationManager) systemContext.getSystemService(Context.NOTIFICATION_SERVICE);
                    audioManager = (AudioManager) systemContext.getSystemService(Context.AUDIO_SERVICE);
                    XposedBridge.log(TAG + " 获取系统服务成功");
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 获取系统服务失败: " + e.getMessage());
            }
            
            hookSystemFramework(lpparam);
        }
        
        // 在 OPPO 健康应用中监听回调
        if ("com.heytap.health".equals(lpparam.packageName) || 
            "com.heytap.wearable.health".equals(lpparam.packageName)) {
            hookOppoHealthCallbacks(lpparam);
        }
    }

    private void hookSystemFramework(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook NotificationManager.setInterruptionFilter
            Class<?> nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(nmClass, "setInterruptionFilter", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (syncingFromWatch.get()) {
                        XposedBridge.log(TAG + " 忽略来自手表的DND同步");
                        return;
                    }
                    
                    int filter = (int) param.args[0];
                    boolean isDndOn = (filter == NotificationManager.INTERRUPTION_FILTER_NONE);
                    XposedBridge.log(TAG + " [手机] DND 变化: " + isDndOn);
                    
                    // 同步到手表
                    sendDndToWatch(isDndOn);
                }
            });
            XposedBridge.log(TAG + " Hook NotificationManager.setInterruptionFilter 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook setInterruptionFilter 失败: " + e.getMessage());
        }

        try {
            // Hook AudioManager.setRingerMode
            Class<?> amClass = XposedHelpers.findClass("android.media.AudioManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(amClass, "setRingerMode", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (syncingFromWatch.get()) {
                        XposedBridge.log(TAG + " 忽略来自手表的铃声同步");
                        return;
                    }
                    
                    int mode = (int) param.args[0];
                    XposedBridge.log(TAG + " [手机] 铃声模式变化: " + mode);
                    
                    // 同步到手表
                    sendRingerToWatch(mode);
                }
            });
            XposedBridge.log(TAG + " Hook AudioManager.setRingerMode 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook setRingerMode 失败: " + e.getMessage());
        }

        try {
            // Hook Settings.Global.putInt
            Class<?> settingsGlobalClass = XposedHelpers.findClass("android.provider.Settings$Global", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(settingsGlobalClass, "putInt", ContentResolver.class, String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (syncingFromWatch.get()) {
                        return;
                    }
                    
                    String key = (String) param.args[1];
                    int value = (int) param.args[2];
                    
                    if (ZEN_MODE.equals(key)) {
                        boolean isDndOn = (value != ZEN_MODE_OFF);
                        XposedBridge.log(TAG + " [手机] zen_mode 变化: " + isDndOn);
                        sendDndToWatch(isDndOn);
                    }
                }
            });
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 失败: " + e.getMessage());
        }
    }

    private void hookOppoHealthCallbacks(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " 在 OPPO 健康应用中寻找同步回调...");
        
        // 扫描已知的类
        String[] classes = {
            "com.heytap.health.sync.SyncManager",
            "com.heytap.health.device.DeviceManager",
            "com.heytap.health.data.DataSyncManager"
        };

        for (String className : classes) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                if (clazz != null) {
                    hookOppoMethods(clazz);
                }
            } catch (Throwable e) {
                // 继续
            }
        }
    }

    private void hookOppoMethods(Class<?> clazz) {
        try {
            java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
            for (java.lang.reflect.Method method : methods) {
                String methodName = method.getName();
                Class<?>[] params = method.getParameterTypes();

                // 查找 on* 或 set* 方法
                if ((methodName.startsWith("on") || methodName.startsWith("set")) && params.length == 1) {
                    try {
                        Class<?> paramType = params[0];
                        XposedHelpers.findAndHookMethod(clazz, methodName, paramType, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String name = param.method.getName();
                                Object value = param.args[0];
                                
                                // 检查是否与 DND/铃声相关
                                if (name.contains("Silent") || name.contains("Dnd") || name.contains("Mute")) {
                                    boolean isDndOn = false;
                                    if (value instanceof Boolean) {
                                        isDndOn = (boolean) value;
                                    }
                                    
                                    XposedBridge.log(TAG + " [手表] " + name + "(" + isDndOn + ")");
                                    
                                    syncingFromWatch.set(true);
                                    try {
                                        setSystemDnd(isDndOn);
                                    } finally {
                                        syncingFromWatch.set(false);
                                    }
                                } else if (name.contains("Ringer")) {
                                    int mode = 0;
                                    if (value instanceof Integer) {
                                        mode = (int) value;
                                    } else if (value instanceof Boolean) {
                                        mode = (boolean) value ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                                    }
                                    
                                    XposedBridge.log(TAG + " [手表] " + name + "(" + mode + ")");
                                    
                                    syncingFromWatch.set(true);
                                    try {
                                        setSystemRinger(mode);
                                    } finally {
                                        syncingFromWatch.set(false);
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hookOppoMethods 失败: " + e.getMessage());
        }
    }

    private void sendDndToWatch(boolean isDndOn) {
        XposedBridge.log(TAG + " 发送DND到手表: " + isDndOn);
        
        if (systemContext == null) return;
        
        try {
            // 发送广播
            Intent intent = new Intent("com.oppo.watch.action.DND_CHANGED");
            intent.putExtra("dnd_on", isDndOn);
            systemContext.sendBroadcast(intent);
            XposedBridge.log(TAG + " 广播已发送");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 发送广播失败: " + e.getMessage());
        }
    }

    private void sendRingerToWatch(int mode) {
        XposedBridge.log(TAG + " 发送铃声模式到手表: " + mode);
        
        if (systemContext == null) return;
        
        try {
            // 发送广播
            Intent intent = new Intent("com.oppo.watch.action.RINGER_MODE_CHANGED");
            intent.putExtra("ringer_mode", mode);
            systemContext.sendBroadcast(intent);
            XposedBridge.log(TAG + " 广播已发送");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 发送广播失败: " + e.getMessage());
        }
    }

    private void setSystemDnd(boolean isDndOn) {
        if (notificationManager == null) return;
        
        try {
            int filter = isDndOn ? NotificationManager.INTERRUPTION_FILTER_NONE : 
                                   NotificationManager.INTERRUPTION_FILTER_ALL;
            if (Build.VERSION.SDK_INT >= 23) {
                notificationManager.setInterruptionFilter(filter);
                XposedBridge.log(TAG + " DND已设置: " + isDndOn);
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置DND失败: " + e.getMessage());
        }
    }

    private void setSystemRinger(int mode) {
        if (audioManager == null) return;
        
        try {
            audioManager.setRingerMode(mode);
            XposedBridge.log(TAG + " 铃声模式已设置: " + mode);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置铃声模式失败: " + e.getMessage());
        }
    }
}
