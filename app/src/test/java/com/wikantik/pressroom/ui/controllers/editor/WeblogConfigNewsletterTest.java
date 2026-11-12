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

import com.wikantik.pressroom.business.SubscribeFormStatus;
import org.junit.jupiter.api.AfterEach;
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
 * Tests for the per-weblog {@code newsletterListUuid} setting: the field
 * Weblog Settings uses to tell Pressroom which Listmonk list a weblog's
 * subscribe form feeds.
 */
class WeblogConfigNewsletterTest extends EditorControllerTestSupport {

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
    void savingAValidUuidPersistsIt() throws Exception {
        bean.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        controller.save(request, model, bean);

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", weblog.getNewsletterListUuid());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void savingBlankClearsIt() throws Exception {
        weblog.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        bean.setNewsletterListUuid("   ");

        controller.save(request, model, bean);

        assertNull(weblog.getNewsletterListUuid(), "A blank submission must clear the setting");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void savingAMalformedUuidIsRejectedAndNotPersisted() throws Exception {
        bean.setNewsletterListUuid("not-a-uuid");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.newsletterListUuid.invalid"),
                "Expected the invalid-uuid error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void copyFromShowsTheStoredValue() {
        weblog.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        WeblogConfigBean opened = new WeblogConfigBean();
        opened.copyFrom(weblog);

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", opened.getNewsletterListUuid());
    }

    // --- 0.9.1 criterion 17: the subscribe form's status beside the field ---

    private static final String BASE_URL = "newsletter.listmonk.baseurl";
    private String previousBaseUrl;
    private boolean baseUrlOverridden;

    private void givenNewsletterService(String baseUrl) {
        String previous = overrideConfigProperty(BASE_URL, baseUrl);
        if (!baseUrlOverridden) {
            previousBaseUrl = previous;
            baseUrlOverridden = true;
        }
    }

    @AfterEach
    void restoreBaseUrl() {
        // WebloggerConfig is process-global: put back what was there.
        if (baseUrlOverridden) {
            overrideConfigProperty(BASE_URL, previousBaseUrl);
        }
    }

    @Test
    void openingTheFormReportsOffWithoutAList() {
        givenNewsletterService("http://listmonk:9000");

        controller.execute(request, model, bean);

        assertEquals(SubscribeFormStatus.OFF_NO_LIST, model.getAttribute("subscribeFormStatus"));
    }

    @Test
    void openingTheFormReportsOffWhenTheSiteHasNoNewsletterService() {
        givenNewsletterService("");
        weblog.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        controller.execute(request, model, bean);

        assertEquals(SubscribeFormStatus.OFF_NOT_CONFIGURED, model.getAttribute("subscribeFormStatus"));
    }

    @Test
    void openingTheFormReportsOnWithAListAndAService() {
        givenNewsletterService("http://listmonk:9000");
        weblog.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        controller.execute(request, model, bean);

        assertEquals(SubscribeFormStatus.ON, model.getAttribute("subscribeFormStatus"));
    }

    /** The page shown after a save describes the blog as saved, not as it was when the request arrived. */
    @Test
    void theStatusAfterASaveReflectsTheSavedList() {
        givenNewsletterService("http://listmonk:9000");
        bean.setNewsletterListUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        controller.save(request, model, bean);

        assertEquals(SubscribeFormStatus.ON, model.getAttribute("subscribeFormStatus"));
    }

    /** A refused save stored nothing, so the status is still the stored blog's. */
    @Test
    void theStatusAfterARefusedSaveIsStillTheStoredOne() {
        givenNewsletterService("http://listmonk:9000");
        bean.setNewsletterListUuid("not-a-uuid");

        controller.save(request, model, bean);

        assertEquals(SubscribeFormStatus.OFF_NO_LIST, model.getAttribute("subscribeFormStatus"));
    }
}
