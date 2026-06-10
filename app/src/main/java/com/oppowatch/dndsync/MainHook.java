package com.oppowatch.dndsync;

import android.app.Notification;
import android.app.NotificationChannel;
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
            "com.heytap.wearable.health",
            "com.oppo.watch",
            "com.oppo.health",
            "com.oplus.wearable.health"
    };

    private Context appContext;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private AtomicBoolean syncingFromWatch = new AtomicBoolean(false);
    private AtomicBoolean syncingFromPhone = new AtomicBoolean(false);

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

        XposedBridge.log(TAG + " 初始化，包名: " + lpparam.packageName);

        try {
            appContext = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentApplication"
            );
            if (appContext == null) {
                appContext = (Context) XposedHelpers.getObjectField(lpparam, "application");
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 获取Context失败: " + e.getMessage());
            return;
        }

        if (appContext == null) {
            XposedBridge.log(TAG + " Context为空");
            return;
        }

        notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        // 监听手机勿扰模式变化 → 发送虚拟通知
        registerPhoneDndObserver();
        // 监听手机铃声模式变化 → 发送虚拟通知
        registerRingerModeObserver();
        // 尝试Hook手表端下发的指令（反向同步）
        hookWatchReverseSync(lpparam.classLoader);
    }

    // 监听勿扰模式（Zen Mode）
    private void registerPhoneDndObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        Uri zenModeUri = Settings.Global.getUriFor(Settings.Global.ZEN_MODE);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingFromWatch.get()) return;
                int zenMode = Settings.Global.getInt(resolver, Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
                boolean isDndOn = (zenMode != Settings.Global.ZEN_MODE_OFF);
                XposedBridge.log(TAG + " [手机] 勿扰模式变化: " + isDndOn);
                sendVirtualNotification("勿扰模式", isDndOn ? "已开启" : "已关闭", 1001);
            }
        };
        resolver.registerContentObserver(zenModeUri, false, observer);
        XposedBridge.log(TAG + " 已注册勿扰监听");
    }

    // 监听铃声模式（静音/振动/正常）
    private void registerRingerModeObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        Uri ringerUri = Settings.System.getUriFor(Settings.System.MODE_RINGER);
        if (ringerUri == null) {
            XposedBridge.log(TAG + " 无法获取铃声模式URI，尝试替代方案");
            return;
        }
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingFromWatch.get()) return;
                int mode = audioManager.getRingerMode();
                String state;
                switch (mode) {
                    case AudioManager.RINGER_MODE_SILENT: state = "静音"; break;
                    case AudioManager.RINGER_MODE_VIBRATE: state = "振动"; break;
                    default: state = "响铃"; break;
                }
                XposedBridge.log(TAG + " [手机] 铃声模式变化: " + state);
                sendVirtualNotification("铃声模式", state, 1002);
            }
        };
        resolver.registerContentObserver(ringerUri, false, observer);
        XposedBridge.log(TAG + " 已注册铃声监听");
    }

    // 发送虚拟通知，触发健康APP同步到手表
    private void sendVirtualNotification(String title, String content, int id) {
        try {
            String channelId = "oppo_watch_sync";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(channelId,
                        "手表同步", NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(appContext, channelId);
            } else {
                builder = new Notification.Builder(appContext);
            }
            Notification notification = builder
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(id, notification);
            XposedBridge.log(TAG + " 已发送虚拟通知: " + title + " - " + content);
            // 1秒后自动取消，避免堆积
            new Handler(Looper.getMainLooper()).postDelayed(() -> notificationManager.cancel(id), 1000);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 发送虚拟通知失败: " + e.getMessage());
        }
    }

    // 尝试Hook手表→手机的反向同步（需要逆向找到正确类/方法）
    private void hookWatchReverseSync(ClassLoader classLoader) {
        // 常见的类名可能性（需要你反编译后确认）
        String[] possibleClasses = {
            "com.heytap.wearable.device.DeviceManager",
            "com.oplus.wearable.provider.WatchStateProvider",
            "com.heytap.health.service.PhoneStateSyncService"
        };
        for (String className : possibleClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz != null) {
                // 可能的方法名
                String[] methods = {"onPhoneDndChanged", "onPhoneSilentModeChanged", "syncDndFromWatch"};
                for (String method : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                boolean enabled = (boolean) param.args[0];
                                XposedBridge.log(TAG + " [手表] 收到反向同步: " + enabled);
                                syncingFromWatch.set(true);
                                setSystemDndMode(enabled);
                                syncingFromWatch.set(false);
                            }
                        });
                        XposedBridge.log(TAG + " 成功Hook反向同步: " + className + "#" + method);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
        XposedBridge.log(TAG + " 未找到反向同步Hook点，手表→手机可能无法同步");
    }

    // 设置手机勿扰模式（反向同步时调用）
    private void setSystemDndMode(boolean enable) {
        if (notificationManager == null) return;
        try {
            int filter = enable ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL;
            if (Build.VERSION.SDK_INT >= 23) {
                notificationManager.setInterruptionFilter(filter);
            } else {
                Settings.Global.putInt(appContext.getContentResolver(), Settings.Global.ZEN_MODE, enable ? 2 : 0);
            }
            XposedBridge.log(TAG + " 已设置手机勿扰: " + enable);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置手机勿扰失败: " + e.getMessage());
        }
    }
}
