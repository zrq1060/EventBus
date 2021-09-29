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

import java.util.logging.Level;

/**
 * Posts events in background.
 *
 * @author Markus
 */
final class BackgroundPoster implements Runnable, Poster {

    private final PendingPostQueue queue;
    private final EventBus eventBus;

    private volatile boolean executorRunning;

    BackgroundPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        // 初始化队列
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        // 封装PendingPost对象
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            // 将PendingPost对象加入到队列中
            queue.enqueue(pendingPost);
            if (!executorRunning) {
                executorRunning = true;
                // 这里使用到之前初始化的线程池
                eventBus.getExecutorService().execute(this);
            }
        }
    }

    // 线程池的执行回调
    @Override
    public void run() {
        // 实现同HandlerPoster
        try {
            try {
                // 无限循环
                while (true) {
                    //　获取队列中的PendingPost，进行双重检查，如果为null直接返回，结束循环
                    PendingPost pendingPost = queue.poll(1000);
                    if (pendingPost == null) {
                        synchronized (this) {
                            // Check again, this time in synchronized
                            pendingPost = queue.poll();
                            if (pendingPost == null) {
                                // 一直循环到queue结束，才返回
                                executorRunning = false;
                                return;
                            }
                        }
                    }
                    //使用反射的方式调用订阅者的订阅方法
                    eventBus.invokeSubscriber(pendingPost);
                }
            } catch (InterruptedException e) {
                eventBus.getLogger().log(Level.WARNING, Thread.currentThread().getName() + " was interruppted", e);
            }
        } finally {
            executorRunning = false;
        }
    }

}
