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

/**
 * Each subscriber method has a thread mode, which determines in which thread the method is to be called by EventBus.
 * EventBus takes care of threading independently of the posting thread.
 *
 * @see EventBus#register(Object)
 */
public enum ThreadMode {
    /**
     * This is the default. Subscriber will be called directly in the same thread, which is posting the event. Event delivery
     * implies the least overhead because it avoids thread switching completely. Thus, this is the recommended mode for
     * simple tasks that are known to complete in a very short time without requiring the main thread. Event handlers
     * using this mode must return quickly to avoid blocking the posting thread, which may be the main thread.
     */
    // 事件的订阅和事件的发布处于同一线程。这是默认设置。事件传递意味着最少的开销，因为它完全避免了线程切换。
    // 因此，对于已知在非常短的时间内完成而不需要主线程的简单任务，这是推荐的模式。使用此模式的事件处理程序必须快速返回，以避免阻塞可能是主线程的发布线程。
    POSTING,

    /**
     * On Android, subscriber will be called in Android's main thread (UI thread). If the posting thread is
     * the main thread, subscriber methods will be called directly, blocking the posting thread. Otherwise the event
     * is queued for delivery (non-blocking). Subscribers using this mode must return quickly to avoid blocking the main thread.
     * <p>
     * If not on Android, behaves the same as {@link #POSTING}.
     */
    // 在Android上，订阅者将在Android的主线程（UI线程）中被调用。
    // 如果提交线程是主线程，则将直接调用订阅者方法，从而阻塞发布线程。
    // 否则，事件将排队等待传递（非阻塞）。
    // 使用此模式的订阅者必须快速返回以避免阻塞主线程。
    // 如果不是在Android上，则行为与POSTING相同。
    MAIN,

    /**
     * On Android, subscriber will be called in Android's main thread (UI thread). Different from {@link #MAIN},
     * the event will always be queued for delivery. This ensures that the post call is non-blocking.
     * <p>
     * If not on Android, behaves the same as {@link #POSTING}.
     */
    // 在Android上，订阅者将在Android的主线程（UI线程）中被调用。与MAIN不同的是，事件总是排队等待传递。这确保了post调用是非阻塞的。
    MAIN_ORDERED,

    /**
     * On Android, subscriber will be called in a background thread. If posting thread is not the main thread, subscriber methods
     * will be called directly in the posting thread. If the posting thread is the main thread, EventBus uses a single
     * background thread, that will deliver all its events sequentially. Subscribers using this mode should try to
     * return quickly to avoid blocking the background thread.
     * <p>
     * If not on Android, always uses a background thread.
     */
    // 在Android上，订阅者将在后台线程中被调用。
    // 如果发布线程不是主线程，则将在发布线程中直接调用订阅者方法。
    // 如果发布线程是主线程，则EventBus使用一个单独的后台线程，它将按顺序传递其所有事件。
    // 使用此模式的订阅者应该尝试快速返回，以避免阻塞后台线程。
    // 如果不在Android上，则始终使用后台线程。
    BACKGROUND,

    /**
     * Subscriber will be called in a separate thread. This is always independent of the posting thread and the
     * main thread. Posting events never wait for subscriber methods using this mode. Subscriber methods should
     * use this mode if their execution might take some time, e.g. for network access. Avoid triggering a large number
     * of long-running asynchronous subscriber methods at the same time to limit the number of concurrent threads. EventBus
     * uses a thread pool to efficiently reuse threads from completed asynchronous subscriber notifications.
     */
    // 订阅者将在单独的线程中调用。这始终独立于发布线程和主线程。使用此模式发布事件从不等待订阅者方法。
    // 如果订阅者方法的执行可能需要一些时间（例如，用于网络访问），则应使用此模式。
    // 避免同时触发大量长时间运行的异步订阅者方法以限制并发线程的数量。
    // EventBus使用线程池高效地重用已完成异步订阅者通知中的线程。
    ASYNC
}