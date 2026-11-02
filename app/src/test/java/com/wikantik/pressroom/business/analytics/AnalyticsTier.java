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

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.business.jpa.JPAWeblogManagerImpl;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The suite's business tier, with an analytics manager on a fake service
 * wired in where the tier's own (unconfigured) one sits: the weblog manager
 * built here calls this manager from its save hook, and the manager saves
 * through that weblog manager, so a test sees the whole loop. Everything
 * else delegates to {@link TestUtils#weblogger()}.
 */
final class AnalyticsTier {

    static final String SITE_URL = "https://blogs.example.com";

    final FakeAnalyticsService service = new FakeAnalyticsService();
    final TrackingExecutor background = new TrackingExecutor();
    final Weblogger weblogger;
    final WeblogManager weblogs;
    final JPAAnalyticsManagerImpl manager;
    final JPAPersistenceStrategy strategy;
    volatile String siteUrl = SITE_URL;
    /** The discovery window the manager reads; the default is the setting's own. */
    volatile int windowDays = 30;
    private final List<String> created = new ArrayList<>();

    AnalyticsTier() throws Exception {
        TestUtils.setupWeblogger();
        Weblogger real = TestUtils.weblogger();
        strategy = strategyOf(real.getWeblogManager());
        Weblogger wrapped = mock(Weblogger.class, delegatesTo(real));
        weblogs = new JPAWeblogManagerImpl(wrapped, strategy, real.getVirtualHostRegistry());
        manager = new JPAAnalyticsManagerImpl(wrapped, strategy, service, background, Clock.systemUTC(),
                () -> siteUrl, () -> windowDays);
        doReturn(weblogs).when(wrapped).getWeblogManager();
        doReturn(manager).when(wrapped).getAnalyticsManager();
        weblogger = wrapped;
        clearRegistrations();
    }

    /** Creates and commits a weblog through this tier's weblog manager, so its hook fires. */
    Weblog addWeblog(String handle, User creator) throws Exception {
        Weblog weblog = newWeblog(handle, creator);
        weblogs.addWeblog(weblog);
        weblogger.flush();
        created.add(weblog.getId());
        return weblog;
    }

    /** Creates and commits a weblog through the suite's own tier, whose hook reaches only its own manager. */
    Weblog addWeblogUnseen(String handle, User creator) throws Exception {
        Weblog weblog = TestUtils.setupWeblog(handle, creator);
        TestUtils.endSession(true);
        created.add(weblog.getId());
        return weblog;
    }

    static Weblog newWeblog(String handle, User creator) {
        Weblog weblog = new Weblog();
        weblog.setName("Test Weblog " + handle);
        weblog.setTagline("Test Weblog");
        weblog.setHandle(handle);
        weblog.setEmailAddress("testweblog@dev.null");
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/Los_Angeles");
        weblog.setDateCreated(new Date());
        weblog.setCreatorUserName(creator.getUserName());
        return weblog;
    }

    /** Waits (bounded) for every background run this tier's manager scheduled to finish. */
    void awaitQuiet() throws InterruptedException {
        background.awaitQuiet(Duration.ofSeconds(20));
    }

    /** The weblog as committed, read in a fresh unit of work. */
    Weblog committed(String id) throws Exception {
        TestUtils.endSession(false);
        return TestUtils.weblogger().getWeblogManager().getWeblog(id);
    }

    /** Lets hung calls go, waits for the background, and removes what the test made. */
    void tearDown() throws Exception {
        service.release();
        awaitQuiet();
        TestUtils.endSession(false);
        for (String id : created) {
            if (TestUtils.weblogger().getWeblogManager().getWeblog(id) != null) {
                TestUtils.teardownWeblog(id);
            }
        }
        TestUtils.endSession(true);
        clearRegistrations();
        background.shutdown();
    }

    private void clearRegistrations() throws Exception {
        for (String entity : List.of("AnalyticsSummary", "AnalyticsPathViews", "AnalyticsBreakdown", "AnalyticsSite")) {
            strategy.getEntityManager(true).createQuery("DELETE FROM " + entity + " x").executeUpdate();
        }
        strategy.flush();
        strategy.release();
    }

    private static JPAPersistenceStrategy strategyOf(WeblogManager manager) throws ReflectiveOperationException {
        Field f = manager.getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(manager);
    }

    /** Runs each task on a pool thread and counts them, so a test can wait for quiet. */
    static final class TrackingExecutor implements Executor {
        private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "analytics-test-background");
            t.setDaemon(true);
            return t;
        });
        final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        @Override
        public void execute(Runnable task) {
            submitted.incrementAndGet();
            pool.execute(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (this) {
                        finished.incrementAndGet();
                        notifyAll();
                    }
                }
            });
        }

        synchronized void awaitQuiet(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (finished.get() < submitted.get()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("background runs still going after " + timeout);
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }

        void shutdown() {
            pool.shutdownNow();
        }
    }
}
