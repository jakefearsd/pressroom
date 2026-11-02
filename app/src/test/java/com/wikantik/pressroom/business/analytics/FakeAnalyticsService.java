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

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * It can be told to be unconfigured, to be down (ping false, ensureSite and
 * read throw), to refuse chosen websites while answering everything else, or
 * to hang until released. A read answers the report set for that website, or
 * an empty one; {@link #maxInFlight} records how many calls ever overlapped.
 */
final class FakeAnalyticsService implements AnalyticsService {

    volatile boolean configured = true;
    volatile boolean down;
    /** While set, ping and ensureSite wait for it (bounded) before answering. */
    volatile CountDownLatch hang;

    /** Website IDs whose ensureSite fails while the service otherwise answers. */
    final Set<String> failIds = ConcurrentHashMap.newKeySet();
    /** Website IDs whose ensureSite throws an unexpected RuntimeException, whose message names a secret. */
    final Set<String> crashIds = ConcurrentHashMap.newKeySet();
    /** What a crash's message carries; it must never reach a page. */
    static final String CRASH_SECRET = "password=hunter2";
    final Map<String, SiteSpec> sites = new ConcurrentHashMap<>();
    final List<SiteSpec> ensured = new CopyOnWriteArrayList<>();
    final List<Thread> callers = new CopyOnWriteArrayList<>();
    final AtomicInteger pings = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    /** Website IDs whose read fails while the service otherwise answers. */
    final Set<String> readFailIds = ConcurrentHashMap.newKeySet();
    /**
     * Website IDs Umami no longer has: a read answers as a 401 after a fresh
     * login does ({@link WebsiteMissingException}), and ensureSite creates the
     * website again, which takes it off this set.
     */
    final Set<String> missingIds = ConcurrentHashMap.newKeySet();
    /** What a read of a website answers, for every period; absent means an empty report. */
    final Map<String, AnalyticsReport> reports = new ConcurrentHashMap<>();
    /** Every read made, in order. */
    final List<Read> reads = new CopyOnWriteArrayList<>();
    final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();

    /** One read: which website, which period, in which zone. */
    record Read(String websiteId, String period, ZoneId zone) {
    }

    /** A report with these headline numbers and nothing else. */
    static AnalyticsReport report(long visitors, long visits, long views) {
        return new AnalyticsReport(Instant.now(), new AnalyticsReport.Summary(visitors, visits, views, 0, 0),
                new AnalyticsReport.Summary(0, 0, 0, 0, 0), List.of(), List.of(), emptyBreakdowns());
    }

    /** Every breakdown key, each empty. */
    static Map<String, List<AnalyticsReport.KeyCount>> emptyBreakdowns() {
        Map<String, List<AnalyticsReport.KeyCount>> breakdowns = new LinkedHashMap<>();
        for (String key : List.of("sources", "places", "devices", "events", "campaigns", "speed")) {
            breakdowns.put(key, List.of());
        }
        return breakdowns;
    }

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
        if (crashIds.contains(spec.websiteId())) {
            throw new IllegalStateException("connection refused, " + CRASH_SECRET);
        }
        if (failIds.contains(spec.websiteId())) {
            throw new AnalyticsException("Umami answered HTTP 400 creating the website");
        }
        sites.put(spec.websiteId(), spec);
        missingIds.remove(spec.websiteId());
        return spec.websiteId();
    }

    @Override
    public AnalyticsReport read(String websiteId, Period period, ZoneId zone) throws AnalyticsException {
        called();
        reads.add(new Read(websiteId, period.key(), zone));
        if (down) {
            throw new AnalyticsException("Umami answered HTTP 502 while reading stats");
        }
        if (missingIds.contains(websiteId)) {
            throw new WebsiteMissingException("Umami answered HTTP 401 while reading stats");
        }
        if (readFailIds.contains(websiteId)) {
            throw new AnalyticsException("Umami answered HTTP 503 while reading stats");
        }
        return reports.getOrDefault(websiteId, report(0, 0, 0));
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
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            CountDownLatch h = hang;
            if (h != null) {
                h.await(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
