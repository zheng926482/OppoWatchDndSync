package com.oppowatch.dndsync;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只在系统进程中 Hook 一次
        if ("android".equals(lpparam.packageName)) {
            if (hookInstalled.getAndSet(true)) {
                return; // 已经 Hook 过了
            }
            
            XposedBridge.log(TAG + " 在系统进程中安装 Hook");
            hookSystemFramework(lpparam);
        }
    }

    private void hookSystemFramework(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook NotificationManager.setInterruptionFilter - 监听 DND 变化
            Class<?> nmClass = XposedHelpers.findClass("android.app.NotificationManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(nmClass, "setInterruptionFilter", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int filter = (int) param.args[0];
                    boolean isDndOn = (filter == NotificationManager.INTERRUPTION_FILTER_NONE);
                    XposedBridge.log(TAG + " [系统] DND 变化: " + isDndOn + " (filter=" + filter + ")");
                    syncDndToWatch(isDndOn);
                }
            });
            XposedBridge.log(TAG + " Hook NotificationManager.setInterruptionFilter 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook setInterruptionFilter 失败: " + e.getMessage());
        }

        try {
            // Hook AudioManager.setRingerMode - 监听铃声模式变化
            Class<?> amClass = XposedHelpers.findClass("android.media.AudioManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(amClass, "setRingerMode", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int mode = (int) param.args[0];
                    XposedBridge.log(TAG + " [系统] 铃声模式变化: " + mode);
                    syncRingerToWatch(mode);
                }
            });
            XposedBridge.log(TAG + " Hook AudioManager.setRingerMode 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook setRingerMode 失败: " + e.getMessage());
        }

        try {
            // Hook Settings.Global.putInt - 监听系统设置变化
            Class<?> settingsGlobalClass = XposedHelpers.findClass("android.provider.Settings$Global", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(settingsGlobalClass, "putInt", ContentResolver.class, String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[1];
                    int value = (int) param.args[2];
                    
                    if (ZEN_MODE.equals(key)) {
                        boolean isDndOn = (value != ZEN_MODE_OFF);
                        XposedBridge.log(TAG + " [系统] zen_mode 变化: " + isDndOn + " (value=" + value + ")");
                        syncDndToWatch(isDndOn);
                    }
                }
            });
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook Settings.Global.putInt 失败: " + e.getMessage());
        }

        // Hook OPPO 健康应用中与同步相关的方法
        hookOppoHealth(lpparam);
    }

    private void hookOppoHealth(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " 扫描 OPPO 健康应用中的同步方法...");
        
        // 尝试 Hook 一些通用的蓝牙/同步相关方法
        String[] potentialClasses = {
            "com.heytap.health.sync.SyncManager",
            "com.heytap.health.device.DeviceManager",
            "com.heytap.health.connect.ConnectManager",
            "com.heytap.health.data.DataSyncManager",
            "com.heytap.health.sync.CallbackListener",
            "com.heytap.health.biz.DeviceConnectManager"
        };

        for (String className : potentialClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                if (clazz != null) {
                    hookClassMethods(clazz);
                    XposedBridge.log(TAG + " 已Hook: " + className);
                }
            } catch (Throwable e) {
                // 类不存在，继续
            }
        }
    }

    private void hookClassMethods(Class<?> clazz) {
        try {
            // 尝试 Hook 静态的 getInstance 方法
            try {
                XposedHelpers.findAndHookMethod(clazz, "getInstance", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + " getInstance 被调用");
                    }
                });
            } catch (Throwable ignored) {
            }

            // 尝试 Hook 所有包含 "on" 或 "set" 的方法
            java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
            for (java.lang.reflect.Method method : methods) {
                String methodName = method.getName();
                Class<?>[] params = method.getParameterTypes();

                if ((methodName.startsWith("on") || methodName.contains("changed") || 
                     methodName.startsWith("set")) && params.length <= 2) {
                    
                    try {
                        if (params.length == 0) {
                            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    XposedBridge.log(TAG + " [应用] " + param.method.getName() + "()");
                                }
                            });
                        } else if (params.length == 1) {
                            XposedHelpers.findAndHookMethod(clazz, methodName, params[0], new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    XposedBridge.log(TAG + " [应用] " + param.method.getName() + "(" + param.args[0] + ")");
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hookClassMethods 失败: " + e.getMessage());
        }
    }

    private void syncDndToWatch(boolean dndOn) {
        XposedBridge.log(TAG + " 同步DND到手表: " + dndOn);
        // 发送广播或通过其他方式通知手表
        try {
            // 这里可以添加实际的同步逻辑
            // 例如：保存到 SharedPreferences、发送 Intent 等
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 同步DND失败: " + e.getMessage());
        }
    }

    private void syncRingerToWatch(int ringerMode) {
        XposedBridge.log(TAG + " 同步铃声模式到手表: " + ringerMode);
        // 发送广播或通过其他方式通知手表
        try {
            // 这里可以添加实际的同步逻辑
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 同步铃声失败: " + e.getMessage());
        }
    }
}
