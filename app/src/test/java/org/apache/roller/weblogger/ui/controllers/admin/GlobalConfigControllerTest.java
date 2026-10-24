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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * <p>The page edits the live {@code roller_properties} rows, so the interesting
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
        // RollerHandlerInterceptor enforces this before the handler runs; it is
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
        verify(weblogger.weblogger()).flush();
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

        controller.save(privacyRequest(" privacy ", "front"), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("privacy", stored.get(PRIVACY).getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void privacyPageIsCheckedAgainstTheStoredFrontpageWhenTheFormOmitsIt() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);

        controller.save(privacyRequest("privacy", null), model);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("privacy", stored.get(PRIVACY).getValue());
    }

    @Test
    void privacyPageUnknownSlugIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        frontBlog("front");

        controller.save(privacyRequest("nope", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageDraftSlugIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.DRAFT);

        controller.save(privacyRequest("privacy", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageWithoutFrontpageBlogIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "");

        controller.save(privacyRequest("privacy", ""), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.noFrontpage");
    }

    @Test
    void privacyPageWithUnknownFrontpageBlogIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "ghost");

        controller.save(privacyRequest("privacy", "ghost"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.noFrontpage");
    }

    @Test
    void privacyPageLookupFailureIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        Weblog blog = frontBlog("front");
        when(weblogger.weblogPageManager().getPageBySlug(blog, "privacy"))
                .thenThrow(new WebloggerException("database down"));

        controller.save(privacyRequest("privacy", "front"), model);

        assertRefused(stored, "old", "configForm.error.privacyPage.notFound");
    }

    @Test
    void privacyPageFrontpageLookupFailureIsRefused() throws Exception {
        Map<String, RuntimeConfigProperty> stored = privacyProps("old", "front");
        when(weblogger.weblogManager().getWeblogByHandle("front"))
                .thenThrow(new WebloggerException("database down"));

        controller.save(privacyRequest("privacy", "front"), model);

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

        controller.save(privacyRequest("privacy", "front"), model);

        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertTrue(a.getLastModified().after(past));
        assertTrue(b.getLastModified().after(past));
        verify(weblogger.weblogManager()).saveWeblog(a);
        verify(weblogger.weblogManager()).saveWeblog(b);
    }

    @Test
    void unchangedPrivacyPageTouchesNothing() throws Exception {
        privacyProps("privacy", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        Weblog a = new Weblog();
        java.util.Date past = new java.util.Date(1_000L);
        a.setLastModified(past);
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(a));

        controller.save(privacyRequest("privacy", "front"), model);

        assertEquals(past, a.getLastModified());
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aFailedSaveDoesNotTouchTheWeblogs() throws Exception {
        privacyProps("", "front");
        page(frontBlog("front"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.propertiesManager()).saveProperties(any());

        controller.save(privacyRequest("privacy", "front"), model);

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

        controller.save(privacyRequest("privacy", "front"), model);

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
    }

    // --- helpers ---

    /** A properties map as {@code PropertiesManager} would return it. */
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
