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
            return;
        }

        XposedBridge.log(TAG + " ========== 初始化，包名: " + lpparam.packageName + " ==========");

        try {
            appContext = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                    "currentApplication"
            );
            XposedBridge.log(TAG + " Context 获取成功");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 获取Context失败: " + e.getMessage());
        }

        if (appContext == null) {
            XposedBridge.log(TAG + " Context 为空");
        }

        try {
            if (appContext != null) {
                notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " 获取服务失败: " + e.getMessage());
        }

        try {
            hookAllMethods(lpparam.classLoader);
            XposedBridge.log(TAG + " 方法 Hook 完成");
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Hook 失败: " + e.getMessage());
        }
        
        XposedBridge.log(TAG + " ========== 初始化完成 ==========");
    }

    private void hookAllMethods(ClassLoader cl) {
        // Hook OPPO 手表相关的所有类
        String[] targetClasses = {
            "com.oplus.wearable.provider.DeviceProvider",
            "com.heytap.wearable.provider.WatchProvider",
            "com.oplus.wearable.device.DeviceManager",
            "com.heytap.wearable.device.DeviceManager",
            "com.oplus.wearable.transport.WatchSyncManager",
            "com.heytap.wearable.transport.WatchSyncManager"
        };

        for (String className : targetClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, cl);
                if (clazz != null) {
                    Method[] methods = clazz.getDeclaredMethods();
                    XposedBridge.log(TAG + " 扫描类: " + className + " (" + methods.length + " 个方法)");
                    
                    for (Method method : methods) {
                        String methodName = method.getName();
                        Class<?>[] paramTypes = method.getParameterTypes();
                        
                        // 查找与 DND/静音/铃声相关的方法
                        if (methodName.contains("Silent") || methodName.contains("Dnd") || 
                            methodName.contains("Mute") || methodName.contains("Ringer") ||
                            methodName.contains("Ringer") || methodName.contains("changed") ||
                            methodName.contains("Changed")) {
                            
                            XposedBridge.log(TAG + " 发现目标方法: " + className + "#" + methodName + 
                                " 参数:" + paramTypes.length);
                            
                            // Hook 该方法
                            try {
                                if (paramTypes.length == 1 && (paramTypes[0] == boolean.class || 
                                    paramTypes[0] == int.class)) {
                                    
                                    XposedHelpers.findAndHookMethod(clazz, methodName, paramTypes[0], 
                                        new XC_MethodHook() {
                                            @Override
                                            protected void beforeHookedMethod(MethodHookParam param) {
                                                XposedBridge.log(TAG + " =====> 方法被调用: " + 
                                                    param.method.getName() + ", 参数: " + param.args[0]);
                                            }
                                            
                                            @Override
                                            protected void afterHookedMethod(MethodHookParam param) {
                                                XposedBridge.log(TAG + " =====> 方法执行完毕: " + 
                                                    param.method.getName());
                                            }
                                        });
                                    XposedBridge.log(TAG + " 成功 Hook: " + methodName);
                                }
                            } catch (Throwable e) {
                                XposedBridge.log(TAG + " Hook 失败 " + methodName + ": " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                // 类不存在，继续下一个
            } catch (Throwable e) {
                XposedBridge.log(TAG + " 处理类失败 " + className + ": " + e.getMessage());
            }
        }
    }
}
