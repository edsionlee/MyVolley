/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.volley;

import java.util.concurrent.BlockingQueue;

import android.os.Process;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 *
 * Requests added to the specified cache queue are resolved from cache. Any
 * deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}. Cache misses and responses that require refresh are
 * enqueued on the specified network queue for processing by a
 * {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

	private static final boolean DEBUG = VolleyLog.DEBUG;

	/** The queue of requests coming in for triage. */
	private final BlockingQueue<Request<?>> mCacheQueue;

	/** The queue of requests going out to the network. */
	private final BlockingQueue<Request<?>> mNetworkQueue;

	/** The cache to read from. */
	private final Cache mCache;

	/** For posting responses. */
	private final ResponseDelivery mDelivery;

	/** Used for telling us to die. */
	private volatile boolean mQuit = false;

	/**
	 * Creates a new cache triage dispatcher thread. You must call
	 * {@link #start()} in order to begin processing.
	 *
	 * @param cacheQueue
	 *            Queue of incoming requests for triage
	 * @param networkQueue
	 *            Queue to post requests that require network to
	 * @param cache
	 *            Cache interface to use for resolution
	 * @param delivery
	 *            Delivery interface to use for posting responses
	 */
	public CacheDispatcher(BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue, Cache cache,
			ResponseDelivery delivery) {
		mCacheQueue = cacheQueue;
		mNetworkQueue = networkQueue;
		mCache = cache;
		mDelivery = delivery;
	}

	/**
	 * Forces this dispatcher to quit immediately. If any requests are still in
	 * the queue, they are not guaranteed to be processed.
	 */
	public void quit() {
		mQuit = true;
		interrupt();
	}

	@Override
	public void run() {
		if (DEBUG)
			VolleyLog.v("start new dispatcher");
		Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		// 缓存初始化，会遍历整个缓存文件夹
		// Make a blocking call to initialize the cache.
		mCache.initialize();

		while (true) {
			try {
				// Get a request from the cache triage queue, blocking until
				// at least one is available.
				// 获取请求-> 阻塞方法，如果取不出来数据会一直停在这里
				final Request<?> request = mCacheQueue.take();
				request.addMarker("cache-queue-take");
				// 执行代码
				// If the request has been canceled, don't bother dispatching
				// it.

				if (request.isCanceled()) {
					request.finish("cache-discard-canceled");
					continue;
				}

				// Attempt to retrieve this item from cache.
				// 如果缓存为空，就放入网络线程处理
				Cache.Entry entry = mCache.get(request.getCacheKey());
				if (entry == null) {
					request.addMarker("cache-miss");
					// Cache miss; send off to the network dispatcher.
					mNetworkQueue.put(request);
					// continue 终止往后执行下去，重新回到while循环
					continue;
				}

				// If it is completely expired, just send it to the network.
				// 如果过期，也放入网络处理
				if (entry.isExpired()) {
					request.addMarker("cache-hit-expired");
					request.setCacheEntry(entry);
					mNetworkQueue.put(request);
					continue;
				}

				// We have a cache hit; parse its data for delivery back to the
				// request.
				request.addMarker("cache-hit");
				Response<?> response = request
						.parseNetworkResponse(new NetworkResponse(entry.data, entry.responseHeaders));
				request.addMarker("cache-hit-parsed");

				if (!entry.refreshNeeded()) {
					// Completely unexpired cache hit. Just deliver the
					// response.
					// 饶了大半圈，其实就是 执行了request的deliverResponse接口
					mDelivery.postResponse(request, response);
				} else {
					// Soft-expired cache hit. We can deliver the cached
					// response,
					// but we need to also send the request to the network for
					// refreshing.
					request.addMarker("cache-hit-refresh-needed");
					request.setCacheEntry(entry);

					// Mark the response as intermediate.
					response.intermediate = true;

					// Post the intermediate response back to the user and have
					// the delivery then forward the request along to the
					// network.
					// 如果需要刷新,那么就在数据分发的同时,同时把这个任务放到网络请求执行器里里面去
					// 但是不懂结果分发器为什么要重新用一个类去操作
					mDelivery.postResponse(request, response, new Runnable() {
						@Override
						public void run() {
							try {
								mNetworkQueue.put(request);
							} catch (InterruptedException e) {
								// Not much we can do about this.
							}
						}
					});
				}
			} catch (InterruptedException e) {
				// We may have been interrupted because it was time to quit.
				if (mQuit) {
					return;
				}
				continue;
			}
		}
	}
}
