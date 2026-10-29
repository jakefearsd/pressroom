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
package com.wikantik.pressroom.ui.controllers.editor;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the per-weblog {@code analyticsSiteId} and {@code analyticsShareUrl}
 * settings: the Umami website UUID the theme macro builds the tracker tag
 * from, and the operator's saved link to the Umami share dashboard.
 */
class WeblogConfigAnalyticsTest extends EditorControllerTestSupport {

    private WeblogConfigController controller;
    private WeblogConfigBean bean;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new WeblogConfigController());
        bean = new WeblogConfigBean();
        model = newModel();

        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(Collections.emptyList());
        givenRuntimeProperty("site.pages.maxEntries", "30");

        weblog.setActive(Boolean.TRUE);
        bean.copyFrom(weblog);
    }

    @Test
    void savingAValidSiteIdPersistsIt() throws Exception {
        bean.setAnalyticsSiteId("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        controller.save(request, model, bean);

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", weblog.getAnalyticsSiteId());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void savingBlankSiteIdClearsIt() throws Exception {
        weblog.setAnalyticsSiteId("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        bean.setAnalyticsSiteId("   ");

        controller.save(request, model, bean);

        assertNull(weblog.getAnalyticsSiteId(), "A blank submission must clear the setting");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void savingAMalformedSiteIdIsRejectedAndNotPersisted() throws Exception {
        bean.setAnalyticsSiteId("not-a-uuid");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.analyticsSiteId.invalid"),
                "Expected the invalid-site-id error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void savingAValidShareUrlPersistsIt() throws Exception {
        bean.setAnalyticsShareUrl("https://analytics.example.com/share/abc123");

        controller.save(request, model, bean);

        assertEquals("https://analytics.example.com/share/abc123", weblog.getAnalyticsShareUrl());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void savingAnUppercaseSchemeShareUrlPersistsAsTyped() throws Exception {
        // RFC 3986 schemes are case-insensitive (and CtaShortcode already treats
        // its own scheme check that way), but validation accepting the value must
        // not rewrite what the operator typed.
        bean.setAnalyticsShareUrl("HTTPS://EXAMPLE.COM/share/x");

        controller.save(request, model, bean);

        assertEquals("HTTPS://EXAMPLE.COM/share/x", weblog.getAnalyticsShareUrl(),
                "Case-insensitive validation must not normalise the stored value");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void savingBlankShareUrlClearsIt() throws Exception {
        weblog.setAnalyticsShareUrl("https://analytics.example.com/share/abc123");
        bean.setAnalyticsShareUrl("   ");

        controller.save(request, model, bean);

        assertNull(weblog.getAnalyticsShareUrl(), "A blank submission must clear the setting");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void savingAMalformedShareUrlIsRejectedAndNotPersisted() throws Exception {
        bean.setAnalyticsShareUrl("javascript:alert(1)");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.analyticsShareUrl.invalid"),
                "Expected the invalid-share-url error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void copyFromShowsTheStoredValues() {
        weblog.setAnalyticsSiteId("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        weblog.setAnalyticsShareUrl("https://analytics.example.com/share/abc123");

        WeblogConfigBean opened = new WeblogConfigBean();
        opened.copyFrom(weblog);

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", opened.getAnalyticsSiteId());
        assertEquals("https://analytics.example.com/share/abc123", opened.getAnalyticsShareUrl());
    }
}
