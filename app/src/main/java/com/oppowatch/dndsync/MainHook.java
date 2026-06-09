package com.oppowatch.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.lang.reflect.Method;
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

        // 扫描所有类，寻找包含 Silent/Dnd/Ringer/Mute 的类
        scanAndHookClasses(lpparam.classLoader);
        XposedBridge.log(TAG + " ========== 初始化完成 ==========");
    }

    private void scanAndHookClasses(ClassLoader cl) {
        // 先尝试 Hook 已知的类
        String[] knownClasses = {
            "com.oplus.wearable.provider.DeviceProvider",
            "com.heytap.wearable.provider.WatchProvider",
            "com.oplus.wearable.device.DeviceManager",
            "com.heytap.wearable.device.DeviceManager"
        };

        for (String className : knownClasses) {
            tryHookClass(className, cl);
        }

        // 如果已知类都失败了，开始广泛扫描
        XposedBridge.log(TAG + " 开始广泛扫描包中的所有类...");
        
        try {
            // 获取 ClassLoader 中的所有已加载类（通过反射）
            // 这个方法有局限性，但可以找到一些类
            scanPackageClasses(cl);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 扫描失败: " + e.getMessage());
        }
    }

    private void tryHookClass(String className, ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            if (clazz == null) {
                XposedBridge.log(TAG + " 类不存在: " + className);
                return;
            }
            
            XposedBridge.log(TAG + " 找到类: " + className);
            
            // 获取所有方法
            Method[] methods = clazz.getDeclaredMethods();
            XposedBridge.log(TAG + " 类 " + className + " 有 " + methods.length + " 个方法");
            
            // 输出所有方法名
            for (Method method : methods) {
                String methodName = method.getName();
                Class<?>[] params = method.getParameterTypes();
                
                // 只输出可能相关的方法
                if (methodName.length() < 50 && 
                    (methodName.contains("on") || methodName.contains("set") || 
                     methodName.contains("get") || methodName.contains("sync"))) {
                    
                    String paramStr = "";
                    for (Class<?> p : params) {
                        paramStr += p.getSimpleName() + ",";
                    }
                    XposedBridge.log(TAG + "   方法: " + methodName + "(" + paramStr + ")");
                    
                    // 尝试 Hook 包含目标关键词的方法
                    if (methodName.contains("Silent") || methodName.contains("Dnd") || 
                        methodName.contains("Mute") || methodName.contains("Ringer") ||
                        methodName.contains("changed") || methodName.contains("Changed")) {
                        
                        tryHookMethod(clazz, methodName, method, params);
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 类扫描失败 " + className + ": " + e.getMessage());
        }
    }

    private void tryHookMethod(Class<?> clazz, String methodName, Method method, Class<?>[] paramTypes) {
        try {
            if (paramTypes.length == 0) {
                XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + " [HOOK] " + param.method.getName() + "()");
                    }
                });
            } else if (paramTypes.length == 1) {
                Class<?> paramType = paramTypes[0];
                XposedHelpers.findAndHookMethod(clazz, methodName, paramType, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + " [HOOK] " + param.method.getName() + "(" + param.args[0] + ")");
                        
                        // 处理回调
                        if (param.args[0] instanceof Boolean) {
                            boolean value = (boolean) param.args[0];
                            String name = param.method.getName();
                            if (name.contains("Silent") || name.contains("Dnd") || name.contains("Mute")) {
                                handleDndChange(value);
                            } else if (name.contains("Ringer")) {
                                handleRingerChange(value);
                            }
                        }
                    }
                });
            }
            XposedBridge.log(TAG + " 成功Hook: " + methodName);
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook失败 " + methodName + ": " + e.getClass().getSimpleName());
        }
    }

    private void scanPackageClasses(ClassLoader cl) {
        // 尝试一些通用的类名模式
        String[] packagePrefixes = {
            "com.oplus.wearable",
            "com.heytap.wearable",
            "com.heytap.health"
        };

        String[] classNamePatterns = {
            "DeviceProvider",
            "WatchProvider",
            "DeviceManager",
            "WatchManager",
            "SyncManager",
            "CallbackListener",
            "DndManager",
            "SilentManager",
            "RingerManager"
        };

        for (String prefix : packagePrefixes) {
            for (String pattern : classNamePatterns) {
                for (String sub : new String[]{"provider", "device", "transport", "manager", "service", ""}) {
                    String fullName = prefix + (sub.isEmpty() ? "" : "." + sub) + "." + pattern;
                    try {
                        Class<?> clazz = XposedHelpers.findClass(fullName, cl);
                        if (clazz != null) {
                            XposedBridge.log(TAG + " 扫描找到类: " + fullName);
                            tryHookClass(fullName, cl);
                        }
                    } catch (Throwable e) {
                        // 忽略
                    }
                }
            }
        }
    }

    private void handleDndChange(boolean dndOn) {
        if (syncingDndFromPhone.get()) return;
        
        syncingDndFromWatch.set(true);
        try {
            XposedBridge.log(TAG + " 处理DND变化: " + dndOn);
            
            if (notificationManager != null) {
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
}
