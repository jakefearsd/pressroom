/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.rendering;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerImpl;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;

import org.springframework.test.util.AopTestUtils;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

/**
 * Puts a stand-in {@link AnalyticsManager} into the suite's business tier, where
 * the unconfigured real one sits, so a rendering test decides which blogs are
 * registered (and under which website id) with no Umami anywhere. The stand-in
 * answers {@code isRegistered}, {@code websiteIdFor} and {@code getStatus},
 * ignores the weblogSaved hook a test's own saves fire, and throws on every
 * other method: it shows a render used no other manager method, but it cannot
 * show the Umami service is never reached (the manager is gone). For that,
 * {@link #installing} puts a real manager on a fake service in its place.
 */
public final class StubAnalytics implements AutoCloseable {

    private final Field field;
    private final Weblogger weblogger;
    private final AnalyticsManager previous;
    private final Map<String, String> ids = new ConcurrentHashMap<>();
    private final AnalyticsManager manager;

    private StubAnalytics(boolean configured, AnalyticsManager real) throws ReflectiveOperationException {
        // the tier may sit behind a Spring proxy: swap the manager on the real object
        weblogger = AopTestUtils.getUltimateTargetObject(TestUtils.weblogger());
        field = WebloggerImpl.class.getDeclaredField("analyticsManager");
        field.setAccessible(true);
        previous = (AnalyticsManager) field.get(weblogger);
        if (real != null) {
            manager = real;
            field.set(weblogger, manager);
            return;
        }
        manager = mock(AnalyticsManager.class, invocation -> {
            throw new AssertionError("a render may only read registrations from memory, but called "
                    + invocation.getMethod().getName());
        });
        doAnswer(a -> ids.containsKey(a.<String>getArgument(0))).when(manager).isRegistered(anyString());
        doAnswer(a -> ids.get(a.<String>getArgument(0))).when(manager).websiteIdFor(anyString());
        doReturn(configured ? new AnalyticsStatus(true, true, 1, 1, null, null, null)
                : AnalyticsStatus.notConfigured()).when(manager).getStatus();
        // a test saves blogs to set them up; that hook only queues background work
        doNothing().when(manager).weblogSaved(any());
        field.set(weblogger, manager);
    }

    /** Installs this (real) manager in the business tier; {@link #register} then has no effect. */
    public static StubAnalytics installing(AnalyticsManager real) throws ReflectiveOperationException {
        return new StubAnalytics(true, real);
    }

    /** A configured stand-in with nothing registered yet. */
    public static StubAnalytics configured() throws ReflectiveOperationException {
        return new StubAnalytics(true, null);
    }

    /** An unconfigured stand-in, like a deployment with no analytics URL. */
    public static StubAnalytics notConfigured() throws ReflectiveOperationException {
        return new StubAnalytics(false, null);
    }

    /** Registers a blog under a website id. */
    public StubAnalytics register(String weblogId, String websiteId) {
        ids.put(weblogId, websiteId);
        return this;
    }

    /** The ids registered. */
    public Set<String> registered() {
        return Set.copyOf(ids.keySet());
    }

    @Override
    public void close() throws ReflectiveOperationException {
        field.set(weblogger, previous);
    }
}
