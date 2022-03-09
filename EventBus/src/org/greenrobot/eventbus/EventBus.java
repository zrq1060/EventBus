/*
 * Copyright (C) 2012-2020 Markus Junginger, greenrobot (http://greenrobot.org)
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

import org.greenrobot.eventbus.android.AndroidDependenciesDetector;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Java and Android.
 * Events are posted ({@link #post(Object)}) to the bus, which delivers it to subscribers that have a matching handler
 * method for the event type.
 * To receive events, subscribers must register themselves to the bus using {@link #register(Object)}.
 * Once registered, subscribers receive events until {@link #unregister(Object)} is called.
 * Event handling methods must be annotated by {@link Subscribe}, must be public, return nothing (void),
 * and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
// EventBus是一个面向Java和Android的中央发布/订阅事件系统。事件被发布(post(Object))到总线，总线将其传递给具有与事件类型匹配的处理程序方法的订阅者。
// 要接收事件，订阅者必须使用register(Object)将自己注册到总线。注册后，订阅者接收事件，直到调用unregister(Object)为止。
// 事件处理方法必须由Subscribe注释，必须是公共的，不返回任何东西(void)，并且只有一个参数(事件)。
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;

    // currentPostingThreadState是包含了PostingThreadState的ThreadLocal对象
    // ThreadLocal是一个线程内部的数据存储类，通过它可以在指定的线程中存储数据，并且线程之间的数据是相互独立的。
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    // 线程池对象
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;

    /** Convenience singleton for apps using a process-wide EventBus instance. */
    // 方便的单例应用程序使用进程范围的EventBus实例。
    // 单例设计模式返回EventBus对象
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    // 调用EventBus构造方法
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    // 主要用于单元测试。
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    // 创建一个新的EventBus实例;每个实例都是一个单独的范围，事件在其中交付。要使用中心总线，请考虑getDefault()。
    public EventBus() {
        // 调用有参构造方法，传入一个EventBusBuilder对象
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        // 日志处理者
        logger = builder.getLogger();

        // =============以下3个集合很重要==================
        // [事件 --> 该事件的订阅关系的List] 的Map。
        // 1.key：Event的Class，value：Subscription（含有订阅者对象、订阅者方法的对象）的List。
        // 2.即通过它，方便通过Event，回调给所有，订阅者的订阅方法，回调对象。
        // 3.举例：有一个Event为MessageEvent，它被A、B、C三个类进行了订阅，则它里面有一条数据，
        // key为MessageEvent的Class，value为有3条数据（A、B、C三个类的Subscription）的List。
        subscriptionsByEventType = new HashMap<>();
        // [订阅者 --> 该订阅者订阅的事件的List] 的Map。
        // 1.key：订阅者对象Object，value：Event的Class的List。
        // 2.即通过它，方便通过订阅者对象，找到其订阅的所有Event类型。
        // 3.举例：有2个Event为MessageEvent、SayHelloEvent，它被A类都进行了订阅，则它里面有一条数据，
        // key为A类对象，value为有2条数据（MessageEvent、SayHelloEvent两个类的Class）的List。
        typesBySubscriber = new HashMap<>();
        // [粘性事件 --> 粘性事件对象] 的Map。
        // 1.key：Event的Class，value：Event的对象。
        stickyEvents = new ConcurrentHashMap<>();

        // =============以下在事件发送中很重要==================
        // 主线程支持，Android平台为DefaultAndroidMainThreadSupport，否则为null。
        mainThreadSupport = builder.getMainThreadSupport();
        // 主线程事件发送者，Android平台为HandlerPoster，否则为null。
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        // Background事件发送者
        backgroundPoster = new BackgroundPoster(this);
        // 异步事件发送者
        asyncPoster = new AsyncPoster(this);

        // =============以下为获取EventBusBuilder的配置==================
        // 添加注解处理器生成的索引SubscriberInfoIndex的个数
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        // 订阅者方法查找对象
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        // 调用订阅者方法异常时，是否打印异常信息，默认为true。
        logSubscriberExceptions = builder.logSubscriberExceptions;
        // 没有订阅者时，是否打印异常信息，默认为true。
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        // 调用订阅者方法异常时，是否发送SubscriberExceptionEvent事件，默认为true。
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        // 没有订阅者时，是否发送NoSubscriberEvent事件，默认为true。
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        // 调用订阅者方法异常时，是否抛出SubscriberException异常，默认为false。
        throwSubscriberException = builder.throwSubscriberException;
        // 事件是否有继承性，默认为true。
        eventInheritance = builder.eventInheritance;
        // 线程池，用于异步和后台事件传递，默认为Executors.newCachedThreadPool()。
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    // 注册给定的订阅者以接收事件。订阅者一旦不再对接收事件感兴趣，就必须调用unregister(Object)。
    // 订阅方具有必须由Subscribe注释的事件处理方法。Subscribe注释还允许像ThreadMode和priority这样的配置。
    public void register(Object subscriber) {
        if (AndroidDependenciesDetector.isAndroidSDKAvailable() && !AndroidDependenciesDetector.areAndroidComponentsAvailable()) {
            // 是android平台但是没依赖eventbus（Android）库，则抛出异常。
            throw new RuntimeException("It looks like you are using EventBus on Android, " +
                    "make sure to add the \"eventbus\" Android library to your dependencies.");
        }
        // 1、获取到订阅者的Class对象。
        Class<?> subscriberClass = subscriber.getClass();
        // 2、通过subscriberMethodFinder对象找到该订阅者的所有订阅者方法。
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        // 3、同步，保证线程安全。
        synchronized (this) {
            // 4、遍历集合进行订阅
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                // 5、订阅，传入订阅者对象、该订阅者的订阅者方法对象。
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    // 必须在同步块中调用
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        // 6、获取订阅者方法的事件类型
        Class<?> eventType = subscriberMethod.eventType;
        // 7、封装Subscription对象（之后会将newSubscription添加到subscriptionsByEventType中）。
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        // 8、通过事件类型获取该事件的Subscription集合（之后会将newSubscription添加到subscriptions中）。
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            // 9、集合为空，说明该事件为第一次订阅，则创建集合，并把当前Event类型存入subscriptionsByEventType中。
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            // 10、集合不为空，说明该事件已经被订阅过，则判断该订阅者是否有重复订阅的现象。
            if (subscriptions.contains(newSubscription)) {
                // 11、包含，则说明是重复订阅，此方法只有被register()方法调用，所以说明是重复注册，则抛出异常。
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        // 12、遍历该事件的所有订阅者，按照优先级进行插入。
        int size = subscriptions.size();
        // 13、i <= size，size最少为0，所以至少会执行一次for循环。
        for (int i = 0; i <= size; i++) {
            // 说明：
            // 1. i == size，则说明是最后一个。
            // 2. subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority，说明新的优先级高于当前的优先级。
            // 14、如果是最后一个，或者新的优先级高于当前的优先级，则添加到当前位置，即当前位置后移，整体从高到低排序。
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        // 15、获取该订阅者订阅的事件的集合（之后会将eventType添加到subscribedEvents中）。
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            // 16、集合为空，则说明是第一次添加订阅者，则创建集合，并把当前订阅者存入typesBySubscriber中。
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        // 17、将事件加入到该订阅者订阅的事件的集合中
        subscribedEvents.add(eventType);

        // 18、判断该订阅者方法是否是粘性事件，如果是粘性事件，则注册时就会通知事件，因为发送粘性事件不管之前注册还是之后注册都会通知。
        if (subscriberMethod.sticky) {
            // 19、是粘性事件，则进行通知订阅者方法。
            if (eventInheritance) { // eventInheritance：事件是否有继承性，默认为true。
                // 20、事件有继承性，则通知所有已发送粘性事件符合是其自己或者其子类的事件。
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                // 必须考虑eventType所有子类的现有粘性事件。
                // 注意:迭代所有事件可能会因为有大量粘性事件而效率低下，因此应该修改数据结构以允许更有效的查找(例如，一个额外的映射存储超类的子类:Class -> List)。

                // 21、获取所有已发送粘性事件，遍历判断是否符合是其自己或者其子类的事件。
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        // 22、符合是其自己或者其子类的事件，则进行通知。
                        Object stickyEvent = entry.getValue();
                        // 23、调用checkPostStickyEventToSubscription方法进行检测并发送。
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                // 24、事件没有继承性，则通知所有已发送粘性事件符合是其自己的事件。
                Object stickyEvent = stickyEvents.get(eventType);
                // 25、调用checkPostStickyEventToSubscription方法进行检测并发送，stickyEvent有可能为null，因为可能之前没发送过此类事件的粘性事件。
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            //如果订阅者试图中止事件，它将失败(事件在提交状态不被跟踪)
            //——>奇怪的情况，这里我们不考虑。
            // 26、如果粘性事件不为空，则进行发送粘性事件到订阅者方法，并传入是否是在主线程发送。
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }

    /**
     * Checks if the current thread is running in the main thread.
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     */
    // 检查当前线程是否在主线程中运行。如果没有主线程支持(例如非android)， "true"总是返回。在这种情况下，主线程订阅者总是在发布线程中被调用，而后台订阅者总是从后台海报中被调用。
    private boolean isMainThread() {
        return mainThreadSupport == null || mainThreadSupport.isMainThread();
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    // 只更新subscriptionsByEventType，而不是typesBySubscriber !调用者必须更新typesBySubscriber。
    // 传入eventType，为了更快的找到，否则只能全部遍历，暴力匹配了
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        // 6、获取该事件的所有订阅者
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            // 7、遍历上面的集合，找到此订阅者的Subscription，然后从此集合中移除。
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    // 8、找到，从此集合中移除。
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    // 从所有事件类注销给订阅者。
    public synchronized void unregister(Object subscriber) {
        // 1、获取订阅者订阅的所有事件
         // typesBySubscriber要移除自己，subscriptionsByEventType要移除subscriber内的多个类型
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            // 2、遍历此订阅者订阅的所有事件集合
            for (Class<?> eventType : subscribedTypes) {
                // 3、通过eventType，将此事件类型的Subscription集合中含有该订阅者的Subscription，从集合中移除。
                unsubscribeByEventType(subscriber, eventType);
            }
            // 4、将此订阅者从typesBySubscriber中移除
            typesBySubscriber.remove(subscriber);
        } else {
            // 5、未注册，调用了注销，Log打印警告。
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /** Posts the given event to the event bus. */
    // 将给定事件发送到事件总线。
    public void post(Object event) {
        // 1、获取当前线程的PostingThreadState，currentPostingThreadState是一个ThreadLocal对象，保证每个线程有一个PostingThreadState对象。
        PostingThreadState postingState = currentPostingThreadState.get();
        // 2、获取当前线程的事件队列
        List<Object> eventQueue = postingState.eventQueue;
        // 3、将要发送的事件加入到当前线程的事件队列中
        eventQueue.add(event);

        // 4、判断是否正在发送事件，如果正在发送中，则不再执行里面的逻辑，因为事件已经添加到eventQueue中，它会继续走里面while循环的判断逻辑。
        if (!postingState.isPosting) {
            // 5、事件未被发送中，则初始化PostingThreadState，遍历事件队列发送。
            // 6、判断当前线程是否是主线程
            postingState.isMainThread = isMainThread();
            // 7、标记当前线程正在发送中
            postingState.isPosting = true;
            // 8、如果当前线程已经取消，则抛出异常。
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                // 9、遍历事件队列，只要有事件，就一直发送。
                while (!eventQueue.isEmpty()) {
                    // 10、发送事件，eventQueue.remove(0)说明post进来的事件，会依次发送。
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                // 11、当前线程的所有事件完成，还原状态。
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    // 从订阅者的事件处理方法调用，进一步的事件传递将被取消。随后的订阅者将不会收到该事件。事件通常由高优先级订阅者取消(请参阅Subscribe.priority())。
    // 取消仅限于在发布线程ThreadMode.POSTING中运行的事件处理方法。
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    // 将给定的事件发布到事件总线并保持该事件(因为它是粘性的)。事件类型的最新粘性事件保存在内存中，以便订阅者使用Subscribe.sticky()将来访问。
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            // 1、将事件添加到粘性事件集合中
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        // 应该在它被放置后张贴，以防订阅者想立即删除
        // 2、发送事件
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    // 获取给定类型的最新粘滞事件。
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    // 删除并获取给定事件类型的最近粘性事件。
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    // 如果粘性事件等于给定事件，则移除粘性事件。
    // 返回：如果事件匹配且粘性事件已删除，则为true。
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    // 移除所有粘性事件。
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        // 11、获取发送事件的Class
        Class<?> eventClass = event.getClass();
        // 12、记录是否找到Subscription
        boolean subscriptionFound = false;
        if (eventInheritance) { // eventInheritance：事件是否有继承性，默认为true。
            // 13、事件有继承性，则发送[发送Event的所有父类以及所有父接口]的Event。
            // 14、获取到[发送Event的所有父类以及所有父接口]。
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            // 15、遍历集合，发送单个事件。
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                // 16、发送单个事件，并判断是否找到Subscription（只要有一个有，就代表找到）。
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            // 17、事件没有继承性，则发送[发送Event]的Event。
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {
            // 18、没有找到，判断是否打印Log，或者发送NoSubscriberEvent。
            if (logNoSubscriberMessages) {
                // 19、没有订阅者时，打印异常信息。
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                // 20、没有订阅者时，发送NoSubscriberEvent事件。
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            // 21、根据事件获取所有订阅它的订阅者，在同步方法内执行，保证了HashMap的get方法线程安全。
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            // 22、有订阅者，遍历集合进行发送，返回true（代表已经找到）。
            // 23、遍历所有此类型的订阅者进行发送
            for (Subscription subscription : subscriptions) {
                // 24、记录发送线程正在发布的Event
                postingState.event = event;
                // 25、记录发送线程正在发布的Subscription
                postingState.subscription = subscription;
                // 26、是否是中断的
                boolean aborted;
                try {
                    // 27、将事件发送给订阅者，postingState.isMainThread为post方法记录的。
                    postToSubscription(subscription, event, postingState.isMainThread);
                    // 28、事件是否被取消
                    aborted = postingState.canceled;
                } finally {
                    // 29、重置postingState
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    // 30、事件被取消，则取消此类型的全部发布。
                    break;
                }
            }
            return true;
        }
        // 31、无订阅者，不进行发送，返回false（代表没有找到）。
        return false;
    }

    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        // 32、根据订阅者方法ThreadMode的类型去判断是直接反射调用方法，还是将事件加入队列执行。
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING:
                // 33、发布线程（默认），事件的订阅和事件的发布处于同一线程。
                // 34、调用反射直接通知
                invokeSubscriber(subscription, event);
                break;
            case MAIN:
                // 35、主线程
                // -在Android上，订阅者将在Android的主线程（UI线程）中被调用。
                // --如果提交线程是主线程，则将直接调用订阅者方法，从而阻塞发布线程（步骤36）。
                // --否则，事件将排队等待传递（非阻塞）（步骤37）。
                // -如果不是在Android上，则行为与POSTING相同（步骤36）。
                if (isMainThread) {
                    // 36、在Android上并且是主线程，或者不在Android上，则行为与POSTING相同。
                    invokeSubscriber(subscription, event);
                } else {
                    // 37、在Android上并且不是主线程，将事件插入到主线程队列中，最后还是会调用EventBus.invokeSubscriber(PendingPost pendingPost)方法。
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case MAIN_ORDERED:
                // 38、主线程（有序），在Android上，订阅者将在Android的主线程（UI线程）中被调用。与MAIN不同的是，事件总是排队等待传递。这确保了post调用是非阻塞的。
                if (mainThreadPoster != null) {
                    // 39、在Android上，始终添加到主线程队列。
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    // 临时:技术上不正确，因为poster未与订阅者解耦
                    // 40、不在Android上，直接通过反射调用。
                    invokeSubscriber(subscription, event);
                }
                break;
            case BACKGROUND:
                // 41、后台线程
                // -在Android上，订阅者将在后台线程中被调用。
                // --如果发布线程不是主线程，则将在发布线程中直接调用订阅者方法（步骤43）。
                // --如果发布线程是主线程，则EventBus使用一个单独的后台线程，它将按顺序传递其所有事件（步骤42）。
                // -如果不在Android上，则始终使用后台线程（步骤42）。
                if (isMainThread) {
                    // 42、在Android上并且是主线程，或者不在Android上，则加入后台队列中执行。
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    // 43、在Android上并且不是主线程，直接通过反射调用。
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC:
                // 44、异步线程，订阅者将在单独的线程中调用。这始终独立于发布线程和主线程。使用此模式发布事件从不等待订阅者方法。
                // 45、加入异步队列中执行
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */
    // 查找所有类对象，包括超类和接口。也应该适用于接口。
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        // 使用了同步并且锁唯一，保证了线程有序执行。
        synchronized (eventTypesCache) {
            // 从缓存中获取当前Event的已维护的所有事件（父类、父接口）集合。
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                // 集合为空，说明是第一次获取，则创建集合，并维护集合，并将其添加到缓存中。
                eventTypes = new ArrayList<>();
                // 当前clazz默认为eventClass
                Class<?> clazz = eventClass;
                // 遍历当前clazz，只要不为空，就继续遍历。
                while (clazz != null) {
                    // 添加当前clazz，第一次会把eventClass添加进去。
                    eventTypes.add(clazz);
                    // 添加当前clazz的所有接口class
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    // 获取当前clazz的父类，以继续添加。
                    clazz = clazz.getSuperclass();
                }
                // 将维护好的集合，添加到缓存。
                eventTypesCache.put(eventClass, eventTypes);
            }
            // 最后返回此维护好的所有事件（父类、父接口）集合。
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    // 通过超级接口递归。
    // 循环添加当前事件的接口class
    // 举例：当前类，实现A、B两个接口，A实现C、D接口，B实现E接口，C、D、E未实现任何接口，最终会把A、B、C、D、E接口全部添加到eventTypes中。
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        // 遍历所有接口
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                // 此接口未被包含，则进行添加。
                eventTypes.add(interfaceClass);
                // 继续递归增加父接口的所有接口，使用递归以完成其所有父接口。
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    // 如果订阅仍处于活动状态，则调用订阅者。跳过订阅可防止注销(对象)和事件交付之间的竞争条件。否则，事件可能在订阅者注销后传递。
    // 这对于绑定到活动或片段的活动周期的主线程交付和注册尤其重要。
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            // 调用反射处理
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            // 反射通知，订阅者对象的订阅者方法，并传入event参数。
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            // 处理调用订阅者方法异常
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            // SubscriberExceptionEvent事件，判断调用订阅者方法异常时是否打印Log（不发送事件防止递归）。
            if (logSubscriberExceptions) {
                // 调用订阅者方法异常时，打印异常信息。
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            // 普通事件，判断调用订阅者方法异常时是否抛出异常、打印Log、发送SubscriberExceptionEvent。
            if (throwSubscriberException) {
                // 调用订阅者方法异常时，抛出SubscriberException异常。
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                // 调用订阅者方法异常时，打印异常信息。
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                // 调用订阅者方法异常时，发送SubscriberExceptionEvent事件。
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    // 对于ThreadLocal，设置(和获取多个值)要快得多
    // 每个线程中存储的数据
    final static class PostingThreadState {
        final List<Object> eventQueue = new ArrayList<>(); // 发送线程的事件队列
        boolean isPosting; // 发送线程是否正在发送中
        boolean isMainThread; // 发送线程是否在主线程
        Subscription subscription; // 发送线程正在发布的Subscription
        Object event; // 发送线程正在发布的Event
        boolean canceled; // 发送线程是否已取消
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
