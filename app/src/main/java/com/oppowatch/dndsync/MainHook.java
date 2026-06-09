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
    // 欢太健康目标包名
    private static final String TARGET_HEYTAP_PKG = "com.heytap.health";
    private Context healthAppContext = null;
    private boolean lockSync = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 模块加载标记，只要模块生效一定会打印这条日志
        XposedBridge.log("[OppoDndSync] ========== 模块已初始化 ==========");
        XposedBridge.log("[OppoDndSync] 当前进程包名：" + lpparam.packageName);

        // 分支1：运行在欢太健康APP进程
        if (lpparam.packageName.equals(TARGET_HEYTAP_PKG)) {
            XposedBridge.log("[OppoDndSync] 进入欢太健康APP进程，执行Hook逻辑");
            initHeyTapHook(lpparam);
            return;
        }

        // 分支2：其他进程（模块自身进程），全局监听系统勿扰
        XposedBridge.log("[OppoDndSync] 全局进程，注册系统勿扰监听");
        listenGlobalZenMode(lpparam);
    }

    // ===================== 欢太健康APP内部Hook逻辑 =====================
    private void initHeyTapHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 获取健康APP上下文
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication"
            );
            healthAppContext = app.getApplicationContext();
            XposedBridge.log("[OppoDndSync] 获取健康APP上下文成功");

            // 1. 伪装OPPO机型绕过设备校验
            fakeOppoBrand(lpparam);

            // 2. Hook手表下发勿扰回调（手表操作同步到手机）
            hookWatchZenCallback(lpparam);

        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 健康APP初始化异常：" + e.getMessage());
            XposedBridge.log("[OppoDndSync] 异常堆栈：" + XposedBridge.getStackTrace(e));
        }
    }

    // 伪装OPPO设备，绕过isOppoDevice校验
    private void fakeOppoBrand(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, "BRAND", "OPPO");
            XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER", "OPPO");

            Class<?> deviceUtilsCls = XposedHelpers.findClass("com.heytap.wearable.utils.DeviceUtils", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceUtilsCls, "isOppoDevice", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                    XposedBridge.log("[OppoDndSync] 强制返回OPPO设备");
                }
            });
            XposedBridge.log("[OppoDndSync] 机型伪装Hook完成");
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] DeviceUtils类未找到，跳过：" + e.getMessage());
        }
    }

    // 手机勿扰状态推送至OPPO手表
    public void sendDndToWatch(boolean enable) {
        if (healthAppContext == null) {
            XposedBridge.log("[OppoDndSync] 健康APP未就绪，无法同步至手表");
            return;
        }
        try {
            Class<?> wearManagerCls = XposedHelpers.findClass("com.heytap.wearable.device.WearDeviceManager", healthAppContext.getClassLoader());
            Object instance = XposedHelpers.callStaticMethod(wearManagerCls, "getInstance");
            XposedHelpers.callMethod(instance, "syncPhoneZenMode", enable);
            XposedBridge.log("[OppoDndSync] 同步勿扰至手表成功，状态：" + enable);
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] 推送手表失败，类/方法不匹配：" + e.getMessage());
            XposedBridge.log("[OppoDndSync] 堆栈：" + XposedBridge.getStackTrace(e));
        }
    }

    // Hook手表修改勿扰回调，同步到手机系统
    private void hookWatchZenCallback(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> zenObserverCls = XposedHelpers.findClass("com.heytap.wearable.observer.ZenModeObserver", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(zenObserverCls, "onZenModeChange", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean watchDndState = (boolean) param.args[0];
                    XposedBridge.log("[OppoDndSync] 手表触发勿扰变更：" + watchDndState);

                    lockSync = true;
                    setSystemZen(watchDndState);
                    new Handler(healthAppContext.getMainLooper()).postDelayed(() -> lockSync = false, 600);
                }
            });
            XposedBridge.log("[OppoDndSync] 手表勿扰回调Hook注册成功");
        } catch (Throwable e) {
            XposedBridge.log("[OppoDndSync] ZenModeObserver Hook失败：" + e.getMessage());
        }
    }

    // ===================== 全局系统勿扰监听（模块进程，常驻） =====================
    private void listenGlobalZenMode(XC_LoadPackage.LoadPackageParam lpparam) {
        Context ctx = lpparam.appInfo.targetSdkVersion >= 24
                ? lpparam.thisApp
                : lpparam.appInfo.processName;
        ContentObserver zenObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (lockSync) return;

                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                int filterMode = nm.getCurrentInterruptionFilter();
                boolean dndOn = filterMode == NotificationManager.INTERRUPTION_FILTER_NONE
                        || filterMode == NotificationManager.INTERRUPTION_FILTER_ALARMS;
                XposedBridge.log("[OppoDndSync] 检测手机系统勿扰变更：" + dndOn);

                // 跨进程调用推送至手表
                sendDndToWatch(dndOn);
            }
        };
        ctx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("zen_mode"),
                true,
                zenObserver
        );
        XposedBridge.log("[OppoDndSync] 全局勿扰监听注册完成");
    }

    // 修改系统全局勿扰模式
    private void setSystemZen(boolean enable) {
        NotificationManager nm = (NotificationManager) healthAppContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        }
        XposedBridge.log("[OppoDndSync] 已修改手机系统勿扰：" + enable);
    }
}
