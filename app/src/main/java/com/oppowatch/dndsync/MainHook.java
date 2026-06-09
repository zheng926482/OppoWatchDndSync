package com.oppowatch.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
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
    private static final String[] TARGET_PACKAGES = {
            "com.heytap.health",
            "com.heytap.wearable.health",
            "com.oppo.watch",
            "com.oppo.health",
            "com.oplus.wearable.health"
    };

    private Context appContext;
    private NotificationManager notificationManager;
    private AudioManager audioManager;

    private AtomicBoolean syncingDndFromPhone = new AtomicBoolean(false);
    private AtomicBoolean syncingDndFromWatch = new AtomicBoolean(false);
    private AtomicBoolean syncingRingerFromPhone = new AtomicBoolean(false);
    private AtomicBoolean syncingRingerFromWatch = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean isTarget = false;
        for (String pkg : TARGET_PACKAGES) {
            if (lpparam.packageName.equals(pkg)) {
                isTarget = true;
                break;
            }
        }
        
        if (!isTarget) return;

        XposedBridge.log(TAG + " ========== 初始化: " + lpparam.packageName + " ==========");

        try {
            appContext = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentApplication"
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 获取Context失败");
        }

        if (appContext != null) {
            try {
                notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 获取服务失败");
            }
        }

        hookAllCallbacks(lpparam.classLoader);
        XposedBridge.log(TAG + " ========== 初始化完成 ==========");
    }

    private void hookAllCallbacks(ClassLoader cl) {
        // Hook DeviceProvider 类
        String[] deviceProviderClasses = {
            "com.oplus.wearable.provider.DeviceProvider",
            "com.heytap.wearable.provider.WatchProvider"
        };

        for (String className : deviceProviderClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, cl);
                hookProviderMethods(clazz);
                XposedBridge.log(TAG + " 已Hook类: " + className);
            } catch (Throwable e) {
                // 类不存在或其他错误，继续
            }
        }

        // Hook DeviceManager 类
        String[] deviceManagerClasses = {
            "com.oplus.wearable.device.DeviceManager",
            "com.heytap.wearable.device.DeviceManager"
        };

        for (String className : deviceManagerClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, cl);
                hookManagerMethods(clazz);
                XposedBridge.log(TAG + " 已Hook类: " + className);
            } catch (Throwable e) {
                // 类不存在或其他错误，继续
            }
        }
    }

    private void hookProviderMethods(Class<?> providerClass) {
        // Hook boolean 参数的方法 - DND 和铃声回调
        String[] booleanMethods = {
            "onSilentModeChanged",
            "onDndChanged", 
            "onMuteModeChanged",
            "onRingerModeChanged"
        };

        for (String methodName : booleanMethods) {
            try {
                XposedHelpers.findAndHookMethod(providerClass, methodName, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        boolean value = (boolean) param.args[0];
                        XposedBridge.log(TAG + " [Hook] " + param.method.getName() + "(" + value + ")");
                        
                        // 根据方法名做相应处理
                        String method = param.method.getName();
                        if (method.contains("Silent") || method.contains("Dnd") || method.contains("Mute")) {
                            handleDndChange(value);
                        } else if (method.contains("Ringer")) {
                            handleRingerChange(value);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hook成功: " + methodName);
            } catch (Throwable e) {
                // 方法不存在，继续尝试其他方法
            }
        }

        // Hook int 参数的方法
        String[] intMethods = {
            "onRingerModeChanged",
            "onAudioModeChanged"
        };

        for (String methodName : intMethods) {
            try {
                XposedHelpers.findAndHookMethod(providerClass, methodName, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int value = (int) param.args[0];
                        XposedBridge.log(TAG + " [Hook] " + param.method.getName() + "(" + value + ")");
                        
                        if (param.method.getName().contains("Ringer")) {
                            handleRingerChangeInt(value);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hook成功: " + methodName);
            } catch (Throwable e) {
                // 方法不存在，继续尝试其他方法
            }
        }
    }

    private void hookManagerMethods(Class<?> managerClass) {
        // Hook set 类方法
        String[] setMethods = {
            "setPhoneSilentMode",
            "setPhoneDoNotDisturb",
            "setPhoneMuteMode",
            "setPhoneVibrateMode",
            "setMute",
            "setVibrate",
            "setSilentMode",
            "setVibrateMode"
        };

        for (String methodName : setMethods) {
            try {
                XposedHelpers.findAndHookMethod(managerClass, methodName, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        boolean value = (boolean) param.args[0];
                        XposedBridge.log(TAG + " [Hook-Set] " + param.method.getName() + "(" + value + ")");
                    }
                });
                XposedBridge.log(TAG + " Hook成功: " + methodName);
            } catch (Throwable e) {
                // 方法不存在
            }
        }
    }

    private void handleDndChange(boolean dndOn) {
        if (syncingDndFromPhone.get()) return;
        
        syncingDndFromWatch.set(true);
        try {
            XposedBridge.log(TAG + " 处理DND变化: " + dndOn);
            
            if (notificationManager != null && audioManager != null) {
                int filter = dndOn ? NotificationManager.INTERRUPTION_FILTER_NONE : 
                            NotificationManager.INTERRUPTION_FILTER_ALL;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    notificationManager.setInterruptionFilter(filter);
                    XposedBridge.log(TAG + " 已设置中断过滤: " + filter);
                }
            }
        } finally {
            syncingDndFromWatch.set(false);
        }
    }

    private void handleRingerChange(boolean silent) {
        if (syncingRingerFromPhone.get()) return;
        
        syncingRingerFromWatch.set(true);
        try {
            XposedBridge.log(TAG + " 处理铃声变化: " + silent);
            
            if (audioManager != null) {
                int mode = silent ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                audioManager.setRingerMode(mode);
                XposedBridge.log(TAG + " 已设置铃声模式: " + mode);
            }
        } finally {
            syncingRingerFromWatch.set(false);
        }
    }

    private void handleRingerChangeInt(int ringerMode) {
        if (syncingRingerFromPhone.get()) return;
        
        syncingRingerFromWatch.set(true);
        try {
            XposedBridge.log(TAG + " 处理铃声变化(int): " + ringerMode);
            
            if (audioManager != null) {
                audioManager.setRingerMode(ringerMode);
                XposedBridge.log(TAG + " 已设置铃声模式: " + ringerMode);
            }
        } finally {
            syncingRingerFromWatch.set(false);
        }
    }
}
