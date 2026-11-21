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

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 1, criterion 1): written before the site-settings rules moved out of
 * {@link GlobalConfigController}, and passing on the code they replaced. They
 * pin the screen's exact answers -- every error's message key and arguments
 * in order, no field marked, the view, what is stored and what the refused
 * form shows back -- for each case the move touches, so the move (criterion
 * 3) is seen to change none of them.
 */
class GlobalConfigCharacterisationTest {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("ApplicationResources");
    private static final String FRONT = "site.frontpage.weblog.handle";
    private static final String FRONT_PAGE = "site.frontpage.page";
    private static final String PRIVACY = "site.privacy.page";

    private MockWeblogger weblogger;
    private GlobalConfigController controller;
    private ExtendedModelMap model;
    private Map<String, RuntimeConfigProperty> stored;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new GlobalConfigController());
        model = new ExtendedModelMap();
        stored = new LinkedHashMap<>();
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --- per-type judgement ---

    @Test
    void aDiscoveryValueOutOfRangeIsTheRangeErrorWithItsEscapedLabel() throws Exception {
        store("site.discovery.sharePercent", "20");

        String view = save(param("site.discovery.sharePercent", "101"));

        assertEquals(".GlobalConfig", view);
        assertEquals(List.of("configForm.error.discoveryRange[" + label("configForm.discoverySharePercent")
                + ", 0, 100]"), ControllerTestFixture.errors(model));
        assertRefusedShowing("site.discovery.sharePercent", "101");
    }

    @Test
    void anUnparsableIntegerNamesItsLabelAndTheEscapedValue() throws Exception {
        store("site.pages.maxEntries", "30");

        save(param("site.pages.maxEntries", "<b>many</b>"));

        assertEquals(List.of("ConfigForm.invalidIntegerProperty[" + label("configForm.pageMaxEntries")
                + ", &lt;b&gt;many&lt;/b&gt;]"), ControllerTestFixture.errors(model));
        assertRefusedShowing("site.pages.maxEntries", "<b>many</b>");
    }

    @Test
    void anUnparsableFloatNamesItsLabelAndTheValue() throws Exception {
        store("uploads.file.maxsize", "2.00");

        save(param("uploads.file.maxsize", "big"));

        assertEquals(List.of("ConfigForm.invalidFloatProperty[" + label("configForm.maxFileSize") + ", big]"),
                ControllerTestFixture.errors(model));
        assertRefusedShowing("uploads.file.maxsize", "big");
    }

    @Test
    void anIntegerIsStoredAsTypedAndABooleanAsTrueOrFalse() throws Exception {
        store("site.pages.maxEntries", "30");
        store("uploads.enabled", "false");
        HttpServletRequest request = param("site.pages.maxEntries", "45");
        when(request.getParameter("uploads.enabled")).thenReturn("on");

        save(request);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("45", stored.get("site.pages.maxEntries").getValue());
        assertEquals("false", stored.get("uploads.enabled").getValue(), "anything but true is false");
    }

    @Test
    void stringAndTextValuesAreStoredTrimmed() throws Exception {
        store("site.name", "Old");
        store("site.description", "Old");
        HttpServletRequest request = param("site.name", "  New Name \t");
        when(request.getParameter("site.description")).thenReturn("\n A site \n");

        save(request);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("New Name", stored.get("site.name").getValue());
        assertEquals("A site", stored.get("site.description").getValue());
    }

    /**
     * The order the errors are listed in: each property's own error in the
     * order the properties are stored, then the front door's, then the
     * privacy page's.
     */
    @Test
    void severalErrorsAreListedPropertyByPropertyThenFrontDoorThenPrivacy() throws Exception {
        store("site.name", "Old");
        store(PRIVACY, "");
        store(FRONT, "");
        store("site.pages.maxEntries", "30");
        store("uploads.file.maxsize", "2.00");
        HttpServletRequest request = param(PRIVACY, "nope");
        when(request.getParameter(FRONT)).thenReturn("site-space");
        when(request.getParameter("site.pages.maxEntries")).thenReturn("x");
        when(request.getParameter("uploads.file.maxsize")).thenReturn("y");

        save(request);

        assertEquals(List.of("ConfigForm.invalidProperty[site.name]",
                "ConfigForm.invalidIntegerProperty[" + label("configForm.pageMaxEntries") + ", x]",
                "ConfigForm.invalidFloatProperty[" + label("configForm.maxFileSize") + ", y]",
                "ConfigForm.invalidProperty[" + FRONT + "]",
                "configForm.error.privacyPage.notFound"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.invalidFields(model));
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    // --- the front door ---

    @Test
    void aDraftUnknownOrBlankFrontDoorPageIsRefusedAndBothSettingsShowAsStored() throws Exception {
        store(FRONT, "travel");
        store(FRONT_PAGE, "");
        page(siteSpace(), "draft", WeblogPage.PubStatus.DRAFT);

        for (String choice : List.of("page:draft", "page:nope", "page:", "page:  ")) {
            model = new ExtendedModelMap();
            String view = save(param(FRONT, choice));

            assertEquals(".GlobalConfig", view, choice);
            assertEquals(List.of("configForm.error.frontpagePage.notFound"), ControllerTestFixture.errors(model),
                    choice);
            assertEquals(List.of(), ControllerTestFixture.invalidFields(model), choice);
            assertEquals("travel", shown(FRONT), choice);
            assertEquals("", shown(FRONT_PAGE), choice);
        }
        assertEquals("travel", stored.get(FRONT).getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void theSiteSpaceHandleInAnyCaseIsRefusedAsTheFrontDoorBlog() throws Exception {
        store(FRONT, "travel");
        store(FRONT_PAGE, "");

        for (String handle : List.of(Weblog.SITE_SPACE_HANDLE, Weblog.SITE_SPACE_HANDLE.toUpperCase(Locale.ROOT))) {
            model = new ExtendedModelMap();
            save(param(FRONT, handle));

            assertEquals(List.of("ConfigForm.invalidProperty[" + FRONT + "]"), ControllerTestFixture.errors(model));
            assertEquals("travel", shown(FRONT));
        }
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void anUnchangedStaleFrontDoorPageIsLeftAloneWithoutALookup() throws Exception {
        store(FRONT, "");
        store(FRONT_PAGE, "gone");
        store("site.name", "Old");
        Weblog site = siteSpace();
        HttpServletRequest request = param(FRONT, "page:gone");
        when(request.getParameter("site.name")).thenReturn("New");

        save(request);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("gone", stored.get(FRONT_PAGE).getValue());
        assertEquals("New", stored.get("site.name").getValue());
        verify(weblogger.weblogPageManager(), never()).getPageBySlug(site, "gone");
    }

    @Test
    void aPublishedFrontDoorPageIsStoredTrimmedAndBlanksTheBlog() throws Exception {
        store(FRONT, "travel");
        store(FRONT_PAGE, "");
        page(siteSpace(), "home", WeblogPage.PubStatus.PUBLISHED);

        save(param(FRONT, "page: home "));

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("", stored.get(FRONT).getValue());
        assertEquals("home", stored.get(FRONT_PAGE).getValue());
    }

    @Test
    void aFrontDoorPageLookupThatThrowsRefuses() throws Exception {
        store(FRONT, "");
        store(FRONT_PAGE, "");
        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPageBySlug(site, "home")).thenThrow(new WebloggerException("down"));

        save(param(FRONT, "page:home"));

        assertEquals(List.of("configForm.error.frontpagePage.notFound"), ControllerTestFixture.errors(model));
    }

    // --- the privacy page ---

    @Test
    void anUnpublishedPrivacyPageIsRefusedAndShownAsTyped() throws Exception {
        store(PRIVACY, "old");
        page(siteSpace(), "privacy", WeblogPage.PubStatus.DRAFT);

        save(param(PRIVACY, " privacy "));

        assertEquals(List.of("configForm.error.privacyPage.notFound"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.invalidFields(model));
        assertEquals("old", stored.get(PRIVACY).getValue());
        assertEquals("privacy", shown(PRIVACY));
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void aPrivacyPageLookupThatThrowsRefuses() throws Exception {
        store(PRIVACY, "");
        when(weblogger.weblogManager().getSiteSpace()).thenThrow(new WebloggerException("down"));

        save(param(PRIVACY, "privacy"));

        assertEquals(List.of("configForm.error.privacyPage.notFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void aPrivacyPageUnpublishedSinceItWasChosenNeverBlocksAnUnrelatedSave() throws Exception {
        store(PRIVACY, "privacy");
        store("site.adminemail", "a@x.test");
        Weblog site = siteSpace();
        page(site, "privacy", WeblogPage.PubStatus.DRAFT);
        HttpServletRequest request = param(PRIVACY, "privacy");
        when(request.getParameter("site.adminemail")).thenReturn("b@x.test");

        save(request);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("b@x.test", stored.get("site.adminemail").getValue());
        verify(weblogger.weblogPageManager(), never()).getPageBySlug(site, "privacy");
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    // --- cache expiry: rendered pages expire only through weblog.lastModified ---

    @Test
    void aChangeToEachRenderedPageInputMovesEveryWeblogAndTheSiteSpace() throws Exception {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(PRIVACY, "privacy");
        changes.put(FRONT, "travel");
        changes.put(FRONT_PAGE, "home");
        changes.put("site.absoluteurl", "https://new.test");
        changes.put("site.name", "New");
        changes.put("site.shortName", "New");
        changes.put("site.description", "New");
        changes.put("site.verification.bing", "New");
        for (Map.Entry<String, String> change : changes.entrySet()) {
            weblogger.detach();
            setUp();
            Weblog site = siteSpace();
            page(site, "privacy", WeblogPage.PubStatus.PUBLISHED);
            page(site, "home", WeblogPage.PubStatus.PUBLISHED);
            for (String name : changes.keySet()) {
                store(name, "");
            }
            HttpServletRequest request = request();
            for (String name : changes.keySet()) {
                if (!FRONT_PAGE.equals(name)) {
                    // The form never posts the page setting: the select carries it.
                    when(request.getParameter(name)).thenReturn("");
                }
            }
            String name = change.getKey();
            when(request.getParameter(FRONT_PAGE.equals(name) ? FRONT : name))
                    .thenReturn(FRONT_PAGE.equals(name) ? "page:" + change.getValue() : change.getValue());
            Weblog blog = blogLastModifiedLongAgo();
            site.setLastModified(new Date(1_000L));

            save(request);

            assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model), name);
            assertEquals(change.getValue(), stored.get(name).getValue(), name);
            assertTrue(blog.getLastModified().after(new Date(1_000L)), name + " moves the blog");
            assertTrue(site.getLastModified().after(new Date(1_000L)), name + " moves the site space");
            verify(weblogger.weblogManager()).saveWeblog(blog);
            verify(weblogger.weblogManager()).saveWeblog(site);
        }
    }

    @Test
    void aChangeToAnyOtherPropertyMovesNoWeblog() throws Exception {
        store("site.adminemail", "a@x.test");
        store("site.pages.maxEntries", "30");
        Weblog blog = blogLastModifiedLongAgo();
        HttpServletRequest request = param("site.adminemail", "b@x.test");
        when(request.getParameter("site.pages.maxEntries")).thenReturn("31");

        save(request);

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertEquals(new Date(1_000L), blog.getLastModified());
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void theDiscoveryRefreshFollowsAChangedDiscoveryValueOnly() throws Exception {
        store("site.discovery.sharePercent", "20");
        save(param("site.discovery.sharePercent", "20"));
        verify(weblogger.discoveryManager(), never()).refresh();

        model = new ExtendedModelMap();
        save(param("site.discovery.sharePercent", "30"));
        verify(weblogger.discoveryManager()).refresh();
    }

    // --- helpers ---

    private String save(HttpServletRequest request) {
        return controller.save(request, model);
    }

    private void store(String name, String value) {
        stored.put(name, new RuntimeConfigProperty(name, value));
    }

    private static String label(String key) {
        return org.apache.commons.text.StringEscapeUtils.escapeHtml4(BUNDLE.getString(key));
    }

    private HttpServletRequest request() {
        HttpServletRequest request = ControllerTestFixture.requestFor(new User());
        when(request.getParameter(anyString())).thenReturn(null);
        return request;
    }

    private HttpServletRequest param(String name, String value) {
        HttpServletRequest request = request();
        when(request.getParameter(name)).thenReturn(value);
        return request;
    }

    /** The value the re-rendered form shows for {@code name}. */
    private String shown(String name) {
        @SuppressWarnings("unchecked")
        Map<String, RuntimeConfigProperty> shown = (Map<String, RuntimeConfigProperty>) model.getAttribute("properties");
        return shown.get(name).getValue();
    }

    private void assertRefusedShowing(String name, String typed) throws Exception {
        assertEquals(List.of(), ControllerTestFixture.invalidFields(model));
        assertEquals(typed, shown(name), "the refused form shows what was typed");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    private Weblog siteSpace() throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        when(weblogger.weblogManager().getSiteSpace()).thenReturn(site);
        return site;
    }

    private void page(Weblog blog, String slug, WeblogPage.PubStatus status) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setStatus(status);
        when(weblogger.weblogPageManager().getPageBySlug(blog, slug)).thenReturn(page);
    }

    private Weblog blogLastModifiedLongAgo() throws Exception {
        Weblog blog = new Weblog();
        blog.setHandle("travel");
        blog.setLastModified(new Date(1_000L));
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));
        return blog;
    }
}
