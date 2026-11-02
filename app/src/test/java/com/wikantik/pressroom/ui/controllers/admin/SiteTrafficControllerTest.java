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
package com.wikantik.pressroom.ui.controllers.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;
import com.wikantik.pressroom.business.analytics.SiteTraffic;
import com.wikantik.pressroom.business.analytics.SiteTraffic.BlogRow;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SiteTrafficController}: the global admin's Traffic page,
 * read from the local snapshots only.
 */
class SiteTrafficControllerTest {

    private static final Instant AS_OF = Instant.parse("2026-11-01T19:05:00Z");

    private MockWeblogger weblogger;
    private SiteTrafficController controller;
    private AnalyticsManager analytics;
    private ExtendedModelMap model;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new SiteTrafficController());
        controller.setClock(Clock.fixed(Instant.parse("2026-11-01T19:35:00Z"), ZoneOffset.UTC));
        model = new ExtendedModelMap();
        analytics = weblogger.analyticsManager();
        request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(java.util.Locale.US);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static SiteTraffic site(TrafficState state, List<BlogRow> blogs, long views, long visits,
                                    List<PostRow> posts, List<KeyCount> sources) {
        return new SiteTraffic(state, AS_OF, blogs, views, visits, posts, sources);
    }

    private static BlogRow blog(String id, String handle, String name, long visitors, long views, double change) {
        return new BlogRow(id, handle, name, visitors, views, views / 2, change, "Top &amp; best");
    }

    private SiteTrafficView view(String period) {
        controller.execute(request, model, period);
        return (SiteTrafficView) model.getAttribute("traffic");
    }

    @Test
    void globalAdminOnly() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("siteTraffic", controller.getActionName());
        assertEquals("siteTraffic.title", controller.getPageTitle());
    }

    @Test
    void listsEveryBlogWithTotalsForViewsAndVisitsOnly() {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK,
                List.of(blog("w1", "alpha", "Alpha <b>", 1200, 3000, 0.25),
                        blog("w2", "beta", "Beta", 800, 1500, Double.NaN)),
                4500, 6000, List.of(), List.of()));

        assertEquals(".SiteTraffic", controller.execute(request, model, "30d"));
        SiteTrafficView v = (SiteTrafficView) model.getAttribute("traffic");

        assertEquals("4,500", v.totalViews());
        assertEquals("6,000", v.totalVisits());
        assertEquals(2, v.blogs().size());
        SiteTrafficView.BlogLine first = v.blogs().get(0);
        assertEquals("alpha", first.handle());
        assertEquals("Alpha <b>", first.name(), "raw; the JSP escapes it");
        assertEquals("1,200", first.visitors());
        assertEquals("3,000", first.views());
        assertEquals("1,500", first.visits());
        assertEquals("+25%", first.change());
        assertEquals("up", first.changeClass());
        assertEquals("Top &amp; best", first.topPostTitle(), "stored escaped; emitted bare");
        assertEquals("—", v.blogs().get(1).change(), "NaN is an em dash");
        assertEquals("", v.blogs().get(1).changeClass());
        assertTrue(v.showBody());
        assertEquals("", v.noticeKey());
    }

    @Test
    void theSiteSpaceIsLabelledPlainlyNotByItsHandle() {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK,
                List.of(blog("w0", Weblog.SITE_SPACE_HANDLE, "Whatever", 5, 9, 0.0)), 9, 5, List.of(), List.of()));

        SiteTrafficView.BlogLine line = view("30d").blogs().get(0);

        assertTrue(line.siteSpace());
        assertEquals("0%", line.change());
    }

    @Test
    void theAsOfTimeIsShownInTheSiteSpacesZone() throws Exception {
        Weblog siteSpace = new Weblog();
        siteSpace.setHandle(Weblog.SITE_SPACE_HANDLE);
        siteSpace.setTimeZone("America/Los_Angeles");
        when(weblogger.weblogManager().getWeblogByHandle(Weblog.SITE_SPACE_HANDLE)).thenReturn(siteSpace);
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK,
                List.of(blog("w1", "a", "A", 1, 1, 0.0)), 1, 1, List.of(), List.of()));

        assertEquals("11:05", view("30d").asOfText());
    }

    @Test
    void periodDefaultsAndUnknownKeysFallBackToThirtyDays() {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK, List.of(), 0, 0, List.of(), List.of()));
        when(analytics.getSiteTraffic("7d")).thenReturn(site(TrafficState.OK, List.of(), 0, 0, List.of(), List.of()));

        assertEquals("30d", view("bogus").period());
        assertEquals("30d", view("45d").period(), "the discovery window is read but not offered");
        assertEquals("7d", view("7d").period());
        assertEquals(List.of("24h", "7d", "30d", "90d"), view("7d").periods());
    }

    @Test
    void topPostsMergeAnEntryUnderTwoSpellingsViewsSummedVisitorsMax() {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK,
                List.of(blog("w1", "a", "A", 1, 1, 0.0)), 0, 0,
                List.of(new PostRow("e1", "One", "/a/one", 10, 7, 0.1),
                        new PostRow("e2", "Two", "/a/two", 12, 3, 0.1),
                        new PostRow("e1", "One", "/a/one/", 5, 9, 0.1)),
                List.of(new KeyCount("channel:search", 40))));

        SiteTrafficView v = view("30d");

        assertEquals(2, v.posts().size());
        assertEquals("e1", v.posts().get(0).entryId());
        assertEquals(15, v.posts().get(0).views());
        assertEquals(9, v.posts().get(0).visitors());
        assertEquals("search", v.sources().get(0).label());
        assertEquals("40", v.sources().get(0).value());
    }

    @Test
    void statesChooseTheNoticeAndHideTheBodyWithNoSnapshot() {
        when(analytics.getSiteTraffic("30d")).thenReturn(
                new SiteTraffic(TrafficState.NOT_CONFIGURED, null, List.of(), 0, 0, List.of(), List.of()));
        SiteTrafficView off = view("30d");
        assertEquals("traffic.state.notConfigured", off.noticeKey());
        assertFalse(off.showBody());

        when(analytics.getSiteTraffic("30d")).thenReturn(
                new SiteTraffic(TrafficState.NO_DATA, null, List.of(), 0, 0, List.of(), List.of()));
        assertEquals("traffic.state.noData", view("30d").noticeKey());
        assertFalse(view("30d").showBody());

        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.STALE,
                List.of(blog("w1", "a", "A", 1, 1, 0.0)), 1, 1, List.of(), List.of()));
        SiteTrafficView stale = view("30d");
        assertEquals("traffic.state.stale", stale.noticeKey());
        assertEquals(30, stale.ageMinutes());
        assertEquals("19:05", stale.asOfText());
        assertTrue(stale.showBody(), "stale keeps its old numbers");

        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK,
                List.of(blog("w1", "a", "A", 0, 0, Double.NaN)), 0, 0, List.of(), List.of()));
        assertEquals("traffic.state.siteZero", view("30d").noticeKey());
    }

    @Test
    void neitherPageCallsAnalyticsServiceOnlyTheLocalSnapshot() {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK, List.of(), 0, 0, List.of(), List.of()));

        view("30d");

        verify(analytics).getSiteTraffic("30d");
        verify(analytics, never()).sync();
        verify(analytics, never()).requestSync();
        verify(analytics, never()).reconcile();
    }
}
