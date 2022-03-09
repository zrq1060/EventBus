/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        // 1、先从之前缓存的集合中获取
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            // 2、如果之前缓存了，直接返回
            return subscriberMethods;
        }

        if (ignoreGeneratedIndex) {
            // 忽略注解处理器生成的索引，所以直接用反射获取，ignoreGeneratedIndex默认为false。
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            // 3、获取所有订阅方法集合
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            // 4、放入缓存集合中
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        // 5、从数组中获取FindState对象，如果有直接返回，如果没有创建一个新的FindState对象。
        FindState findState = prepareFindState();
        // 6、根据事件订阅者初始化findState
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            // 7、获取subscriberInfo，初始化为null。
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                // 通过注解处理器处理
                // 获取到所有订阅者方法
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        // 通过检查，给findState.subscriberMethods增加订阅者方法
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                // 8、通过反射的方式获取订阅者中的Method
                findUsingReflectionInSingleClass(findState);
            }
            // 移动到父类，以便后续操作。
            findState.moveToSuperclass();
        }
        // 释放
        return getMethodsAndRelease(findState);
    }

    // 从findState中获取订阅者所有方法并释放
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        // 获取订阅者所有订阅方法集合
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        // findState进行回收
        findState.recycle();
        // 将回收后的findState，恢复到FIND_STATE_POOL中。
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        // 返回集合
        return subscriberMethods;
    }

    // 准备FindState对象，先从缓存中获取，如果有则获取并在缓存中移除；如果没有则进行创建并返回。
    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    // 获取订阅者信息
    private SubscriberInfo getSubscriberInfo(FindState findState) {
        // 处理SubscriberInfoIndex添加手动创建的AbstractSubscriberInfo（一般不会手动创建）。
        // -findState.subscriberInfo != null：说明调用者调用findState.subscriberInfo = getSubscriberInfo(findState)的getSubscriberInfo(findState)返回的不为null，即subscriberInfoIndexes有值。
        // -findState.subscriberInfo.getSuperSubscriberInfo() != null：说明手动创建的AbstractSubscriberInfo，而不是注解处理器生成添加的SimpleSubscriberInfo。
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                // 要获取的Class等于父类的Class，则返回父类的Class。
                return superclassInfo;
            }
        }
        // 处理addIndex()添加的SubscriberInfoIndex。
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    // 通过反射获取订阅者方法
    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        // 获取FindState，有复用。
        FindState findState = prepareFindState();
        // 初始化FindState，因为有复用，所以得初始化。
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            // 反射获取，如果找到则存到findState.subscriberMethods里。
            findUsingReflectionInSingleClass(findState);
            // 移动到父类，会更改findState.clazz
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            // 这比getMethods快，特别是当订阅者是像Activities这样的胖类时
            // 9、订阅者中所有声明的方法，放入数组中。
            // getDeclaredMethods，获取的是当前类的所有方法。
            // getMethods，获取的是当前类及其所有父类的所有公共方法。
            methods = findState.clazz.getDeclaredMethods();
            // 因为getDeclaredMethods，获取的是当前类的所有方法，所以findState.skipSuperClasses为false，为不跳过父类，即继续查找父类。
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                // 10、获取订阅者中声明的public方法，设置跳过父类
                // 因为getMethods，获取的是当前类及其所有父类的所有公共方法，所以findState.skipSuperClasses为true，为跳过父类，即不查找父类。
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    // 忽略注解生成器生成的索引，提示：请考虑使用EventBus注释处理器来避免反射。
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    // 忽略注解生成器生成的索引，提示：请使这个类对EventBus注释处理器可见，以避免反射。
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            findState.skipSuperClasses = true;
        }
        // 遍历这些方法
        for (Method method : methods) {
            // 11、获取方法的修饰符:public、private等等
            int modifiers = method.getModifiers();
            // 12、订阅方法为public同时不是abstract、static、bridge、synthetic。
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                // 13、方法参数类型数组
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    // 14、获取方法的注解
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    // 15、如果有注解
                    if (subscribeAnnotation != null) {
                        Class<?> eventType = parameterTypes[0];
                        // 16、将method和eventType放入到findState进行检查
                        if (findState.checkAdd(method, eventType)) {
                            // 17、获取注解中的threadMode对象
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            // 18、新建一个SubscriberMethod对象，同时加入到findState中
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    // 非1个参数，并且是严格模式，并且方法上有Subscribe注解，则抛异常警告。
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                // 方法的修饰符不符合，并且是严格模式，并且方法上有Subscribe注解，则抛异常警告。
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    static class FindState {
        // 订阅者方法集合
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        // 订阅者Class（不会移动）
        Class<?> subscriberClass;
        // 当前Class（会移动，会移动成父类Class）
        Class<?> clazz;
        // 是否要跳过父类
        boolean skipSuperClasses;
        // 订阅者信息
        SubscriberInfo subscriberInfo;

        // 初始化FindState
        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        // 回收，各属性清空。
        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        // 检查是否增加，返回true为增加，返回false为不增加。
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            // 2级检查:第1级只有事件类型(快速)，第2级需要时有完整的签名。
            // 通常，订阅者没有侦听相同事件类型的方法。
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                // 之前没有此event类型的方法，则返回true使其添加。
                return true;
            } else {
                // 之前有此event类型的方法，则根据其签名返回其结果。
                // -说明：
                // --1.当前类及其父类，所有Subscribe注解的方法中，有相同Event类型参数的方法（可能是不同方法名同参类型，或者子类覆盖父类同方法名同参类型）。
                // --2.existing为第1个，method为第2个，method已经添加到anyMethodByEventType中。
                if (existing instanceof Method) {
                    // 只有此Event类型参数的方法，第一次才能进入，因为后面会使用FindState替换掉此Method，所以就不会再次进入此if判断。
                    // 所以要把第一次的existing加入到subscriberClassByMethodKey中，好进行后续的签名比较，因为它之前没有加入。
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        // 偏执检查
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    // 将任何非Method对象“消费”现有的Method，使其后续不会再进入此if判断。
                    anyMethodByEventType.put(eventType, this);
                }
                // 检查方法签名是否相同，确定是否要增加。
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            // Builder清空
            methodKeyBuilder.setLength(0);
            // Builder拼接方法名
            methodKeyBuilder.append(method.getName());
            // Builder拼接event全类名
            methodKeyBuilder.append('>').append(eventType.getName());

            // 方法key，为：方法名>event全类名。
            String methodKey = methodKeyBuilder.toString();
            // 方法所在的类，即订阅者或其父类的类。
            Class<?> methodClass = method.getDeclaringClass();
            // 存放到subscriberClassByMethodKey中，并接收返回值methodClassOld。
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                // 只有在子类中没有找到时才添加

                // 1.methodClassOld == null：进入到这个判断，则说明methodClassOld为空，说明之前没有出现过与此Method相同方法名，并且相同Event类型参数的方法。
                // 2.methodClassOld.isAssignableFrom(methodClass)：进入到这个判断，则说明methodClassOld不为空，并且methodClassOld.isAssignableFrom(methodClass)为true。
                // -2.1.methodClassOld != null：说明之前有出现过与此Method相同方法名，并且相同Event类型参数的方法，即子类覆盖了父类同方法名同参类型的方法。
                // -2.2.methodClassOld.isAssignableFrom(methodClass)为true：说明methodClassOld为父类，methodClass为子类。即先遍历的父类方法后遍历的子类方法，产生它的原因分析：
                // --2.2.1.methods = findState.clazz.getDeclaredMethods()：说明getDeclaredMethods，获取的是当前类的所有方法，然后它会继续遍历父类，所以它是有序的（先子类后父类），所以不是它产生的。
                // --2.2.2.methods = findState.clazz.getMethods()：说明getMethods，获取的是当前类及其所有父类的所有公共方法（无序的），所以它是有可能出现先父类后子类的，所以有可能是它产生的。但是出现这种情况的几率很少，因为getMethods()是在getDeclaredMethods()报错后才调用的。

                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                // 恢复put时，旧的类在类层次结构的下方

                // 1.进入到这个判断，则说明methodClassOld不为空，并且methodClassOld.isAssignableFrom(methodClass)为false。
                // 即子类覆盖了父类的方法，且methodClassOld为子类，methodClass为父类，且先遍历的子类方法后遍历的父类方法，替换父类的methodClass保留子类的methodClassOld。
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                // 返回false，则说明在子类中有，则不再增加。
                return false;
            }
        }

        void moveToSuperclass() {
            if (skipSuperClasses) {
                // 跳过父类，则不会获取父类，clazz为空，使其不再后续循环。
                clazz = null;
            } else {
                // 不跳过父类，则获取父类，使其继续后续循环。
                clazz = clazz.getSuperclass(); // 调用moveToSuperclass()方法前已经判断clazz不为空
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                // -跳过系统类会降低性能
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
    }

}
