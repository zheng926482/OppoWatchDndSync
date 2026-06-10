package com.oppowatch.dndsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "OppoWatchDndSync";

    // 目标包名
    private static final String[] TARGET_PACKAGES = {
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

    // 通知渠道ID和通知ID（避免冲突）
    private static final String DND_NOTIF_CHANNEL = "dnd_sync_channel";
    private static final int DND_NOTIF_ID = 0x7D0; // 2000
    private static final int RINGER_NOTIF_ID = 0x7D1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 先处理目标APP（用于Hook手表回调）
        boolean isTarget = false;
        for (String pkg : TARGET_PACKAGES) {
            if (lpparam.packageName.equals(pkg)) {
                isTarget = true;
                break;
            }
        }
        if (isTarget) {
            XposedBridge.log(TAG + " 进入目标APP: " + lpparam.packageName);
            // 尝试初始化Context（可能在APP进程内）
            initContext(lpparam);
            // Hook手表回调
            hookWatchDndCallback(lpparam.classLoader);
            hookWatchRingerCallback(lpparam.classLoader);
            return;
        }

        // 在系统进程或任何进程初始化通用的监听（因为需要监听系统设置变化）
        // 但为了避免重复初始化，只初始化一次
        if (appContext == null) {
            try {
                appContext = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 获取Context失败: " + e);
                return;
            }
            if (appContext != null) {
                notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                // 创建通知渠道（Android 8+）
                createNotificationChannel();
                // 监听手机勿扰模式变化
                registerPhoneDndObserver();
                // 监听手机铃声模式变化
                registerRingerModeObserver();
                XposedBridge.log(TAG + " 已注册手机状态监听");
            }
        }
    }

    private void initContext(XC_LoadPackage.LoadPackageParam lpparam) {
        if (appContext == null) {
            try {
                appContext = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                        "currentApplication");
                if (appContext == null && lpparam.appInfo != null) {
                    appContext = (Context) XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader),
                            lpparam.appInfo);
                }
            } catch (Throwable ignored) {}
            if (appContext != null) {
                notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                createNotificationChannel();
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(DND_NOTIF_CHANNEL,
                    "勿扰同步", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("用于同步勿扰状态到OPPO手表");
            channel.setSound(null, null);
            channel.setVibrationPattern(null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    // 监听手机勿扰模式变化（ZEN_MODE）
    private void registerPhoneDndObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        Uri zenModeUri = Settings.Global.getUriFor(Settings.Global.ZEN_MODE);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingDndFromWatch.get()) return;
                int zenMode = Settings.Global.getInt(resolver, Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
                boolean isDndOn = (zenMode != Settings.Global.ZEN_MODE_OFF);
                XposedBridge.log(TAG + " [手机] 勿扰变化: " + isDndOn);
                // 通过通知欺骗同步到手表
                syncDndToWatchByNotification(isDndOn);
            }
        };
        resolver.registerContentObserver(zenModeUri, false, observer);
    }

    // 监听手机铃声模式变化（静音/振动）
    private void registerRingerModeObserver() {
        if (audioManager == null) return;
        // 由于没有直接的内容观察者，我们通过轮询或监听音频变化? 这里简单监听RINGER_MODE的Settings变化
        ContentResolver resolver = appContext.getContentResolver();
        Uri ringerUri = Settings.System.getUriFor(Settings.System.MODE_RINGER);
        if (ringerUri == null) {
            // 旧版本可能没有，改用轮询? 简单处理：不监听静音了，或者用AudioManager的监听? AudioManager没有直接ContentObserver
            // 改为每2秒轮询? 不推荐。改为Hook AudioManager的setRingerMode? 太复杂。
            // 简单起见，只监听勿扰，静音暂不同步，用户可自行开启手表勿扰。
            XposedBridge.log(TAG + " 无法监听铃声模式，静音同步将禁用");
            return;
        }
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingRingerFromWatch.get()) return;
                int mode = audioManager.getRingerMode();
                XposedBridge.log(TAG + " [手机] 铃声模式变化: " + mode);
                syncRingerToWatchByNotification(mode);
            }
        };
        resolver.registerContentObserver(ringerUri, false, observer);
    }

    // 通过发送通知欺骗方式同步勿扰状态到手表
    private void syncDndToWatchByNotification(boolean enable) {
        if (syncingDndFromWatch.get()) return;
        syncingDndFromPhone.set(true);
        try {
            sendVirtualNotification("勿扰模式", enable ? "已开启" : "已关闭", DND_NOTIF_ID);
            XposedBridge.log(TAG + " 已发送勿扰状态通知: " + enable);
        } finally {
            syncingDndFromPhone.set(false);
        }
    }

    private void syncRingerToWatchByNotification(int ringerMode) {
        if (syncingRingerFromWatch.get()) return;
        syncingRingerFromPhone.set(true);
        try {
            String text;
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) text = "静音模式";
            else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) text = "振动模式";
            else text = "响铃模式";
            sendVirtualNotification("铃声模式", text, RINGER_NOTIF_ID);
            XposedBridge.log(TAG + " 已发送铃声状态通知: " + text);
        } finally {
            syncingRingerFromPhone.set(false);
        }
    }

    private void sendVirtualNotification(String title, String content, int id) {
        if (notificationManager == null) return;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(appContext, DND_NOTIF_CHANNEL);
        } else {
            builder = new Notification.Builder(appContext);
        }
        builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_MAX);
        // 添加一个空的Intent，避免点击打开应用
        Intent emptyIntent = new Intent();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext, 0, emptyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);
        notificationManager.notify(id, builder.build());
    }

    // 以下为Hook手表回调部分（从手表同步到手机）
    private void hookWatchDndCallback(ClassLoader classLoader) {
        String[] classes = {
                "com.oplus.wearable.provider.DeviceProvider",
                "com.heytap.wearable.provider.WatchProvider",
                "com.heytap.health.device.DeviceManager",
                "com.oplus.health.device.DeviceManager"
        };
        for (String clsName : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(clsName, classLoader);
            if (clazz != null) {
                String[] methods = {"onSilentModeChanged", "onDndChanged", "onDoNotDisturbChanged"};
                for (String method : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (syncingDndFromPhone.get()) return;
                                boolean watchDnd = (boolean) param.args[0];
                                XposedBridge.log(TAG + " [手表] 勿扰回调: " + watchDnd);
                                syncingDndFromWatch.set(true);
                                setSystemDndMode(watchDnd);
                                syncingDndFromWatch.set(false);
                            }
                        });
                        XposedBridge.log(TAG + " Hook成功: " + clsName + "#" + method);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
        // 如果上述没有，尝试Hook NotificationListenerService的onInterruptionFilterChanged? 需要权限，备用
        tryHookNotificationListener(classLoader);
    }

    private void hookWatchRingerCallback(ClassLoader classLoader) {
        String[] classes = {
                "com.oplus.wearable.provider.DeviceProvider",
                "com.heytap.wearable.provider.WatchProvider"
        };
        for (String clsName : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(clsName, classLoader);
            if (clazz != null) {
                String[] methods = {"onMuteModeChanged", "onSilentModeChanged", "onRingerModeChanged"};
                for (String method : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (syncingRingerFromPhone.get()) return;
                                boolean isSilent = (boolean) param.args[0];
                                XposedBridge.log(TAG + " [手表] 静音回调: " + isSilent);
                                syncingRingerFromWatch.set(true);
                                int targetMode = isSilent ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                                setSystemRingerMode(targetMode);
                                syncingRingerFromWatch.set(false);
                            }
                        });
                        XposedBridge.log(TAG + " Hook静音成功: " + clsName + "#" + method);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void tryHookNotificationListener(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.service.notification.NotificationListenerService", classLoader,
                    "onInterruptionFilterChanged", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (syncingDndFromPhone.get()) return;
                            int filter = (int) param.args[0];
                            boolean isDnd = (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                    filter == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                            XposedBridge.log(TAG + " [监听] NotificationListenerService filter: " + filter + " isDnd=" + isDnd);
                            // 注意：这里可能是手机其他应用导致，需要判断来源，简单起见如果非来自手表同步，就不做反向
                            // 但我们已经有手表回调Hook，所以这里作为备选
                        }
                    });
            XposedBridge.log(TAG + " Hook NotificationListenerService 成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook NotificationListenerService 失败: " + e);
        }
    }

    private void setSystemDndMode(boolean enable) {
        if (notificationManager == null) return;
        try {
            int filter = enable ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL;
            if (Build.VERSION.SDK_INT >= 23) {
                notificationManager.setInterruptionFilter(filter);
            } else {
                Settings.Global.putInt(appContext.getContentResolver(), Settings.Global.ZEN_MODE,
                        enable ? Settings.Global.ZEN_MODE_ALARMS : Settings.Global.ZEN_MODE_OFF);
            }
            XposedBridge.log(TAG + " 设置手机勿扰: " + enable);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置手机勿扰失败: " + e);
        }
    }

    private void setSystemRingerMode(int mode) {
        if (audioManager == null) return;
        try {
            audioManager.setRingerMode(mode);
            XposedBridge.log(TAG + " 设置手机铃声模式: " + mode);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置铃声模式失败: " + e);
        }
    }
}
