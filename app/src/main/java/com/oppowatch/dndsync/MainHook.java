import android.app.Application;
import android.content.Context;
import android.content.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.app.NotificationManager;
import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    // 欢太健康目标包名
    private static final String TARGET_PKG = "com.heytap.health";
    private Context appCtx;
    private boolean isSyncing = false; // 防循环同步锁

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        // 只Hook OPPO健康APP
        if (!pkg.equals(TARGET_PKG)) return;
        XposedBridge.log("===== OppoWatchDndSync 已挂载目标APP =====");

        // 1. 伪装OPPO设备，绕过机型校验（关键修复点）
        fakeOppoDeviceInfo(lpparam);

        // 2. 获取APP上下文
        Application app = (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
        );
        appCtx = app.getApplicationContext();

        // 3. 监听系统勿扰变化，同步到手表（手机→手表）
        listenSystemZenMode();

        // 4. Hook手表下发勿扰指令，同步回手机（手表→手机）
        hookWatchZenCallback(lpparam);
    }

    // -------------------------- 1. 伪装OPPO机型，绕过APP校验 --------------------------
    private void fakeOppoDeviceInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        // 伪造Build硬件标识
        XposedHelpers.setStaticObjectField(Build.class, "BRAND", "OPPO");
        XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER", "OPPO");
        XposedHelpers.setStaticObjectField(Build.class, "PRODUCT", "CPH2487");
        XposedHelpers.setStaticObjectField(Build.class, "MODEL", "OPPO Find X6");

        // Hook机型校验方法，强制返回OPPO
        try {
            Class<?> deviceCheckCls = XposedHelpers.findClass("com.heytap.wearable.utils.DeviceUtils", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceCheckCls, "isOppoDevice", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
            XposedBridge.log("机型伪装完成，已绕过OPPO设备校验");
        } catch (Throwable e) {
            XposedBridge.log("DeviceUtils 类未找到，跳过机型Hook：" + e.getMessage());
        }
    }

    // -------------------------- 2. 监听系统勿扰，下发至手表 syncPhoneZenMode --------------------------
    private void listenSystemZenMode() {
        ContentObserver zenObserver = new ContentObserver(new Handler(appCtx.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (isSyncing) return; // 防止手表回调触发死循环

                NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
                int filter = nm.getCurrentInterruptionFilter();
                boolean dndEnable = filter == INTERRUPTION_FILTER_NONE || filter == INTERRUPTION_FILTER_ALARMS;

                XposedBridge.log("检测到系统勿扰变更：" + dndEnable);
                syncDndToWatch(dndEnable);
            }
        };
        appCtx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                true,
                zenObserver
        );
    }

    // 【核心修复方法】新版欢太健康下发勿扰API，替换失效的setPhoneSilentMode
    private void syncDndToWatch(boolean enable) {
        try {
            // 新版正确类路径：com.heytap.wearable.device.WearDeviceManager
            Class<?> wearDeviceMgrCls = XposedHelpers.findClass(
                    "com.heytap.wearable.device.WearDeviceManager",
                    appCtx.getClassLoader()
            );
            // 获取单例 getInstance()
            Object deviceMgr = XposedHelpers.callStaticMethod(wearDeviceMgrCls, "getInstance");
            // 真实有效方法名：syncPhoneZenMode(boolean isZenOn)
            XposedHelpers.callMethod(deviceMgr, "syncPhoneZenMode", enable);
            XposedBridge.log("成功同步勿扰至手表：" + enable);
        } catch (Throwable e) {
            XposedBridge.log("同步手表勿扰失败（类/方法不匹配）：" + e.toString());
        }
    }

    // -------------------------- 3. Hook手表勿扰回调，同步到手机系统 --------------------------
    private void hookWatchZenCallback(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 手表下发勿扰状态回调类：com.heytap.wearable.observer.ZenModeObserver
            Class<?> zenObserverCls = XposedHelpers.findClass(
                    "com.heytap.wearable.observer.ZenModeObserver",
                    lpparam.classLoader
            );
            // 手表修改勿扰后触发：onZenModeChange(boolean watchDndState)
            XposedHelpers.findAndHookMethod(
                    zenObserverCls,
                    "onZenModeChange",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean watchDnd = (boolean) param.args[0];
                            XposedBridge.log("手表下发勿扰指令：" + watchDnd);
                            isSyncing = true;
                            setSystemZenMode(watchDnd);
                            // 延时解锁同步锁，避免循环触发
                            new Handler(appCtx.getMainLooper()).postDelayed(() -> isSyncing = false, 800);
                        }
                    }
            );
            XposedBridge.log("手表勿扰回调Hook完成");
        } catch (Throwable e) {
            XposedBridge.log("ZenModeObserver Hook失败：" + e.getMessage());
        }
    }

    // 控制安卓系统全局勿扰模式
    private void setSystemZenMode(boolean enable) {
        NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (enable) {
            nm.setInterruptionFilter(INTERRUPTION_FILTER_NONE);
        } else {
            nm.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
        }
    }
}
