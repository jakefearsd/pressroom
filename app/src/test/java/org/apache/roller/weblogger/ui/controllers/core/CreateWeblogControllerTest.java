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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CreateWeblogController}.
 *
 * <p>A weblog handle becomes part of every URL the blog will ever have and has
 * to be unique, so the tests here are mostly about which handles are allowed
 * through to {@code addWeblog} and which are turned away.
 */
class CreateWeblogControllerTest {

    private MockWeblogger weblogger;
    private CreateWeblogController controller;
    private ExtendedModelMap model;
    private RedirectAttributes redirectAttributes;
    private String previousGroupBlogging;
    private String previousAllowedChars;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new CreateWeblogController());
        model = new ExtendedModelMap();
        redirectAttributes = new RedirectAttributesModelMap();
        previousGroupBlogging = ControllerTestFixture.setConfigProperty("groupblogging.enabled", "true");
        previousAllowedChars = ControllerTestFixture.setConfigProperty("username.allowedChars", "A-Za-z0-9");
        allowWeblogCreation(true);
        org.apache.roller.weblogger.business.themes.SharedTheme journal =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(List.of(journal));
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restoreConfigProperty("groupblogging.enabled", previousGroupBlogging);
        ControllerTestFixture.restoreConfigProperty("username.allowedChars", previousAllowedChars);
    }

    // --- the form ---

    @Test
    void theFormIsPrefilledFromTheUsersOwnSettings() {
        User user = user("jake");
        user.setLocale("en_GB");
        user.setTimeZone("Europe/Berlin");
        user.setEmailAddress("jake@example.com");
        CreateWeblogBean bean = new CreateWeblogBean();

        String view = controller.execute(ControllerTestFixture.requestFor(user), model, bean);

        assertEquals(".CreateWeblog", view);
        assertEquals("en_GB", bean.getLocale());
        assertEquals("Europe/Berlin", bean.getTimeZone());
        assertEquals("jake@example.com", bean.getEmailAddress());
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
        assertEquals("createWebsite.title", model.getAttribute("pageTitle"));
        assertEquals(user, model.getAttribute("authenticatedUser"));
    }

    @Test
    void theThemeListExcludesTheFrontpageTheme() {
        // "frontpage" is the $site-wide aggregator theme (see the matching
        // exclusion -- and its grandfathering case -- on ThemeEditController)
        // and breaks any weblog that adopts it. A brand-new weblog can never
        // already be on it, so unlike ThemeEditController's picker there is
        // no grandfathering case here: it is always excluded.
        org.apache.roller.weblogger.business.themes.SharedTheme frontpage =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(frontpage.getId()).thenReturn("frontpage");
        org.apache.roller.weblogger.business.themes.SharedTheme journal =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(List.of(frontpage, journal));

        controller.execute(ControllerTestFixture.requestFor(user("jake")), model, new CreateWeblogBean());

        assertEquals(List.of(journal), model.getAttribute("themes"));
    }

    /**
     * The picker defaulted to whichever theme listed first (Portfolio,
     * alphabetically), though Journal is the neutral default its own
     * description promises. A fresh form preselects Journal when it is enabled.
     */
    @Test
    void aFreshFormPreselectsTheJournalTheme() {
        org.apache.roller.weblogger.business.themes.SharedTheme portfolio =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(portfolio.getId()).thenReturn("portfolio");
        org.apache.roller.weblogger.business.themes.SharedTheme journal =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(List.of(portfolio, journal));
        CreateWeblogBean bean = new CreateWeblogBean();

        controller.execute(ControllerTestFixture.requestFor(user("jake")), model, bean);

        assertEquals("journal", bean.getTheme());
    }

    @Test
    void withoutTheJournalThemeTheFormLeavesTheChoiceToTheList() {
        org.apache.roller.weblogger.business.themes.SharedTheme portfolio =
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.themes.SharedTheme.class);
        when(portfolio.getId()).thenReturn("portfolio");
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(List.of(portfolio));
        CreateWeblogBean bean = new CreateWeblogBean();

        controller.execute(ControllerTestFixture.requestFor(user("jake")), model, bean);

        assertEquals(null, bean.getTheme());
    }

    @Test
    void creatingAWeblogNeedsNoWeblogAndSuppliesItsOwnFormBean() {
        // The page exists for users who have no weblog yet, so requiring one
        // would put it out of reach of everybody who needs it.
        assertFalse(controller.isWeblogRequired());
        assertEquals("createWebsite.title", controller.getPageTitle());
        assertNotNull(controller.getBean());
    }

    @Test
    void theAllowedHandleCharactersAreWhateverTheDeploymentConfigured() throws Exception {
        // Handles are validated against username.allowedChars; a site that
        // widens that set must get the wider set, not the built-in default.
        ControllerTestFixture.setConfigProperty("username.allowedChars", "A-Za-z0-9_");
        CreateWeblogBean bean = validBean("travel_guide");

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager()).addWeblog(any());
    }

    @Test
    void withNoConfiguredCharacterSetTheBuiltInDefaultAppliesToHandles() throws Exception {
        ControllerTestFixture.setConfigProperty("username.allowedChars", null);
        CreateWeblogBean bean = validBean("travel_guide");

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of("createWeblog.error.invalidHandle"), ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void withWeblogCreationSwitchedOffTheFormIsRefused() throws Exception {
        allowWeblogCreation(false);

        String view = controller.execute(ControllerTestFixture.requestFor(user("jake")), model, new CreateWeblogBean());

        assertEquals(".GenericError", view);
        assertEquals(List.of("createWebsite.disabled"), ControllerTestFixture.errors(model));
    }

    @Test
    void withGroupBloggingOffAUserWhoAlreadyHasABlogIsTurnedAway() throws Exception {
        // Single-blog mode is enforced here, not in the data layer, so this is
        // the only thing standing between a user and a second weblog.
        ControllerTestFixture.setConfigProperty("groupblogging.enabled", "false");
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user))
                .thenReturn(List.of(new WeblogPermission()));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model, new CreateWeblogBean());

        assertEquals(".GenericError", view);
        assertEquals(List.of("createWebsite.oneBlogLimit"), ControllerTestFixture.errors(model));
    }

    @Test
    void withGroupBloggingOnAnExistingBlogIsNoObstacle() throws Exception {
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user))
                .thenReturn(List.of(new WeblogPermission()));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model, new CreateWeblogBean());

        assertEquals(".CreateWeblog", view);
    }

    @Test
    void aFailedPermissionLookupStopsTheFormRatherThanGuessing() throws Exception {
        ControllerTestFixture.setConfigProperty("groupblogging.enabled", "false");
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model, new CreateWeblogBean());

        assertEquals(".GenericError", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
    }

    // --- saving ---

    @Test
    void aValidSubmissionCreatesTheWeblogAndConfirmsIt() throws Exception {
        User user = user("jake");
        CreateWeblogBean bean = validBean("travelguide");
        bean.setName("Travel Guide");
        bean.setDescription("Where to go");
        bean.setEmailAddress("jake@example.com");
        bean.setTheme("journal");
        bean.setLocale("en_US");
        bean.setTimeZone("America/New_York");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        ArgumentCaptor<Weblog> created = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogger.weblogManager()).addWeblog(created.capture());
        Weblog weblog = created.getValue();
        assertEquals("travelguide", weblog.getHandle());
        assertEquals("Travel Guide", weblog.getName());
        assertEquals("Where to go", weblog.getTagline());
        assertEquals("jake@example.com", weblog.getEmailAddress());
        assertEquals("journal", weblog.getEditorTheme());
        assertEquals("en_US", weblog.getLocale());
        assertEquals("America/New_York", weblog.getTimeZone());
        assertEquals("jake", weblog.getCreatorUserName(), "the signed-in user owns what they create");
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("createWebsite.created[travelguide]"),
                ControllerTestFixture.flashMessages(redirectAttributes));
    }

    @Test
    void aHandleWithPunctuationInItIsRefused() throws Exception {
        // Handles go straight into URLs; anything outside the allowed set would
        // produce a blog that cannot be linked to.
        CreateWeblogBean bean = validBean("my blog!");

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertEquals(List.of("createWeblog.error.invalidHandle"), ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
        // The form comes back, so it still needs its pickers and page title.
        assertEquals("createWebsite.title", model.getAttribute("pageTitle"));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
    }

    @Test
    void anEmptyHandleIsRefusedInsteadOfCreatingAnUnreachableWeblog() throws Exception {
        // An empty handle used to sail through validation and produce a weblog
        // whose URL is the site root, with no way to reach or fix it.
        CreateWeblogBean bean = validBean("");

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertEquals(List.of("createWeblog.error.invalidHandle"), ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void aMissingHandleIsRefusedInsteadOfFailingWithAServerError() throws Exception {
        // A post without the field at all (an old bookmark, a script) reached
        // CharSetUtils.keep(null, ...) and died with a NullPointerException.
        CreateWeblogBean bean = validBean(null);

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertEquals(List.of("createWeblog.error.invalidHandle"), ControllerTestFixture.errors(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void aHandleSomebodyElseAlreadyTookIsRefusedAndCleared() throws Exception {
        CreateWeblogBean bean = validBean("taken");
        when(weblogger.weblogManager().getWeblogByHandle("taken", null)).thenReturn(new Weblog());

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertEquals(List.of("createWeblog.error.handleExists"), ControllerTestFixture.errors(model));
        assertNull(bean.getHandle(), "the taken handle is cleared so the user has to choose another");
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void aFailedHandleLookupStopsTheCreateRatherThanRiskingADuplicate() throws Exception {
        CreateWeblogBean bean = validBean("travelguide");
        when(weblogger.weblogManager().getWeblogByHandle("travelguide", null))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertFalse(ControllerTestFixture.errors(model).isEmpty());
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    /**
     * The form check can be bypassed by posting straight to the save URL, so
     * save refuses on its own when creation is switched off. Characterisation
     * of the shipped save: expected to pass at once.
     */
    @Test
    void withWeblogCreationSwitchedOffASaveIsRefusedToo() throws Exception {
        allowWeblogCreation(false);

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model,
                validBean("travelguide"), redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertEquals(List.of("createWebsite.disabled"), ControllerTestFixture.flashErrors(redirectAttributes));
        assertEquals(List.of(), ControllerTestFixture.flashMessages(redirectAttributes));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void withGroupBloggingOffASecondWeblogCannotBeSavedEither() throws Exception {
        // The form check can be bypassed by posting straight to the save URL.
        ControllerTestFixture.setConfigProperty("groupblogging.enabled", "false");
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user))
                .thenReturn(List.of(new WeblogPermission()));
        CreateWeblogBean bean = validBean("second");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertEquals(List.of("createWebsite.oneBlogLimit"), ControllerTestFixture.flashErrors(redirectAttributes));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void aFailedWriteLeavesTheUserOnTheFormWithoutAConfirmation() throws Exception {
        CreateWeblogBean bean = validBean("travelguide");
        doThrow(new WebloggerException("database down")).when(weblogger.weblogManager()).addWeblog(any());

        String view = controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(".CreateWeblog", view);
        assertFalse(ControllerTestFixture.errors(model).isEmpty());
        assertEquals(List.of(), ControllerTestFixture.flashMessages(redirectAttributes));
    }

    @Test
    void aReservedHandleMarksTheHandleFieldAndCreatesNothing() throws Exception {
        CreateWeblogBean bean = validBean("api");

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of("createWeblog.error.reservedHandle"), ControllerTestFixture.errors(model));
        assertEquals(List.of("handle"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void theSitewideFrontpageThemeMarksTheThemeField() throws Exception {
        CreateWeblogBean bean = validBean("travelguide");
        bean.setTheme("frontpage");

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of("theme"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    @Test
    void aFailedMembershipLookupOnSaveCreatesNothing() throws Exception {
        ControllerTestFixture.setConfigProperty("groupblogging.enabled", "false");
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.save(ControllerTestFixture.requestFor(user), model,
                validBean("travelguide"), redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.flashErrors(redirectAttributes));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    // --- helpers ---

    private void allowWeblogCreation(boolean allowed) throws WebloggerException {
        runtimeProperty("site.allowUserWeblogCreation", Boolean.toString(allowed));
    }

    private void runtimeProperty(String name, String value) throws WebloggerException {
        when(weblogger.propertiesManager().getProperty(name))
                .thenReturn(new RuntimeConfigProperty(name, value));
    }

    private static CreateWeblogBean validBean(String handle) {
        CreateWeblogBean bean = new CreateWeblogBean();
        bean.setHandle(handle);
        bean.setName("A weblog");
        bean.setTheme("journal");
        bean.setLocale("en_US");
        bean.setTimeZone("Europe/Berlin");
        return bean;
    }

    private static User user(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    // --- errors point at the field they name (B8) ---

    /** Characterisation: pins the page before the rules move. */
    @Test
    void aTakenHandleMarksTheHandleFieldAndIsCleared() throws Exception {
        CreateWeblogBean bean = validBean("taken");
        when(weblogger.weblogManager().getWeblogByHandle("taken", null)).thenReturn(new Weblog());

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of("handle"), ControllerTestFixture.invalidFields(model));
        assertNull(bean.getHandle());
    }

    @Test
    void aRefusedHandleMarksTheHandleField() throws Exception {
        CreateWeblogBean bean = validBean("my blog!");

        controller.save(ControllerTestFixture.requestFor(user("jake")), model, bean, redirectAttributes);

        assertEquals(List.of("handle"), ControllerTestFixture.invalidFields(model));
    }
}
