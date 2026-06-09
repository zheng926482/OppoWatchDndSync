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
    private Context appCtx;
    private boolean isSyncing = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PKG)) return;
        XposedBridge.log("===== OppoWatchDndSync 已加载 =====");

        fakeOppoDevice(lpparam);

        Application app = (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
        );
        appCtx = app.getApplicationContext();

        listenSystemDnd();
        hookWatchDndCallback(lpparam);
    }

    private void fakeOppoDevice(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.setStaticObjectField(Build.class, "BRAND", "OPPO");
        XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER", "OPPO");
        XposedHelpers.setStaticObjectField(Build.class, "MODEL", "OPPO Find X5");

        try {
            Class<?> utils = XposedHelpers.findClass("com.heytap.wearable.utils.DeviceUtils", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(utils, "isOppoDevice", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Exception e) {
            XposedBridge.log("设备校验Hook跳过：" + e.getMessage());
        }
    }

    private void listenSystemDnd() {
        ContentObserver observer = new ContentObserver(new Handler(appCtx.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (isSyncing) return;

                NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
                int mode = nm.getCurrentInterruptionFilter();
                boolean enabled = (mode == NotificationManager.INTERRUPTION_FILTER_NONE || mode == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                XposedBridge.log("手机勿扰状态：" + enabled);
                syncDndToWatch(enabled);
            }
        };

        appCtx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("zen_mode"),
                true,
                observer
        );
    }

    private void syncDndToWatch(boolean enable) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.heytap.wearable.device.WearDeviceManager", appCtx.getClassLoader());
            Object manager = XposedHelpers.callStaticMethod(cls, "getInstance");
            XposedHelpers.callMethod(manager, "syncPhoneZenMode", enable);
            XposedBridge.log("已同步到手表：" + enable);
        } catch (Throwable e) {
            XposedBridge.log("同步失败：" + e.toString());
        }
    }

    private void hookWatchDndCallback(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.heytap.wearable.observer.ZenModeObserver", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cls, "onZenModeChange", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean state = (boolean) param.args[0];
                    XposedBridge.log("手表控制勿扰：" + state);

                    isSyncing = true;
                    setSystemDnd(state);
                    new Handler(appCtx.getMainLooper()).postDelayed(() -> isSyncing = false, 500);
                }
            });
        } catch (Exception e) {
            XposedBridge.log("手表Hook失败：" + e.getMessage());
        }
    }

    private void setSystemDnd(boolean enable) {
        NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        }
    }
}
