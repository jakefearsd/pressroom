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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.config.runtime.ConfigDef;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GlobalConfigController}, the admin settings page.
 *
 * <p>The page edits the live {@code pressroom_properties} rows, so the interesting
 * question at every turn is whether a given submission actually reaches
 * {@code saveProperties} and with what value. A rejected form that saves anyway
 * would silently change the running site.
 */
class GlobalConfigControllerTest {

    private MockWeblogger weblogger;
    private GlobalConfigController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new GlobalConfigController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --- display ---

    @Test
    void theSettingsPageIsAdminOnlyAndNeedsNoWeblog() {
        // PressroomHandlerInterceptor enforces this before the handler runs; it is
        // what keeps site-wide settings away from ordinary bloggers.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("configForm.title", controller.getPageTitle());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("globalConfig", controller.getActionName());
    }

    @Test
    void theFormShowsTheStoredPropertiesAlongsideTheirDefinitions() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "My Site");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        Weblog weblog = new Weblog();
        when(weblogger.weblogManager().getWeblogs(anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(weblog));

        String view = controller.execute(request(), model);

        assertEquals(".GlobalConfig", view);
        assertEquals("globalConfig", model.getAttribute("actionName"));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertSame(stored, model.getAttribute("properties"));
        assertEquals(List.of(weblog), model.getAttribute("weblogs"),
                "the front-page weblog picker needs the weblog list");
        ConfigDef configDef = (ConfigDef) model.getAttribute("globalConfigDef");
        assertNotNull(configDef, "without the config definitions the page has nothing to render");
        assertEquals("global-properties", configDef.getName());
        assertNotNull(configDef.getPropertyDef("site.name"));
    }

    @Test
    void theFormStillRendersWhenThePropertiesCannotBeRead() throws Exception {
        // A broken database read must not take out the one page an admin might
        // use to fix the configuration.
        when(weblogger.propertiesManager().getProperties()).thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request(), model);

        assertEquals(".GlobalConfig", view);
        assertTrue(((Map<?, ?>) model.getAttribute("properties")).isEmpty());
    }

    // --- saving ---

    @Test
    void aSubmittedStringValueIsTrimmedAndSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "Old Name");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("  New Name  ");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals("New Name", stored.get("site.name").getValue(),
                "leading and trailing whitespace in a site name is never intentional");
        verify(weblogger.propertiesManager()).saveProperties(stored);
        // a site name change also touches the weblogs (a second flush)
        verify(weblogger.weblogger(), org.mockito.Mockito.atLeastOnce()).flush();
        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertEquals("admin", model.getAttribute("desiredMenu"),
                "the settings page is re-rendered after saving and still needs its menu");
    }

    @Test
    void anUntickedCheckboxTurnsThePropertyOff() throws Exception {
        // Browsers send nothing at all for an unticked checkbox, so "absent"
        // has to mean false -- otherwise a setting could never be turned off.
        Map<String, RuntimeConfigProperty> stored = properties("uploads.enabled", "true");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals("false", stored.get("uploads.enabled").getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void aTickedCheckboxTurnsThePropertyOn() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("uploads.enabled", "false");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("uploads.enabled")).thenReturn("true");

        controller.save(request, model);

        assertEquals("true", stored.get("uploads.enabled").getValue());
    }

    @Test
    void anIntegerFieldWithLettersInItIsRejectedAndNothingIsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.pages.maxEntries", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.pages.maxEntries")).thenReturn("many");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("ConfigForm.invalidIntegerProperty["),
                "the admin has to be told which property was wrong: " + ControllerTestFixture.errors(model));
        assertEquals("30", stored.get("site.pages.maxEntries").getValue(), "the stored value must be left alone");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
        verify(weblogger.weblogger(), never()).flush();
    }

    /**
     * The properties are managed entities, and JPAPropertiesManagerImpl says an
     * in-place edit is written by whichever commit comes next. Validation
     * used to set each value as it went, so a form refused for one bad
     * integer still left every edit before (and after) it dirty in the
     * thread's EntityManager. A check that refuses must change nothing.
     */
    @Test
    void aRefusedFormLeavesEveryStoredPropertyUnchanged() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "Old Name");
        stored.put("site.pages.maxEntries", new RuntimeConfigProperty("site.pages.maxEntries", "30"));
        stored.put("uploads.enabled", new RuntimeConfigProperty("uploads.enabled", "true"));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("New Name");
        when(request.getParameter("site.pages.maxEntries")).thenReturn("many");
        // uploads.enabled is unticked, so it would turn off

        controller.save(request, model);

        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertEquals("Old Name", stored.get("site.name").getValue(), "a valid edit on a refused form is not applied");
        assertEquals("30", stored.get("site.pages.maxEntries").getValue());
        assertEquals("true", stored.get("uploads.enabled").getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());

        // The form comes back with what the admin typed, so nothing is lost.
        @SuppressWarnings("unchecked")
        Map<String, RuntimeConfigProperty> shown =
                (Map<String, RuntimeConfigProperty>) model.getAttribute("properties");
        assertEquals("New Name", shown.get("site.name").getValue());
        assertEquals("many", shown.get("site.pages.maxEntries").getValue());
        assertEquals("false", shown.get("uploads.enabled").getValue());
    }

    @Test
    void anInvalidIntegerValueIsEscapedInTheErrorMessage() throws Exception {
        // The submitted value is echoed straight back into messages.jsp's
        // deliberately-raw sink; propDesc (the first arg) is server-built and
        // safe, but the submitted value (the second arg) is not.
        Map<String, RuntimeConfigProperty> stored = properties("site.pages.maxEntries", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.pages.maxEntries")).thenReturn("<b>many</b>");

        controller.save(request, model);

        assertTrue(ControllerTestFixture.errors(model).get(0).contains("&lt;b&gt;many&lt;/b&gt;"),
                "Expected the submitted value HTML-escaped, got: " + ControllerTestFixture.errors(model));
    }

    @Test
    void anIntegerFieldWithANumberInItIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.pages.maxEntries", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.pages.maxEntries")).thenReturn("45");

        controller.save(request, model);

        assertEquals("45", stored.get("site.pages.maxEntries").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aFloatFieldWithLettersInItIsRejectedAndNothingIsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("uploads.file.maxsize", "2.00");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("uploads.file.maxsize")).thenReturn("big");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("ConfigForm.invalidFloatProperty["),
                "the admin has to be told which property was wrong: " + ControllerTestFixture.errors(model));
        assertEquals("2.00", stored.get("uploads.file.maxsize").getValue(), "the stored value must be left alone");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void aFloatFieldWithADecimalInItIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("uploads.file.maxsize", "2.00");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("uploads.file.maxsize")).thenReturn("3.5");

        controller.save(request, model);

        assertEquals("3.5", stored.get("uploads.file.maxsize").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aTextFieldMissingFromTheSubmissionIsReportedRatherThanBlanked() throws Exception {
        // Every property on the form should come back with the form. One that
        // does not means the page and the definitions have drifted apart, and
        // silently clearing a site setting would be worse than saying so.
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "My Site");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        String view = controller.save(request(), model);

        assertEquals(".GlobalConfig", view);
        assertEquals(List.of("ConfigForm.invalidProperty[site.name]"), ControllerTestFixture.errors(model));
        assertEquals("My Site", stored.get("site.name").getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void aMissingFrontPageWeblogNeverBlocksTheRestOfTheForm() throws Exception {
        // A fresh install has no weblogs, so the front-page <select> rendered
        // with no options, and a <select> with no options submits nothing:
        // the whole save was refused with "Property ... is null", leaving no
        // way to change any setting. Which weblog fronts the site is a content
        // choice, not a setting the rest of the form depends on, so its
        // absence leaves it unchanged.
        Map<String, RuntimeConfigProperty> stored = properties("site.frontpage.weblog.handle", "");
        stored.put("site.name", new RuntimeConfigProperty("site.name", "Old Name"));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("New Name");

        controller.save(request, model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("New Name", stored.get("site.name").getValue());
        assertEquals("", stored.get("site.frontpage.weblog.handle").getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void theFrontPagePageHasNoFieldHereSoItsAbsenceNeverBlocksTheForm() throws Exception {
        // site.frontpage.page is set from the pages screen, not this form; a
        // save that does not carry it must neither error nor blank it.
        Map<String, RuntimeConfigProperty> stored = properties("site.frontpage.page", "home");
        stored.put("site.name", new RuntimeConfigProperty("site.name", "Old Name"));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("New Name");

        controller.save(request, model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("home", stored.get("site.frontpage.page").getValue());
    }

    @Test
    void aBlankFrontPageWeblogClearsItSoTheSiteDirectoryShows() throws Exception {
        // Characterisation: a blank submission is already stored as blank.
        // The form's "None" option depends on exactly that, so it is pinned.
        Map<String, RuntimeConfigProperty> stored = properties("site.frontpage.weblog.handle", "travel");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.frontpage.weblog.handle")).thenReturn("");

        controller.save(request, model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("", stored.get("site.frontpage.weblog.handle").getValue());
    }

    @Test
    void anUnknownPropertyInTheDatabaseIsLeftUntouched() throws Exception {
        // Rows with no definition in runtimeConfigDefs.xml are not on the form,
        // so the form's silence about them must not be read as "clear it".
        Map<String, RuntimeConfigProperty> stored = properties("some.legacy.property", "keep me");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals("keep me", stored.get("some.legacy.property").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aFailedWriteIsReportedInsteadOfBeingAnnouncedAsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "My Site");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("My Site");
        doThrow(new WebloggerException("database down"))
                .when(weblogger.propertiesManager()).saveProperties(any());

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model));
    }

    // --- site.privacy.page ---

    private static final String PRIVACY = "site.privacy.page";
    private static final String FRONT = "site.frontpage.weblog.handle";

    private Map<String, RuntimeConfigProperty> privacyProps(String privacy, String front) throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties(PRIVACY, privacy);
        stored.put(FRONT, new RuntimeConfigProperty(FRONT, front));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        return stored;
    }

    private HttpServletRequest privacyRequest(String privacy, String front) {
        HttpServletRequest request = request();
        when(request.getParameter(PRIVACY)).thenReturn(privacy);
        when(request.getParameter(FRONT)).thenReturn(front);
        return request;
    }

    private Weblog frontBlog(String handle) throws Exception {
        Weblog blog = new Weblog();
        blog.setHandle(handle);
        when(weblogger.weblogManager().getWeblogByHandle(handle)).thenReturn(blog);
        return blog;
    }

    private void page(Weblog blog, String slug, WeblogPage.PubStatus status) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setStatus(status);
        when(weblogger.weblogPageManager().getPageBySlug(blog, slug)).thenReturn(page);
    }

    private void assertRefused(Map<String, RuntimeConfigProperty> stored, String previous, String errorKey)
            throws Exception {
        assertEquals(1, ControllerTestFixture.errors(model).size(), ControllerTestFixture.errors(model).toString());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith(errorKey), ControllerTestFixture.errors(model).toString());
        assertEquals(previous, stored.get(PRIVACY).getValue(), "the stored value must be left alone");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void privacyPageBlankIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("privacy", "");

        controller.save(privacyRequest("  ", ""), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("", stored.get(PRIVACY).getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void privacyPagePublishedSlugIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);

        controller.save(privacyRequest(" front/privacy ", "front"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("front/privacy", stored.get(PRIVACY).getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void aBareSlugIsAPublishedPageOfTheSiteSpace() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("", "");
        Weblog site = siteSpace();
        page(site, "privacy", WeblogPage.PubStatus.PUBLISHED);

        controller.save(privacyRequest("privacy", null), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("privacy", stored.get(PRIVACY).getValue());
    }

    @Test
    void aBareSlugWithNoSiteSpaceOrNoSuchPageIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "");

        controller.save(privacyRequest("privacy", null), model);
        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");

        model = new ExtendedModelMap();
        siteSpace();
        controller.save(privacyRequest("privacy", null), model);
        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void aDraftSitePageIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "");
        page(siteSpace(), "privacy", WeblogPage.PubStatus.DRAFT);

        controller.save(privacyRequest("privacy", null), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void aMalformedReferenceIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "");

        controller.save(privacyRequest("a/b/c", null), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageUnknownSlugIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        frontBlog("front");

        controller.save(privacyRequest("front/nope", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageDraftSlugIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.DRAFT);

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageWithUnknownBlogIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "");

        controller.save(privacyRequest("ghost/privacy", ""), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageLookupFailureIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        Weblog blog = frontBlog("front");
        when(weblogger.weblogPageManager().getPageBySlug(blog, "privacy"))
                .thenThrow(new WebloggerException("database down"));

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageFrontpageLookupFailureIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        when(weblogger.weblogManager().getWeblogByHandle("front"))
                .thenThrow(new WebloggerException("database down"));

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void changingPrivacyPageTouchesEveryWeblog() throws Exception {
        privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        Weblog a = new Weblog();
        a.setHandle("a");
        Weblog b = new Weblog();
        b.setHandle("b");
        java.util.Date past = new java.util.Date(1_000L);
        a.setLastModified(past);
        b.setLastModified(past);
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a, b));

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertTrue(a.getLastModified().after(past));
        assertTrue(b.getLastModified().after(past));
        verify(weblogger.weblogManager()).saveWeblog(a);
        verify(weblogger.weblogManager()).saveWeblog(b);
    }

    @Test
    void unchangedPrivacyPageTouchesNothing() throws Exception {
        privacyProps("front/privacy", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        Weblog a = new Weblog();
        java.util.Date past = new java.util.Date(1_000L);
        a.setLastModified(past);
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a));

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertEquals(past, a.getLastModified());
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aFailedSaveDoesNotTouchTheWeblogs() throws Exception {
        privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.propertiesManager()).saveProperties(any());

        controller.save(privacyRequest("front/privacy", "front"), model);

        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aFailureTouchingWeblogsIsReported() throws Exception {
        privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        Weblog a = new Weblog();
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a));
        doThrow(new WebloggerException("database down")).when(weblogger.weblogManager()).saveWeblog(a);

        controller.save(privacyRequest("front/privacy", "front"), model);

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
    }

    // --- cache expiry and validation scope (final review, AC6) ---

    private static final String ABSURL = "site.absoluteurl";

    /** Stored privacy, front-page handle and absolute URL, plus one unrelated property. */
    private Map<String, RuntimeConfigProperty> linkProps(String privacy, String front, String absUrl) throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps(privacy, front);
        stored.put(ABSURL, new RuntimeConfigProperty(ABSURL, absUrl));
        stored.put("site.name", new RuntimeConfigProperty("site.name", "Old"));
        return stored;
    }

    private HttpServletRequest linkRequest(String privacy, String front, String absUrl, String name) {
        HttpServletRequest request = privacyRequest(privacy, front);
        when(request.getParameter(ABSURL)).thenReturn(absUrl);
        when(request.getParameter("site.name")).thenReturn(name);
        return request;
    }

    /** Saves and reports whether the weblog was touched and the site-wide cache invalidated. */
    private boolean[] saveAndWatch(HttpServletRequest request, Weblog a) throws Exception {
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a));
        java.util.Date past = new java.util.Date(1_000L);
        a.setLastModified(past);
        boolean invalidated;
        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            controller.save(request, model);
            try {
                cache.verify(() -> CacheManager.invalidate(a));
                invalidated = true;
            } catch (AssertionError e) {
                invalidated = false;
            }
        }
        return new boolean[] {a.getLastModified().after(past), invalidated};
    }

    @Test
    void changingThePrivacySlugTouchesAndClearsTheSiteWideCache() throws Exception {
        linkProps("", "front", "https://x.test");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);

        boolean[] r = saveAndWatch(linkRequest("front/privacy", "front", "https://x.test", "Old"), new Weblog());

        assertTrue(r[0], "touched");
        assertTrue(r[1], "site-wide cache invalidated");
    }

    @Test
    void changingTheFrontpageHandleTouchesAndClearsTheSiteWideCache() throws Exception {
        linkProps("front/privacy", "front", "https://x.test");
        page(frontBlog("other"), "privacy", WeblogPage.PubStatus.PUBLISHED);

        boolean[] r = saveAndWatch(linkRequest("front/privacy", "other", "https://x.test", "Old"), new Weblog());

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertTrue(r[0], "touched");
        assertTrue(r[1], "site-wide cache invalidated");
    }

    @Test
    void changingTheAbsoluteUrlTouchesAndClearsTheSiteWideCache() throws Exception {
        linkProps("front/privacy", "front", "https://x.test");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);

        boolean[] r = saveAndWatch(linkRequest("front/privacy", "front", "https://y.test", "Old"), new Weblog());

        assertTrue(r[0], "touched");
        assertTrue(r[1], "site-wide cache invalidated");
    }

    @Test
    void changingAnUnrelatedPropertyNeitherTouchesNorClears() throws Exception {
        Map<String, RuntimeConfigProperty> stored =
                linkProps("front/privacy", "front", "https://x.test");
        stored.put("site.adminemail", new RuntimeConfigProperty("site.adminemail", "a@x.test"));
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        HttpServletRequest request = linkRequest("front/privacy", "front", "https://x.test", "Old");
        when(request.getParameter("site.adminemail")).thenReturn("b@x.test");

        boolean[] r = saveAndWatch(request, new Weblog());

        assertFalse(r[0], "touched");
        assertFalse(r[1], "site-wide cache invalidated");
    }

    /**
     * A site page's header shows the site name and description, and rendered
     * pages expire only through weblog.lastModified.
     */
    @Test
    void changingTheSiteNameShortNameOrDescriptionTouchesAndClears() throws Exception {
        for (String name : new String[] {"site.name", "site.shortName", "site.description"}) {
            Map<String, RuntimeConfigProperty> stored =
                    linkProps("front/privacy", "front", "https://x.test");
            stored.put(name, new RuntimeConfigProperty(name, "Old"));
            page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
            HttpServletRequest request = linkRequest("front/privacy", "front", "https://x.test", "Old");
            when(request.getParameter(name)).thenReturn("New");

            boolean[] r = saveAndWatch(request, new Weblog());

            assertTrue(r[0], name + " touched");
            assertTrue(r[1], name + " site-wide cache invalidated");
        }
    }

    @Test
    void anUnchangedButNowInvalidPrivacySlugDoesNotBlockAnUnrelatedSave() throws Exception {
        Map<String, RuntimeConfigProperty> stored = linkProps("front/gone", "front", "https://x.test");
        frontBlog("front"); // the page "gone" no longer exists

        controller.save(linkRequest("front/gone", "front", "https://x.test", "New"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("New", stored.get("site.name").getValue());
        verify(weblogger.propertiesManager()).saveProperties(any());
    }

    @Test
    void aChangedInvalidPrivacySlugIsStillRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = linkProps("front/gone", "front", "https://x.test");
        frontBlog("front");

        controller.save(linkRequest("front/nope", "front", "https://x.test", "Old"), model);

        assertRefused(stored, "front/gone", "configForm.error.privacyPage.notFound");
    }

    @Test
    void changingTheFrontpageHandleNeverRevalidatesTheUnrelatedPrivacyReference() throws Exception {
        Map<String, RuntimeConfigProperty> stored = linkProps("front/gone", "front", "https://x.test");
        frontBlog("other");

        controller.save(linkRequest("front/gone", "other", "https://x.test", "Old"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("other", stored.get(FRONT).getValue());
    }

    // --- the front door: a site directory, a site page, or a blog ---

    private static final String FRONT_PAGE = "site.frontpage.page";

    private Weblog siteSpace() throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        when(weblogger.weblogManager().getSiteSpace()).thenReturn(site);
        return site;
    }

    private WeblogPage sitePage(String slug) {
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setTitle(slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        return page;
    }

    private Map<String, RuntimeConfigProperty> doorProps(String handle, String page) throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties(FRONT, handle);
        stored.put(FRONT_PAGE, new RuntimeConfigProperty(FRONT_PAGE, page));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        return stored;
    }

    private HttpServletRequest doorRequest(String choice) {
        HttpServletRequest request = request();
        when(request.getParameter(FRONT)).thenReturn(choice);
        return request;
    }

    @Test
    void thePickerOffersTheDirectoryPublishedSitePagesAndBlogsButNeverTheSiteSpace() throws Exception {
        doorProps("", "");
        Weblog site = siteSpace();
        Weblog blog = new Weblog();
        blog.setHandle("travel");
        when(weblogger.weblogManager().getWeblogs(anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog, site));
        WeblogPage home = sitePage("home");
        when(weblogger.weblogPageManager().getPublishedPages(site)).thenReturn(List.of(home));

        controller.execute(request(), model);

        assertEquals(List.of(blog), model.getAttribute("weblogs"), "AC6: never the site space");
        assertEquals(List.of(home), model.getAttribute("sitePages"));
    }

    @Test
    void thePickerHasNoSitePagesWhenThereIsNoSiteSpaceOrItsPagesCannotBeRead() throws Exception {
        doorProps("", "");
        controller.execute(request(), model);
        assertEquals(List.of(), model.getAttribute("sitePages"));

        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPublishedPages(site)).thenThrow(new WebloggerException("down"));
        controller.execute(request(), model);
        assertEquals(List.of(), model.getAttribute("sitePages"));
    }

    @Test
    void choosingASitePageStoresItsSlugAndBlanksTheBlog() throws Exception {
        Map<String, RuntimeConfigProperty> stored = doorProps("travel", "");
        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPublishedPages(site)).thenReturn(List.of(sitePage("home")));
        page(site, "home", WeblogPage.PubStatus.PUBLISHED);

        controller.save(doorRequest("page:home"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("home", stored.get(FRONT_PAGE).getValue());
        assertEquals("", stored.get(FRONT).getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void aStaleFrontDoorSlugIsOfferedAsASelectedOptionAndSurvivesAnUnchangedSave() throws Exception {
        // The page was unpublished or renamed after it was chosen. The select
        // lists published pages only, so without its own option the browser
        // would submit "" and an unrelated save would silently clear the door.
        Map<String, RuntimeConfigProperty> stored = doorProps("", "gone");
        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPublishedPages(site)).thenReturn(List.of(sitePage("home")));

        controller.execute(request(), model);
        assertEquals("gone", model.getAttribute("staleFrontDoorPage"));

        model = new ExtendedModelMap();
        controller.save(doorRequest("page:gone"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("gone", stored.get(FRONT_PAGE).getValue());
        assertEquals("gone", model.getAttribute("staleFrontDoorPage"));
    }

    @Test
    void noStaleFrontDoorSlugWhenTheStoredPageIsPublishedOrNoneIsSet() throws Exception {
        doorProps("", "home");
        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPublishedPages(site)).thenReturn(List.of(sitePage("home")));
        controller.execute(request(), model);
        assertEquals("", model.getAttribute("staleFrontDoorPage"));

        doorProps("travel", "");
        model = new ExtendedModelMap();
        controller.execute(request(), model);
        assertEquals("", model.getAttribute("staleFrontDoorPage"));
    }

    @Test
    void choosingABlogBlanksTheSitePage() throws Exception {
        Map<String, RuntimeConfigProperty> stored = doorProps("", "home");

        controller.save(doorRequest("travel"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("travel", stored.get(FRONT).getValue());
        assertEquals("", stored.get(FRONT_PAGE).getValue());
    }

    @Test
    void choosingTheDirectoryBlanksBoth() throws Exception {
        Map<String, RuntimeConfigProperty> stored = doorProps("travel", "home");

        controller.save(doorRequest(""), model);

        assertEquals("", stored.get(FRONT).getValue());
        assertEquals("", stored.get(FRONT_PAGE).getValue());
    }

    @Test
    void aDraftOrMissingFrontDoorPageIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = doorProps("travel", "");
        Weblog site = siteSpace();
        page(site, "draft", WeblogPage.PubStatus.DRAFT);

        controller.save(doorRequest("page:draft"), model);
        assertEquals(List.of("configForm.error.frontpagePage.notFound"), ControllerTestFixture.errors(model));
        assertEquals("travel", stored.get(FRONT).getValue());
        assertEquals("", stored.get(FRONT_PAGE).getValue());

        model = new ExtendedModelMap();
        controller.save(doorRequest("page:"), model);
        assertEquals(List.of("configForm.error.frontpagePage.notFound"), ControllerTestFixture.errors(model));
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void theSiteSpaceIsRefusedAsAFrontDoorBlogEvenWhenPostedDirectly() throws Exception {
        Map<String, RuntimeConfigProperty> stored = doorProps("travel", "");

        controller.save(doorRequest(Weblog.SITE_SPACE_HANDLE.toUpperCase(java.util.Locale.ROOT)), model);

        assertEquals(List.of("ConfigForm.invalidProperty[" + FRONT + "]"), ControllerTestFixture.errors(model));
        assertEquals("travel", stored.get(FRONT).getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void aFrontDoorPageLookupFailureIsRefused() throws Exception {
        doorProps("", "");
        Weblog site = siteSpace();
        when(weblogger.weblogPageManager().getPageBySlug(site, "home")).thenThrow(new WebloggerException("down"));

        controller.save(doorRequest("page:home"), model);

        assertEquals(List.of("configForm.error.frontpagePage.notFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void changingTheFrontDoorTouchesEveryWeblogAndTheSiteSpace() throws Exception {
        doorProps("travel", "");
        Weblog site = siteSpace();
        page(site, "home", WeblogPage.PubStatus.PUBLISHED);
        Weblog blog = new Weblog();
        java.util.Date past = new java.util.Date(1_000L);
        blog.setLastModified(past);
        site.setLastModified(past);
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));

        controller.save(doorRequest("page:home"), model);

        assertTrue(blog.getLastModified().after(past));
        assertTrue(site.getLastModified().after(past), "the site space is not in the weblog listing");
        verify(weblogger.weblogManager()).saveWeblog(site);
    }

    // --- helpers ---

    /** A properties map as {@code PropertiesManager} would return it. */
    // --- front page discovery settings ---

    @Test
    void refreshIsEditedInMinutesStoredInSeconds() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.discovery.refreshSeconds", "3600");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("discoveryRefreshMinutes")).thenReturn("90");

        controller.save(request, model);

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertEquals("5400", stored.get("site.discovery.refreshSeconds").getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void anAbsentMinutesFieldLeavesTheRefreshAlone() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.discovery.refreshSeconds", "7200");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertEquals("7200", stored.get("site.discovery.refreshSeconds").getValue());
    }

    @Test
    void outOfRangeShareIsRefusedAndNothingSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.discovery.sharePercent", "20");
        stored.put("site.name", new RuntimeConfigProperty("site.name", "Old Name"));
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.discovery.sharePercent")).thenReturn("101");
        when(request.getParameter("site.name")).thenReturn("New Name");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(1, ControllerTestFixture.errors(model).size(), ControllerTestFixture.errors(model).toString());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("configForm.error.discoveryRange["),
                ControllerTestFixture.errors(model).toString());
        assertEquals("Old Name", stored.get("site.name").getValue());
        assertEquals("20", stored.get("site.discovery.sharePercent").getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void outOfRangeWindowIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.discovery.windowDays", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.discovery.windowDays")).thenReturn("0");

        controller.save(request, model);

        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("configForm.error.discoveryRange["));
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void nonIntegerMinutesRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.discovery.refreshSeconds", "3600");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("discoveryRefreshMinutes")).thenReturn("soon");

        controller.save(request, model);

        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("ConfigForm.invalidIntegerProperty["));
        assertEquals("3600", stored.get("site.discovery.refreshSeconds").getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void minutesOutsideOneToADayAreRefused() throws Exception {
        for (String minutes : List.of("0", "1441", "-5", "99999999")) {
            Map<String, RuntimeConfigProperty> stored = properties("site.discovery.refreshSeconds", "3600");
            when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
            HttpServletRequest request = request();
            when(request.getParameter("discoveryRefreshMinutes")).thenReturn(minutes);
            ExtendedModelMap m = new ExtendedModelMap();

            controller.save(request, m);

            assertTrue(ControllerTestFixture.errors(m).get(0).startsWith("configForm.error.discoveryRange["),
                    minutes + ": " + ControllerTestFixture.errors(m));
            assertEquals("3600", stored.get("site.discovery.refreshSeconds").getValue());
        }
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    private static Map<String, RuntimeConfigProperty> properties(String name, String value) {
        Map<String, RuntimeConfigProperty> props = new LinkedHashMap<>();
        props.put(name, new RuntimeConfigProperty(name, value));
        return props;
    }

    private HttpServletRequest request() {
        HttpServletRequest request = ControllerTestFixture.requestFor(new User());
        when(request.getParameter(anyString())).thenReturn(null);
        return request;
    }
}
