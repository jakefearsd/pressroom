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

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.admin.MaintenanceController;
import com.wikantik.pressroom.ui.controllers.admin.SiteTrafficController;
import com.wikantik.pressroom.ui.controllers.editor.EntriesBean;
import com.wikantik.pressroom.ui.controllers.editor.EntriesController;
import com.wikantik.pressroom.ui.controllers.editor.TrafficController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC 8 for the admin pages: no admin request reaches Umami. The REAL analytics
 * manager, with everything registered and read once through a fake service, is
 * what each controller asks; the service is then told to hang (every call
 * waits up to 30 s) and each page is served. Each must come back in normal
 * time, and the request's own thread must never have called the service.
 *
 * <p>Sync now and a blog save do start a sync, on the manager's background
 * pool: that sync may call the hanging service, which is the point (the
 * caller does not wait for it). So the check is the calling thread, not the
 * call count. The companion for page renders is
 * {@link RenderingNeverCallsAnalyticsServiceTest}.
 */
class AdminPagesNeverCallAnalyticsServiceTest {

    private static final Duration NORMAL = Duration.ofSeconds(5);
    /** A request that waits on the hanging service is abandoned (its thread interrupted) after this. */
    private static final Duration HARD_LIMIT = Duration.ofSeconds(10);

    private AnalyticsTier tier;
    private User user;
    private Weblog blog;

    /** One admin request (or the save behind one). */
    enum Page {
        BLOG_TRAFFIC {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) {
                assertEquals(".Traffic", t.wire(new TrafficController())
                        .execute(request, new ExtendedModelMap(), "30d", "posts", "views"));
            }
        },
        GLOBAL_TRAFFIC {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) {
                assertEquals(".SiteTraffic", t.wire(new SiteTrafficController())
                        .execute(request, new ExtendedModelMap(), "30d"));
            }
        },
        ENTRIES_WITH_VIEWS {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) {
                ExtendedModelMap model = new ExtendedModelMap();
                t.wire(new EntriesController()).execute(request, model, new EntriesBean());
                assertEquals(Boolean.TRUE, model.getAttribute("viewsColumn"), "the Views column is computed");
            }
        },
        MAINTENANCE {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) {
                ExtendedModelMap model = new ExtendedModelMap();
                assertEquals(".Maintenance", t.wire(new MaintenanceController()).execute(request, model, null));
                assertTrue(model.containsAttribute("analyticsStatus"));
            }
        },
        MAINTENANCE_SYNC_NOW {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) {
                int scheduled = t.tier.background.submitted.get();
                assertEquals(".Maintenance", t.wire(new MaintenanceController())
                        .syncAnalytics(request, new ExtendedModelMap()));
                assertEquals(scheduled + 1, t.tier.background.submitted.get(), "a sync was handed to the pool");
            }
        },
        WEBLOG_SAVE {
            @Override
            void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request) throws Exception {
                int scheduled = t.tier.background.submitted.get();
                Weblog renamed = t.tier.weblogs.getWeblog(t.blog.getId());
                renamed.setName("Renamed while Umami hangs");
                t.tier.weblogs.saveWeblog(renamed);
                t.tier.weblogger.flush();
                assertEquals(scheduled + 1, t.tier.background.submitted.get(),
                        "the rename asked for a sync (weblogSaved)");
            }
        };

        abstract void serve(AdminPagesNeverCallAnalyticsServiceTest t, MockHttpServletRequest request)
                throws Exception;
    }

    @BeforeEach
    void setUp() throws Exception {
        tier = new AnalyticsTier();
        user = TestUtils.setupUser("adminneverumami");
        TestUtils.weblogger().getUserManager().grantRole("admin", user);
        blog = TestUtils.setupWeblog("adminneverumamiblog", user);
        TestUtils.setupWeblogEntry("hang-proof", TestUtils.getManagedWebsite(blog), user);
        TestUtils.endSession(true);
        // register and read everything through the fake service, then hang it
        tier.manager.sync();
        TestUtils.endSession(false);
        assertTrue(tier.manager.isRegistered(blog.getId()), "the blog is registered");
        tier.service.hang = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        tier.tearDown();
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @ParameterizedTest
    @EnumSource(Page.class)
    void anAdminPageNeverWaitsOnTheService(Page page) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pressroom-ui/x.rol");
        request.setAttribute("authenticatedUser", TestUtils.weblogger().getUserManager()
                .getUserByUserName(user.getUserName()));
        request.setAttribute("actionWeblog", TestUtils.weblogger().getWeblogManager().getWeblog(blog.getId()));
        AtomicReference<Thread> served = new AtomicReference<>();

        long start = System.nanoTime();
        assertTimeoutPreemptively(HARD_LIMIT, () -> {
            served.set(Thread.currentThread());
            page.serve(this, request);
        }, page + " waited on the hanging Umami service");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(took.compareTo(NORMAL) < 0, page + " took " + took + " with Umami hanging");
        assertFalse(tier.service.callers.contains(served.get()), page + " called the service on its own thread");
    }

    /** The controller as production wires it: this tier, and messages that read as their codes. */
    private <T extends BaseController> T wire(T controller) {
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        set(controller, "weblogger", tier.weblogger);
        set(controller, "messageSource", messages);
        return controller;
    }

    private static void set(BaseController controller, String name, Object value) {
        try {
            Field field = BaseController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(controller, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
