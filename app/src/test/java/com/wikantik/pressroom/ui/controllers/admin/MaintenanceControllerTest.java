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
package com.wikantik.pressroom.ui.controllers.admin;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryStatus;
import com.wikantik.pressroom.business.discovery.DiscoveryStatus.State;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MaintenanceController}, the Global Admin screen for
 * per-weblog maintenance operations (flush the page cache, rebuild the search
 * index, regenerate media renditions).
 *
 * <p>Each operation is destructive or expensive enough that the admin only
 * reaches for it occasionally, so a silent failure that still shows a success
 * message would go unnoticed for a long time. The interesting behaviour is
 * therefore just that: success is only reported when the manager call
 * actually succeeded, and a failure short-circuits before the confirmation.
 *
 * <p>Unlike the editor screen this replaced, there is no action-context
 * weblog here -- {@link MaintenanceController#isWeblogRequired()} is false --
 * so every action resolves its target weblog by id from an explicit {@code
 * weblogId} parameter, and an unknown or missing id is a page error rather
 * than an exception.
 */
class MaintenanceControllerTest {

    private MockWeblogger weblogger;
    private MaintenanceController controller;
    private ExtendedModelMap model;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new MaintenanceController());
        model = new ExtendedModelMap();

        weblog = new Weblog();
        weblog.setId("weblog-1");
        weblog.setHandle("testblog");
        weblog.setName("Test Blog");
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --- metadata ---

    @Test
    void theScreenIsAdminOnlyAndNeedsNoActionContextWeblog() {
        // PressroomHandlerInterceptor enforces this before the handler runs; it is
        // what keeps operator-only actions (flush cache, rebuild the search
        // index) away from ordinary bloggers now that the screen has moved to
        // Global Admin. isWeblogRequired() is false because there is no
        // /pressroom-ui/admin/** action-context weblog -- the target is chosen
        // explicitly on the page instead.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("maintenance", controller.getActionName());
        assertEquals("maintenance.title", controller.getPageTitle());
    }

    // --- display ---

    @Test
    void executeListsEveryWeblogWithNothingSelectedByDefault() throws Exception {
        when(weblogger.weblogManager().getWeblogs(true, null, null, null, 0, -1))
                .thenReturn(List.of(weblog));

        String view = controller.execute(request(), model, null);

        assertEquals(".Maintenance", view);
        assertEquals(List.of(weblog), model.getAttribute("weblogs"));
        assertNull(model.getAttribute("selectedWeblog"));
        assertTrue(ControllerTestFixture.errors(model).isEmpty(),
                "loading the page before any weblog is chosen must not itself be an error");
    }

    @Test
    void executeSelectsTheNamedWeblog() throws Exception {
        when(weblogger.weblogManager().getWeblogs(true, null, null, null, 0, -1))
                .thenReturn(List.of(weblog));
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);

        String view = controller.execute(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        assertSame(weblog, model.getAttribute("selectedWeblog"));
        assertTrue(ControllerTestFixture.errors(model).isEmpty());
    }

    @Test
    void executeReportsAnUnknownWeblogIdAsAPageErrorRatherThanThrowing() throws Exception {
        when(weblogger.weblogManager().getWeblogs(true, null, null, null, 0, -1))
                .thenReturn(List.of(weblog));
        when(weblogger.weblogManager().getWeblog("no-such-id")).thenReturn(null);

        String view = controller.execute(request(), model, "no-such-id");

        assertEquals(".Maintenance", view);
        assertNull(model.getAttribute("selectedWeblog"));
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error, got: " + ControllerTestFixture.errors(model));
    }

    @Test
    void aFailedWeblogListLookupYieldsAnEmptyPickerRatherThanAnException() throws Exception {
        // loadWeblogs() backs the page's <select>; without the catch, a
        // lookup failure here would take down the whole Maintenance screen
        // instead of just leaving the picker empty -- an operator trying to
        // flush a cache or rebuild an index could not even reach the buttons.
        when(weblogger.weblogManager().getWeblogs(true, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request(), model, null);

        assertEquals(".Maintenance", view);
        assertEquals(List.of(), model.getAttribute("weblogs"),
                "A failed weblog-list lookup must degrade to an empty picker, not a null or an exception");
    }

    // --- flush cache ---

    @Test
    void flushingTheCacheSavesTheWeblogAndConfirms() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);

        String view = controller.flushCache(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        verify(weblogger.weblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0, "A cache flush must be committed");
        assertTrue(ControllerTestFixture.messages(model).contains("maintenance.message.flushed"),
                "Expected a flush confirmation, got: " + ControllerTestFixture.messages(model));
    }

    @Test
    void aFailedCacheFlushIsReportedRatherThanConfirmed() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.weblogManager()).saveWeblog(any());

        String view = controller.flushCache(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("generic.error.check.logs"),
                "Expected a flush error, got: " + ControllerTestFixture.errors(model));
        assertTrue(ControllerTestFixture.messages(model).isEmpty(),
                "A failed flush must not also report success");
    }

    @Test
    void flushingTheCacheForAnUnknownWeblogIsAPageErrorNotAnException() throws Exception {
        when(weblogger.weblogManager().getWeblog("no-such-id")).thenReturn(null);

        String view = controller.flushCache(request(), model, "no-such-id");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error, got: " + ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aFailedWeblogLookupByIdIsAPageErrorRatherThanAnException() throws Exception {
        // resolveWeblog() is shared by every action handler. Without the
        // catch, a lookup failure for an id that DOES exist (unlike the
        // "unknown id" cases above, which get null back from a lookup that
        // succeeds) would propagate out of the controller as an unhandled
        // exception instead of the same noSuchWeblog page error.
        when(weblogger.weblogManager().getWeblog("weblog-1"))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.flushCache(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "A lookup failure must be reported the same way an unknown id is, not thrown: "
                        + ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    // --- rebuild search index ---

    @Test
    void rebuildingTheIndexConfirms() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);

        String view = controller.index(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        verify(weblogger.indexManager()).rebuildWeblogIndex(weblog);
        assertTrue(ControllerTestFixture.messages(model).contains("maintenance.message.indexed"),
                "Expected an index confirmation, got: " + ControllerTestFixture.messages(model));
    }

    @Test
    void aFailedIndexRebuildIsReported() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);
        doThrow(new WebloggerException("index corrupt"))
                .when(weblogger.indexManager()).rebuildWeblogIndex(any());

        String view = controller.index(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.message.indexed.failure"),
                "Expected an index-failure error, got: " + ControllerTestFixture.errors(model));
        assertTrue(ControllerTestFixture.messages(model).isEmpty(),
                "A failed rebuild must not also report success");
    }

    @Test
    void rebuildingTheIndexForAnUnknownWeblogIsAPageErrorNotAnException() throws Exception {
        when(weblogger.weblogManager().getWeblog("no-such-id")).thenReturn(null);

        String view = controller.index(request(), model, "no-such-id");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error, got: " + ControllerTestFixture.errors(model));
        verify(weblogger.indexManager(), never()).rebuildWeblogIndex(any());
    }

    // --- regenerate renditions ---

    @Test
    void regeneratingRenditionsConfirmsWithTheCount() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);
        when(weblogger.mediaFileManager().regenerateRenditions(weblog)).thenReturn(3);

        String view = controller.regenerateRenditions(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        verify(weblogger.mediaFileManager()).regenerateRenditions(weblog);
        assertTrue(ControllerTestFixture.messages(model).contains(
                        "maintenance.message.renditionsRegenerated[3]"),
                "Expected the regenerated count in the confirmation, got: "
                        + ControllerTestFixture.messages(model));
    }

    @Test
    void aFailedRenditionRegenerationIsReported() throws Exception {
        when(weblogger.weblogManager().getWeblog("weblog-1")).thenReturn(weblog);
        doThrow(new WebloggerException("disk full"))
                .when(weblogger.mediaFileManager()).regenerateRenditions(any());

        String view = controller.regenerateRenditions(request(), model, "weblog-1");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains(
                        "maintenance.message.renditionsRegenerated.failure"),
                "Expected a rendition-regeneration failure, got: "
                        + ControllerTestFixture.errors(model));
        assertTrue(ControllerTestFixture.messages(model).isEmpty(),
                "A failed regeneration must not also report success");
    }

    @Test
    void regeneratingRenditionsForAnUnknownWeblogIsAPageErrorNotAnException() throws Exception {
        when(weblogger.weblogManager().getWeblog("no-such-id")).thenReturn(null);

        String view = controller.regenerateRenditions(request(), model, "no-such-id");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error, got: " + ControllerTestFixture.errors(model));
        verify(weblogger.mediaFileManager(), never()).regenerateRenditions(any());
    }

    @Test
    void aBlankWeblogIdIsTreatedTheSameAsAnUnknownOne() throws Exception {
        String view = controller.flushCache(request(), model, "");

        assertEquals(".Maintenance", view);
        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error, got: " + ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    // --- front page discovery ---

    private static DiscoveryStatus status(State state, String error) {
        return new DiscoveryStatus(state, Instant.parse("2026-11-01T10:00:00Z"),
                Instant.parse("2026-11-01T09:00:00Z"), 3, 40, error);
    }

    @Test
    void getExposesDiscoveryStatus() {
        DiscoveryStatus status = status(State.OK, null);
        when(weblogger.discoveryManager().getStatus()).thenReturn(status);

        controller.execute(request(), model, null);

        assertSame(status, model.getAttribute("discoveryStatus"));
        assertEquals(java.util.Date.from(status.lastAttempt()), model.getAttribute("discoveryLastAttempt"));
        assertEquals(java.util.Date.from(status.lastSuccess()), model.getAttribute("discoveryLastSuccess"));
    }

    @Test
    void refreshNowCallsRefreshAndReportsOk() {
        when(weblogger.discoveryManager().getStatus()).thenReturn(status(State.OK, null));

        String view = controller.refreshDiscovery(request(), model);

        assertEquals(".Maintenance", view);
        verify(weblogger.discoveryManager()).refresh();
        assertTrue(ControllerTestFixture.messages(model).contains("maintenance.discovery.refreshed"),
                String.valueOf(ControllerTestFixture.messages(model)));
        assertTrue(ControllerTestFixture.errors(model).isEmpty());
        assertEquals(State.OK, ((DiscoveryStatus) model.getAttribute("discoveryStatus")).state());
    }

    @Test
    void refreshNowReportsFailureMessage() {
        when(weblogger.discoveryManager().getStatus())
                .thenReturn(status(State.FAILED, "<b>timeout</b> & \"more\""));

        controller.refreshDiscovery(request(), model);

        String error = ControllerTestFixture.errors(model).toString();
        assertTrue(error.contains("maintenance.discovery.refreshFailed"), error);
        assertTrue(error.contains("&lt;b&gt;timeout&lt;/b&gt; &amp; &quot;more&quot;"), error);
        assertFalse(error.contains("<b>"), error);
        assertTrue(ControllerTestFixture.messages(model).isEmpty());
    }

    @Test
    void disabledStateExplainsUmamiNotConfigured() {
        when(weblogger.discoveryManager().getStatus()).thenReturn(
                new DiscoveryStatus(State.DISABLED, null, null, 0, 0, null));

        controller.refreshDiscovery(request(), model);

        assertTrue(ControllerTestFixture.errors(model).contains("maintenance.discovery.state.DISABLED"),
                String.valueOf(ControllerTestFixture.errors(model)));
        assertTrue(ControllerTestFixture.messages(model).isEmpty());
    }

    // --- helpers ---

    private HttpServletRequest request() {
        return ControllerTestFixture.requestFor(new User());
    }
}
