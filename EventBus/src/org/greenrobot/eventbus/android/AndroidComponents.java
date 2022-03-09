package org.greenrobot.eventbus.android;

import org.greenrobot.eventbus.Logger;
import org.greenrobot.eventbus.MainThreadSupport;

public abstract class AndroidComponents {

    // AndroidComponents类实现者
    private static final AndroidComponents implementation;

    static {
        // 如果Android SDK可用，则创建AndroidComponentsImpl实例。
        implementation = AndroidDependenciesDetector.isAndroidSDKAvailable()
            ? AndroidDependenciesDetector.instantiateAndroidComponents()
            : null;
    }

    // AndroidComponents是否可用
    public static boolean areAvailable() {
        return implementation != null;
    }

    // 获取AndroidComponents实例
    public static AndroidComponents get() {
        return implementation;
    }

    public final Logger logger;
    public final MainThreadSupport defaultMainThreadSupport;

    // 创建AndroidComponents，需要Logger、MainThreadSupport
    public AndroidComponents(Logger logger, MainThreadSupport defaultMainThreadSupport) {
        this.logger = logger;
        this.defaultMainThreadSupport = defaultMainThreadSupport;
    }
}
