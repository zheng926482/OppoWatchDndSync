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
    private static final String[] TARGET_PACKAGES = {
            "android",
            "com.heytap.health",
            "com.heytap.wearable.health",
            "com.oppo.watch",
            "com.oppo.health",
            "com.oplus.wearable.health"
    };
    private static final String ZEN_MODE = "zen_mode";
    private static final int ZEN_MODE_OFF = 0;

    private Context appContext;
    private NotificationManager notificationManager;
    private AudioManager audioManager;

    private AtomicBoolean syncingDndFromPhone = new AtomicBoolean(false);
    private AtomicBoolean syncingDndFromWatch = new AtomicBoolean(false);
    private AtomicBoolean syncingRingerFromPhone = new AtomicBoolean(false);
    private AtomicBoolean syncingRingerFromWatch = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " 检测到包: " + lpparam.packageName);
        
        boolean isTarget = false;
        for (String pkg : TARGET_PACKAGES) {
            if (lpparam.packageName.equals(pkg)) {
                isTarget = true;
                break;
            }
        }
        
        if (!isTarget) {
            XposedBridge.log(TAG + " 跳过非目标包: " + lpparam.packageName);
            return;
        }

        XposedBridge.log(TAG + " ========== 初始化，包名: " + lpparam.packageName + " ==========");

        try {
            appContext = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentApplication"
            );
            if (appContext == null) {
                XposedBridge.log(TAG + " ActivityThread 获取失败，尝试备用方法");
                appContext = (Context) XposedHelpers.getObjectField(lpparam, "application");
            }
            XposedBridge.log(TAG + " Context 获取成功: " + (appContext != null));
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 获取Context失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (appContext == null) {
            XposedBridge.log(TAG + " Context为空，无法继续");
            return;
        }

        notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        XposedBridge.log(TAG + " NotificationManager: " + (notificationManager != null));
        XposedBridge.log(TAG + " AudioManager: " + (audioManager != null));

        registerPhoneDndObserver();
        XposedBridge.log(TAG + " DND 观察器已注册");
        
        hookWatchDndCallback(lpparam.classLoader);
        XposedBridge.log(TAG + " 手表 DND 回调已 Hook");
        
        registerRingerModeObserver();
        XposedBridge.log(TAG + " 铃声观察器已注册");
        
        hookWatchRingerCallback(lpparam.classLoader);
        XposedBridge.log(TAG + " 手表铃声回调已 Hook");
        
        XposedBridge.log(TAG + " ========== 初始化完成 ==========");
    }

    // 勿扰监听
    private void registerPhoneDndObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        Uri zenModeUri = Settings.Global.getUriFor(ZEN_MODE);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingDndFromWatch.get()) return;
                int zenMode = Settings.Global.getInt(resolver, ZEN_MODE, ZEN_MODE_OFF);
                boolean isDndOn = (zenMode != ZEN_MODE_OFF);
                XposedBridge.log(TAG + " 手机勿扰变化: " + isDndOn);
                syncDndToWatch(isDndOn);
            }
        };
        resolver.registerContentObserver(zenModeUri, false, observer);
    }

    private void syncDndToWatch(boolean enable) {
        if (syncingDndFromWatch.get()) return;
        syncingDndFromPhone.set(true);
        try {
            boolean success = callOppoDndMethod(enable);
            if (!success) callOppoDndBroadcast(enable);
        } finally {
            syncingDndFromPhone.set(false);
        }
    }

    private boolean callOppoDndMethod(boolean enable) {
        try {
            Class<?> clazz = findDeviceManagerClass();
            if (clazz == null) return false;
            Object instance = XposedHelpers.callStaticMethod(clazz, "getInstance");
            if (instance == null) return false;
            String[] methods = {"setPhoneSilentMode", "setPhoneDoNotDisturb", "syncDndMode"};
            for (String m : methods) {
                try {
                    XposedHelpers.callMethod(instance, m, enable);
                    XposedBridge.log(TAG + " 勿扰同步成功: " + m);
                    return true;
                } catch (Throwable ignored) {}
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void callOppoDndBroadcast(boolean enable) {
        try {
            Intent intent = new Intent("com.oppo.watch.action.SILENT_MODE");
            intent.putExtra("silent_mode", enable);
            appContext.sendBroadcast(intent);
        } catch (Throwable e) {}
    }

    private void hookWatchDndCallback(ClassLoader cl) {
        String[] classes = {"com.oplus.wearable.provider.DeviceProvider", "com.heytap.wearable.provider.WatchProvider"};
        for (String cls : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(cls, cl);
            if (clazz != null) {
                String[] methods = {"onSilentModeChanged", "onDndChanged"};
                for (String m : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, m, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (syncingDndFromPhone.get()) return;
                                boolean watchDnd = (boolean) param.args[0];
                                syncingDndFromWatch.set(true);
                                setSystemDndMode(watchDnd);
                                syncingDndFromWatch.set(false);
                            }
                        });
                        XposedBridge.log(TAG + " Hook手表勿扰成功: " + cls + "#" + m);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void setSystemDndMode(boolean enable) {
        if (notificationManager == null || syncingDndFromPhone.get()) return;
        try {
            int filter = enable ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL;
            if (Build.VERSION.SDK_INT >= 23) notificationManager.setInterruptionFilter(filter);
            else Settings.Global.putInt(appContext.getContentResolver(), ZEN_MODE, enable ? 2 : 0);
        } catch (Throwable e) {}
    }

    // 静音监听
    private void registerRingerModeObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        Uri uri = Settings.System.getUriFor(Settings.System.MODE_RINGER);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingRingerFromWatch.get()) return;
                int mode = audioManager.getRingerMode();
                XposedBridge.log(TAG + " 手机铃声变化: " + mode);
                syncRingerToWatch(mode);
            }
        };
        resolver.registerContentObserver(uri, false, observer);
    }

    private void syncRingerToWatch(int ringerMode) {
        if (syncingRingerFromWatch.get()) return;
        syncingRingerFromPhone.set(true);
        try {
            boolean silent = (ringerMode == AudioManager.RINGER_MODE_SILENT);
            boolean vibrate = (ringerMode == AudioManager.RINGER_MODE_VIBRATE);
            boolean ok = callOppoRingerMethod(silent, vibrate);
            if (!ok) callOppoRingerBroadcast(silent, vibrate);
        } finally {
            syncingRingerFromPhone.set(false);
        }
    }

    private boolean callOppoRingerMethod(boolean silent, boolean vibrate) {
        try {
            Class<?> clazz = findDeviceManagerClass();
            if (clazz == null) return false;
            Object instance = XposedHelpers.callStaticMethod(clazz, "getInstance");
            if (instance == null) return false;
            String[][] pairs = {
                {"setPhoneMuteMode", "setPhoneVibrateMode"},
                {"setMute", "setVibrate"},
                {"setSilentMode", "setVibrateMode"}
            };
            for (String[] p : pairs) {
                try {
                    XposedHelpers.callMethod(instance, p[0], silent);
                    XposedHelpers.callMethod(instance, p[1], vibrate);
                    XposedBridge.log(TAG + " 静音同步成功: silent=" + silent + ", vibrate=" + vibrate);
                    return true;
                } catch (Throwable ignored) {}
            }
            try {
                XposedHelpers.callMethod(instance, "setPhoneSilentMode", silent || vibrate);
                return true;
            } catch (Throwable ignored) {}
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void callOppoRingerBroadcast(boolean silent, boolean vibrate) {
        try {
            Intent intent = new Intent("com.oppo.watch.action.RINGER_MODE");
            intent.putExtra("silent", silent);
            intent.putExtra("vibrate", vibrate);
            appContext.sendBroadcast(intent);
        } catch (Throwable e) {}
    }

    private void hookWatchRingerCallback(ClassLoader cl) {
        String[] classes = {"com.oplus.wearable.provider.DeviceProvider", "com.heytap.wearable.provider.WatchProvider"};
        for (String cls : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(cls, cl);
            if (clazz != null) {
                String[] methods = {"onMuteModeChanged", "onSilentModeChanged", "onRingerModeChanged"};
                for (String m : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, m, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (syncingRingerFromPhone.get()) return;
                                boolean silent = (boolean) param.args[0];
                                syncingRingerFromWatch.set(true);
                                int target = silent ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                                setSystemRingerMode(target);
                                syncingRingerFromWatch.set(false);
                            }
                        });
                        XposedBridge.log(TAG + " Hook手表静音成功: " + cls + "#" + m);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void setSystemRingerMode(int mode) {
        if (audioManager == null || syncingRingerFromPhone.get()) return;
        try {
            audioManager.setRingerMode(mode);
        } catch (Throwable e) {}
    }

    private Class<?> findDeviceManagerClass() {
        String[] names = {"com.oplus.wearable.device.DeviceManager", "com.heytap.wearable.device.DeviceManager"};
        for (String n : names) {
            Class<?> c = XposedHelpers.findClassIfExists(n, null);
            if (c != null) return c;
        }
        return null;
    }
}
