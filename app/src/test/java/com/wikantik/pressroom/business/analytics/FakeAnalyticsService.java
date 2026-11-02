/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.analytics;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An analytics service held in memory: websites by ID, every call counted.
 * It can be told to be unconfigured, to be down (ping false, ensureSite
 * throws), to refuse chosen websites while answering everything else, or to
 * hang until released.
 */
final class FakeAnalyticsService implements AnalyticsService {

    volatile boolean configured = true;
    volatile boolean down;
    /** While set, ping and ensureSite wait for it (bounded) before answering. */
    volatile CountDownLatch hang;

    /** Website IDs whose ensureSite fails while the service otherwise answers. */
    final Set<String> failIds = ConcurrentHashMap.newKeySet();
    final Map<String, SiteSpec> sites = new ConcurrentHashMap<>();
    final List<SiteSpec> ensured = new CopyOnWriteArrayList<>();
    final List<Thread> callers = new CopyOnWriteArrayList<>();
    final AtomicInteger pings = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String ensureSite(SiteSpec spec) throws AnalyticsException {
        called();
        ensured.add(spec);
        if (down) {
            throw new AnalyticsException("Umami answered HTTP 502 looking up the website");
        }
        if (failIds.contains(spec.websiteId())) {
            throw new AnalyticsException("Umami answered HTTP 400 creating the website");
        }
        sites.put(spec.websiteId(), spec);
        return spec.websiteId();
    }

    @Override
    public AnalyticsReport read(String websiteId, Period period, ZoneId zone) throws AnalyticsException {
        called();
        throw new AnalyticsException("not faked");
    }

    @Override
    public boolean ping() {
        pings.incrementAndGet();
        called();
        return !down;
    }

    /** Lets every hung call go and stops hanging. */
    void release() {
        CountDownLatch h = hang;
        hang = null;
        if (h != null) {
            h.countDown();
        }
    }

    private void called() {
        calls.incrementAndGet();
        callers.add(Thread.currentThread());
        CountDownLatch h = hang;
        if (h != null) {
            try {
                h.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
