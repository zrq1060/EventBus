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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

// 这个类继承自Handler并且实现了Poster接口
public class HandlerPoster extends Handler implements Poster {

    private final PendingPostQueue queue;
    private final int maxMillisInsideHandleMessage;
    private final EventBus eventBus;
    private boolean handlerActive;

    protected HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        // 这里传入的是主线程的Looper对象，所以这个Handler对象是主线程的Handler
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        // 创建一个事件队列
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        // 根据传入的参数封装一个PendingPost对象
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            // 将PendingPost加入到队列中
            // 发一个，执行一个，再发送，如果已经正在执行中，则不再立即执行，而是和上次一块执行
            queue.enqueue(pendingPost);
            if (!handlerActive) {
                handlerActive = true;
                // 调用Handler的sendMessage，发送事件回到主线程，最终会调用下面的handleMessage方法
                if (!sendMessage(obtainMessage())) {
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
            long started = SystemClock.uptimeMillis();
            // 无限循环
            while (true) {
                PendingPost pendingPost = queue.poll(); // 获取并移除第一个
                // 进行两次检查，确保pendingPost不为null，如果为null直接跳出循环
                if (pendingPost == null) {
                    synchronized (this) {
                        // Check again, this time in synchronized
                        pendingPost = queue.poll();
                        if (pendingPost == null) {
                            // 一直循环到queue结束，才返回
                            handlerActive = false;
                            return;
                        }
                    }
                }
                // 使用反射的方法调用订阅者的订阅方法。
                eventBus.invokeSubscriber(pendingPost);
                long timeInMethod = SystemClock.uptimeMillis() - started;
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            handlerActive = rescheduled;
        }
    }
}