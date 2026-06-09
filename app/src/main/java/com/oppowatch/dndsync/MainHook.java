import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.app.NotificationManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PKG = "com.heytap.health";
    private Context appContext;
    private boolean syncFlag = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PKG)) {
            return;
        }

        XposedBridge.log("[OppoDndSync] 已成功挂载到 OPPO 健康");

        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            );
            appContext = app.getApplicationContext();

            fakeDevice(lpparam);
            listenSystemDnd();
            hookWatchCommand(lpparam);

        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 初始化错误: " + e.getMessage());
        }
    }

    private void fakeDevice(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "BRAND", "OPPO");
            XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER", "OPPO");

            Class<?> deviceUtils = XposedHelpers.findClass("com.heytap.wearable.utils.DeviceUtils", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceUtils, "isOppoDevice", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                    XposedBridge.log("[OppoDndSync] 已伪装成 OPPO 设备");
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 设备伪装跳过: " + e.getMessage());
        }
    }

    private void listenSystemDnd() {
        ContentObserver observer = new ContentObserver(new Handler(appContext.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (syncFlag) return;

                NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                int mode = nm.getCurrentInterruptionFilter();
                boolean enabled = (mode == NotificationManager.INTERRUPTION_FILTER_NONE || mode == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                XposedBridge.log("[OppoDndSync] 手机勿扰状态: " + enabled);
                sendToWatch(enabled);
            }
        };

        appContext.getContentResolver().registerContentObserver(
            Settings.Global.getUriFor("zen_mode"),
            true,
            observer
        );
        XposedBridge.log("[OppoDndSync] 已监听手机勿扰");
    }

    private void sendToWatch(boolean enable) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.heytap.wearable.device.WearDeviceManager", appContext.getClassLoader());
            Object manager = XposedHelpers.callStaticMethod(cls, "getInstance");
            XposedHelpers.callMethod(manager, "syncPhoneZenMode", enable);
            XposedBridge.log("[OppoDndSync] 同步到手表: " + enable);
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 同步失败: " + e.toString());
        }
    }

    private void hookWatchCommand(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.heytap.wearable.observer.ZenModeObserver", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cls, "onZenModeChange", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean state = (boolean) param.args[0];
                    XposedBridge.log("[OppoDndSync] 手表控制勿扰: " + state);

                    syncFlag = true;
                    setSystemDnd(state);
                    new Handler(appContext.getMainLooper()).postDelayed(() -> syncFlag = false, 500);
                }
            });
            XposedBridge.log("[OppoDndSync] 已监听手表勿扰指令");
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 手表监听失败: " + e.getMessage());
        }
    }

    private void setSystemDnd(boolean enable) {
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        }
    }
}
