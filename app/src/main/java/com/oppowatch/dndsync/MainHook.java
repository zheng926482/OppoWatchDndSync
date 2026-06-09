package com.oppowatch.dndsync;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private static final String PREFS_NAME = "oppo_watch_sync";

    private static Context systemContext;
    private static ContentResolver contentResolver;
    private static NotificationManager notificationManager;
    private static AudioManager audioManager;
    private static SharedPreferences prefs;
    
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
                    
                    // 创建共享存储用于跨进程通信
                    try {
                        prefs = systemContext.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE | Context.MODE_WORLD_WRITEABLE);
                    } catch (Throwable e) {
                        prefs = systemContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    }
                    
                    XposedBridge.log(TAG + " 获取系统服务成功");
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 获取系统服务失败: " + e.getMessage());
            }
            
            hookSystemFramework(lpparam);
        }
        
        // 在 OPPO 健康应用中监听回调
        if ("com.heytap.health".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + " ========== 扫描 com.heytap.health ==========");
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
                        return;
                    }
                    
                    int filter = (int) param.args[0];
                    boolean isDndOn = (filter == NotificationManager.INTERRUPTION_FILTER_NONE);
                    XposedBridge.log(TAG + " [手机] DND 变化: " + isDndOn);
                    
                    syncDndToWatch(isDndOn);
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
                        return;
                    }
                    
                    int mode = (int) param.args[0];
                    XposedBridge.log(TAG + " [手机] 铃声模式变化: " + mode);
                    
                    syncRingerToWatch(mode);
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
                        syncDndToWatch(isDndOn);
                    }
                }
            });
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 失败: " + e.getMessage());
        }
    }

    private void hookOppoHealthCallbacks(XC_LoadPackage.LoadPackageParam lpparam) {
        // 扫描所有可能的类
        String[] packages = {
            "com.heytap.health",
            "com.heytap.wearable",
            "com.heytap.health.sync",
            "com.heytap.health.device",
            "com.heytap.health.data"
        };

        String[] classNames = {
            "SyncManager",
            "DeviceManager",
            "DataManager",
            "WatchManager",
            "CallbackListener",
            "SyncListener",
            "DataSyncManager",
            "DeviceConnectManager"
        };

        for (String pkg : packages) {
            for (String cls : classNames) {
                String fullName = pkg + "." + cls;
                try {
                    Class<?> clazz = XposedHelpers.findClass(fullName, lpparam.classLoader);
                    if (clazz != null) {
                        XposedBridge.log(TAG + " [扫描] 找到类: " + fullName);
                        hookAllMethods(clazz, fullName);
                    }
                } catch (Throwable e) {
                    // 继续
                }
            }
        }
    }

    private void hookAllMethods(Class<?> clazz, String className) {
        try {
            java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
            XposedBridge.log(TAG + " [扫描] " + className + " 有 " + methods.length + " 个方法");
            
            int hooked = 0;
            for (java.lang.reflect.Method method : methods) {
                String methodName = method.getName();
                Class<?>[] params = method.getParameterTypes();

                // 只输出和 Hook 相对较短且可能相关的方法
                if (methodName.length() < 40 && 
                    (methodName.startsWith("on") || methodName.startsWith("set") || 
                     methodName.startsWith("handle") || methodName.contains("changed") ||
                     methodName.contains("Ringer") || methodName.contains("Mute") || 
                     methodName.contains("Silent") || methodName.contains("Dnd"))) {
                    
                    String paramStr = "";
                    for (Class<?> p : params) {
                        paramStr += p.getSimpleName() + ",";
                    }
                    if (paramStr.length() > 0) paramStr = paramStr.substring(0, paramStr.length() - 1);
                    
                    XposedBridge.log(TAG + "   方法: " + methodName + "(" + paramStr + ")");
                    
                    // 尝试 Hook
                    if (params.length <= 2) {
                        try {
                            if (params.length == 0) {
                                XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        XposedBridge.log(TAG + " [HOOK触发] " + param.method.getName() + "()");
                                    }
                                });
                                hooked++;
                            } else if (params.length == 1) {
                                XposedHelpers.findAndHookMethod(clazz, methodName, params[0], new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        XposedBridge.log(TAG + " [HOOK触发] " + param.method.getName() + "(" + param.args[0] + ")");
                                        
                                        // 处理 DND/铃声相关的回调
                                        String name = param.method.getName();
                                        if (name.contains("Silent") || name.contains("Dnd") || name.contains("Mute")) {
                                            boolean isDndOn = parseBoolean(param.args[0]);
                                            syncingFromWatch.set(true);
                                            try {
                                                setSystemDnd(isDndOn);
                                            } finally {
                                                syncingFromWatch.set(false);
                                            }
                                        } else if (name.contains("Ringer")) {
                                            int mode = parseInt(param.args[0]);
                                            syncingFromWatch.set(true);
                                            try {
                                                setSystemRinger(mode);
                                            } finally {
                                                syncingFromWatch.set(false);
                                            }
                                        }
                                    }
                                });
                                hooked++;
                            } else if (params.length == 2) {
                                XposedHelpers.findAndHookMethod(clazz, methodName, params[0], params[1], new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        XposedBridge.log(TAG + " [HOOK触发] " + param.method.getName() + "(" + param.args[0] + "," + param.args[1] + ")");
                                    }
                                });
                                hooked++;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            
            XposedBridge.log(TAG + " [扫描] " + className + " Hook 了 " + hooked + " 个方法");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " [扫描] 错误: " + e.getMessage());
        }
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (boolean) value;
        } else if (value instanceof Integer) {
            return ((int) value) != 0;
        }
        return false;
    }

    private int parseInt(Object value) {
        if (value instanceof Integer) {
            return (int) value;
        } else if (value instanceof Boolean) {
            return (boolean) value ? 1 : 0;
        }
        return 0;
    }

    private void syncDndToWatch(boolean isDndOn) {
        XposedBridge.log(TAG + " 同步DND到手表: " + isDndOn);
        
        // 方法1: 保存到共享存储
        if (prefs != null) {
            try {
                prefs.edit().putBoolean("dnd_on", isDndOn).putLong("dnd_time", System.currentTimeMillis()).commit();
                XposedBridge.log(TAG + " 已保存到 SharedPreferences");
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 保存到 SharedPreferences 失败: " + e.getMessage());
            }
        }
        
        // 方法2: 发送广播（尝试多个Action）
        if (systemContext != null) {
            String[] actions = {
                "com.oppo.watch.action.DND_CHANGED",
                "com.heytap.health.action.DND_CHANGED",
                "com.heytap.wearable.action.DND_CHANGED",
                "com.oppo.watch.DND_CHANGED"
            };
            
            for (String action : actions) {
                try {
                    Intent intent = new Intent(action);
                    intent.putExtra("dnd_on", isDndOn);
                    intent.putExtra("zen_mode", isDndOn ? 2 : 0);
                    systemContext.sendBroadcast(intent);
                    XposedBridge.log(TAG + " 广播已发送: " + action);
                } catch (Throwable e) {
                    // 继续尝试其他 action
                }
            }
        }
    }

    private void syncRingerToWatch(int mode) {
        XposedBridge.log(TAG + " 同步铃声模式到手表: " + mode);
        
        // 方法1: 保存到共享存储
        if (prefs != null) {
            try {
                prefs.edit().putInt("ringer_mode", mode).putLong("ringer_time", System.currentTimeMillis()).commit();
                XposedBridge.log(TAG + " 已保存到 SharedPreferences");
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 保存到 SharedPreferences 失败: " + e.getMessage());
            }
        }
        
        // 方法2: 发送广播
        if (systemContext != null) {
            String[] actions = {
                "com.oppo.watch.action.RINGER_MODE_CHANGED",
                "com.heytap.health.action.RINGER_MODE_CHANGED",
                "com.heytap.wearable.action.RINGER_MODE_CHANGED",
                "com.oppo.watch.RINGER_MODE_CHANGED"
            };
            
            for (String action : actions) {
                try {
                    Intent intent = new Intent(action);
                    intent.putExtra("ringer_mode", mode);
                    systemContext.sendBroadcast(intent);
                    XposedBridge.log(TAG + " 广播已发送: " + action);
                } catch (Throwable e) {
                    // 继续尝试其他 action
                }
            }
        }
    }

    private void setSystemDnd(boolean isDndOn) {
        if (notificationManager == null) return;
        
        try {
            int filter = isDndOn ? NotificationManager.INTERRUPTION_FILTER_NONE : 
                                   NotificationManager.INTERRUPTION_FILTER_ALL;
            if (Build.VERSION.SDK_INT >= 23) {
                notificationManager.setInterruptionFilter(filter);
                XposedBridge.log(TAG + " [手表同步] DND已设置: " + isDndOn);
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置DND失败: " + e.getMessage());
        }
    }

    private void setSystemRinger(int mode) {
        if (audioManager == null) return;
        
        try {
            audioManager.setRingerMode(mode);
            XposedBridge.log(TAG + " [手表同步] 铃声模式已设置: " + mode);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置铃声模式失败: " + e.getMessage());
        }
    }
}
