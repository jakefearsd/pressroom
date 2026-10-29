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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SetupController}, the first-run page that picks which
 * weblog appears on the site's front page.
 */
class SetupControllerTest {

    private static final String FRONTPAGE_HANDLE = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED = "site.frontpage.weblog.aggregated";

    private MockWeblogger weblogger;
    private SetupController controller;
    private ExtendedModelMap model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new SetupController());
        model = new ExtendedModelMap();
        redirectAttributes = new RedirectAttributesModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void thePageOffersTheWeblogsToChooseFromAndTheSiteTotals() throws Exception {
        Weblog weblog = new Weblog();
        when(weblogger.weblogManager().getWeblogs(anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(weblog));
        when(weblogger.userManager().getUserCount()).thenReturn(3L);
        when(weblogger.weblogManager().getWeblogCount()).thenReturn(7L);

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model);

        assertEquals(".Setup", view);
        assertEquals(List.of(weblog), model.getAttribute("weblogs"));
        assertEquals(3L, model.getAttribute("userCount"));
        assertEquals(7L, model.getAttribute("blogCount"));
        assertEquals("index.heading", model.getAttribute("pageTitle"));
    }

    @Test
    void theSetupPageIsReachableWithoutAUserOrAWeblog() {
        // It runs on a site that has neither yet.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertEquals("index.heading", controller.getPageTitle());
    }

    @Test
    void thePageReflectsTheCurrentlyStoredFrontpageChoice() throws Exception {
        // Without this, the select and checkbox always render at their
        // defaults regardless of what is actually stored, so any re-save
        // silently reverts the front-page weblog to whatever happened to be
        // first in the <select> (or clears "aggregated").
        property(FRONTPAGE_HANDLE, "travelguide");
        property(FRONTPAGE_AGGREGATED, "true");

        controller.execute(ControllerTestFixture.requestFor(admin()), model);

        assertEquals("travelguide", model.getAttribute("frontpageWeblogHandle"));
        assertEquals(Boolean.TRUE, model.getAttribute("frontpageAggregated"));
    }

    @Test
    void aFailedWeblogLookupIsReportedButThePageStillRenders() throws Exception {
        when(weblogger.weblogManager().getWeblogs(anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model);

        assertEquals(".Setup", view);
        assertEquals(List.of("frontpageConfig.weblogs.error"), ControllerTestFixture.errors(model));
    }

    @Test
    void failedCountsShowAsZeroRatherThanBreakingThePage() throws Exception {
        when(weblogger.userManager().getUserCount()).thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model);

        assertEquals(".Setup", view);
        assertEquals(0L, model.getAttribute("userCount"));
        assertEquals(0L, model.getAttribute("blogCount"));
    }

    @Test
    void choosingAFrontPageWeblogStoresBothSettings() throws Exception {
        RuntimeConfigProperty handle = property(FRONTPAGE_HANDLE, "");
        RuntimeConfigProperty aggregated = property(FRONTPAGE_AGGREGATED, "false");

        String view = controller.save(
                ControllerTestFixture.requestFor(admin()), model, "travelguide", Boolean.TRUE, redirectAttributes);

        assertEquals("redirect:/pressroom-ui/setup.rol", view);
        assertEquals("travelguide", handle.getValue());
        assertEquals("true", aggregated.getValue());
        verify(weblogger.propertiesManager()).saveProperty(handle);
        verify(weblogger.propertiesManager()).saveProperty(aggregated);
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("frontpageConfig.values.saved"),
                ControllerTestFixture.flashMessages(redirectAttributes));
        assertEquals("index.heading", model.getAttribute("pageTitle"));
    }

    @Test
    void anUntickedAggregateBoxIsStoredAsFalseRatherThanNull() throws Exception {
        // The checkbox sends nothing when unticked, and "null" in the database
        // would not parse back to a boolean.
        RuntimeConfigProperty aggregated = property(FRONTPAGE_AGGREGATED, "true");
        property(FRONTPAGE_HANDLE, "old");

        controller.save(ControllerTestFixture.requestFor(admin()), model, "travelguide", null, redirectAttributes);

        assertEquals("false", aggregated.getValue());
    }

    @Test
    void aFailedSaveIsReportedInsteadOfBeingAnnouncedAsSaved() throws Exception {
        when(weblogger.propertiesManager().getProperty(FRONTPAGE_HANDLE))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.save(
                ControllerTestFixture.requestFor(admin()), model, "travelguide", Boolean.TRUE, redirectAttributes);

        assertEquals("redirect:/pressroom-ui/setup.rol", view);
        assertEquals(List.of("frontpageConfig.values.error"), ControllerTestFixture.flashErrors(redirectAttributes));
        assertEquals(List.of(), ControllerTestFixture.flashMessages(redirectAttributes));
        verify(weblogger.propertiesManager(), never()).saveProperty(any());
    }

    private RuntimeConfigProperty property(String name, String value) throws WebloggerException {
        RuntimeConfigProperty property = new RuntimeConfigProperty(name, value);
        when(weblogger.propertiesManager().getProperty(name)).thenReturn(property);
        return property;
    }

    private static User admin() {
        User user = new User();
        user.setUserName("admin");
        return user;
    }
}
