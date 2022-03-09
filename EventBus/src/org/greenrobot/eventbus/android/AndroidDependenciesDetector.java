package org.greenrobot.eventbus.android;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressWarnings("TryWithIdenticalCatches")
// Android依赖发现者
public class AndroidDependenciesDetector {

    // Android SDK是否是可用的，其内部判断为Looper.getMainLooper()是否有值。
    public static boolean isAndroidSDKAvailable() {

        try {
            // 反射获取Looper类Class
            Class<?> looperClass = Class.forName("android.os.Looper");
            // 反射获取Looper类getMainLooper方法Method
            Method getMainLooper = looperClass.getDeclaredMethod("getMainLooper");
            // 反射获取Looper类getMainLooper方法返回值
            Object mainLooper = getMainLooper.invoke(null);
            // 判断Looper类getMainLooper方法返回值是否为空
            return mainLooper != null;
        }
        catch (ClassNotFoundException ignored) {}
        catch (NoSuchMethodException ignored) {}
        catch (IllegalAccessException ignored) {}
        catch (InvocationTargetException ignored) {}

        return false;
    }

    private static final String ANDROID_COMPONENTS_IMPLEMENTATION_CLASS_NAME = "org.greenrobot.eventbus.android.AndroidComponentsImpl";

    // AndroidComponents是否是可用的，其内部判断为是否有AndroidComponentsImpl类。
    public static boolean areAndroidComponentsAvailable() {

        try {
            Class.forName(ANDROID_COMPONENTS_IMPLEMENTATION_CLASS_NAME);
            return true;
        }
        catch (ClassNotFoundException ex) {
            return false;
        }
    }

    // 创建AndroidComponents类，其内部为反射创建AndroidComponentsImpl类。
    public static AndroidComponents instantiateAndroidComponents() {

        try {
            Class<?> impl = Class.forName(ANDROID_COMPONENTS_IMPLEMENTATION_CLASS_NAME);
            return (AndroidComponents) impl.getConstructor().newInstance();
        }
        catch (Throwable ex) {
            return null;
        }
    }
}
