package com.oppowatch.dndsync;

import android.app.Notification;
import android.app.NotificationChannel;
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

        registerPhoneDndObserver();
        registerRingerModeObserver();
        hookWatchReverseSync(lpparam.classLoader);
    }

    private void registerPhoneDndObserver() {
        ContentResolver resolver = appContext.getContentResolver();
        // 使用字符串常量代替 Settings.Global.ZEN_MODE
        Uri zenModeUri = Settings.Global.getUriFor("zen_mode");
        if (zenModeUri == null) return;

        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (syncingFromWatch.get()) return;
                // 读取 zen_mode 值：0关闭，1重要中断，2全部静音
                int zenMode = Settings.Global.getInt(resolver, "zen_mode", 0);
                boolean isDndOn = (zenMode != 0);
                XposedBridge.log(TAG + " [手机] 勿扰模式变化: " + isDndOn);
                sendVirtualNotification("勿扰模式", isDndOn ? "已开启" : "已关闭", 1001);
            }
        };
        resolver.registerContentObserver(zenModeUri, false, observer);
        XposedBridge.log(TAG + " 已注册勿扰监听");
    }

    private void registerRingerModeObserver() {
        if (audioManager == null) return;
        ContentResolver resolver = appContext.getContentResolver();
        Uri ringerUri = Settings.System.getUriFor(Settings.System.MODE_RINGER);
        if (ringerUri == null) {
            XposedBridge.log(TAG + " 无法获取铃声模式URI");
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
            // 1秒后自动取消
            new Handler(Looper.getMainLooper()).postDelayed(() -> notificationManager.cancel(id), 1000);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 发送虚拟通知失败: " + e.getMessage());
        }
    }

    // 尝试 Hook 手表→手机的反向同步（需要根据实际APP调整类名/方法名）
    private void hookWatchReverseSync(ClassLoader classLoader) {
        String[] possibleClasses = {
            "com.heytap.wearable.device.DeviceManager",
            "com.oplus.wearable.provider.WatchStateProvider",
            "com.heytap.health.service.PhoneStateSyncService"
        };
        for (String className : possibleClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz != null) {
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

    private void setSystemDndMode(boolean enable) {
        if (notificationManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int filter = enable ? NotificationManager.INTERRUPTION_FILTER_NONE
                                    : NotificationManager.INTERRUPTION_FILTER_ALL;
                notificationManager.setInterruptionFilter(filter);
            } else {
                // 低版本使用 Settings.Global
                Settings.Global.putInt(appContext.getContentResolver(), "zen_mode", enable ? 2 : 0);
            }
            XposedBridge.log(TAG + " 已设置手机勿扰: " + enable);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 设置手机勿扰失败: " + e.getMessage());
        }
    }
}
