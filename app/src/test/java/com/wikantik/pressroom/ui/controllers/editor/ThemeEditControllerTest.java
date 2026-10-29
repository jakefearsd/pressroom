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

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ThemeEditController}.
 *
 * <p>{@code save} folds two very different flows into one handler: adopting a
 * custom theme (optionally importing a shared theme's templates as the
 * starting point) and switching to a different shared theme outright. Both
 * paths have to clean up a custom stylesheet override left behind by the
 * theme being replaced, and the "did the theme actually change" check governs
 * whether a success message appears at all — getting it backwards would
 * either spam a no-op save or silently swallow a real one.
 */
class ThemeEditControllerTest extends EditorControllerTestSupport {

    private ThemeEditController controller;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new ThemeEditController());
        model = newModel();
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of());

        // Most tests here exercise what the theme switch DOES, which presumes
        // the installation permits custom themes at all. The refusal when it
        // does not is its own group of tests below.
        givenRuntimeProperty("themes.customtheme.allowed", "true");

        // The controller resolves the current theme through its injected
        // facade's ThemeManager. save() does so unconditionally via
        // isSharedThemeCustomStylesheet() before even branching on themeType, so
        // every test needs a non-null default here or it NPEs before the
        // behaviour under test ever runs. Tests that care about the current
        // theme's id, name or stylesheet override this with their own stub.
        WeblogTheme defaultCurrentTheme = mock(WeblogTheme.class);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(defaultCurrentTheme);
    }

    // --- execute ---

    @Test
    void executeOnASharedThemeNamesTheCurrentThemeForTheForm() throws Exception {
        // ThemeEdit.jsp shows "Your current theme: <name>" from a model
        // attribute the controller resolves through its ThemeManager -- the
        // raw entity no longer knows its theme (plan Task 17).
        weblog.setEditorTheme("journal");
        WeblogTheme journal = mock(WeblogTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(journal.getName()).thenReturn("Quiet Journal");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(journal);

        String view = controller.execute(request, model);

        assertEquals(".ThemeEdit", view);
        assertEquals("journal", model.getAttribute("themeId"));
        assertEquals("Quiet Journal", model.getAttribute("currentThemeName"));
    }

    @Test
    void executeOnACustomThemeLeavesTheThemeIdsUnsetAndSkipsImport() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        String view = controller.execute(request, model);

        assertEquals(".ThemeEdit", view);
        assertTrue(model.containsAttribute("themeId"));
        assertNull(model.getAttribute("themeId"));
        assertNull(model.getAttribute("selectedThemeId"));
        assertEquals(Boolean.FALSE, model.getAttribute("importTheme"));
    }

    @Test
    void executeOnASharedThemePopulatesTheThemeIdFromTheCurrentTheme() throws Exception {
        weblog.setEditorTheme("shared-1");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        controller.execute(request, model);

        assertEquals("shared-1", model.getAttribute("themeId"));
        assertEquals("shared-1", model.getAttribute("selectedThemeId"));
    }

    @Test
    void executeAlwaysPopulatesTheEnabledThemesListFromTheThemeManager() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        SharedTheme theme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of(theme));

        controller.execute(request, model);

        assertEquals(List.of(theme), model.getAttribute("themes"));
    }

    // --- frontpage exclusion (Bug 8) ---
    //
    // "frontpage" is the $site-wide aggregator theme (renders the multi-blog
    // directory named by site.frontpage.weblog.handle, not an individual
    // weblog) and breaks a normal weblog that adopts it, so the picker must
    // not offer it -- except to a weblog that is already on it, which must
    // not be stranded with no way to keep (or leave) its current theme.

    @Test
    void executeExcludesTheFrontpageThemeWhenItIsNotTheCurrentTheme() throws Exception {
        weblog.setEditorTheme("journal");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("journal");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        SharedTheme frontpage = mock(SharedTheme.class);
        when(frontpage.getId()).thenReturn("frontpage");
        SharedTheme journal = mock(SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of(frontpage, journal));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        List<SharedTheme> themes = (List<SharedTheme>) model.getAttribute("themes");
        assertEquals(List.of(journal), themes,
                "frontpage must not be offered to a weblog that is not already using it");
    }

    @Test
    void executeKeepsTheFrontpageThemeWhenItIsTheWeblogsCurrentTheme() throws Exception {
        weblog.setEditorTheme("frontpage");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("frontpage");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        SharedTheme frontpage = mock(SharedTheme.class);
        when(frontpage.getId()).thenReturn("frontpage");
        SharedTheme journal = mock(SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of(frontpage, journal));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        List<SharedTheme> themes = (List<SharedTheme>) model.getAttribute("themes");
        assertEquals(List.of(frontpage, journal), themes,
                "a weblog already on frontpage must not be stranded with no way to keep or "
                        + "leave it");
    }

    @Test
    void executeExcludesTheFrontpageThemeWhenTheWeblogIsOnACustomTheme() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        SharedTheme frontpage = mock(SharedTheme.class);
        when(frontpage.getId()).thenReturn("frontpage");
        SharedTheme journal = mock(SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of(frontpage, journal));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        List<SharedTheme> themes = (List<SharedTheme>) model.getAttribute("themes");
        assertEquals(List.of(journal), themes,
                "a weblog on a custom theme has no current SHARED theme id to grandfather "
                        + "frontpage against");
    }

    @Test
    void executeFlagsFirstCustomizationWhenNoWeblogTemplateExistsYet() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(null);

        controller.execute(request, model);

        assertEquals(Boolean.TRUE, model.getAttribute("firstCustomization"));
    }

    @Test
    void executeDoesNotFlagFirstCustomizationWhenAWeblogTemplateAlreadyExists() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(new WeblogTemplate());

        controller.execute(request, model);

        assertEquals(Boolean.FALSE, model.getAttribute("firstCustomization"));
    }

    @Test
    void executeLeavesFirstCustomizationAbsentWhenTheLookupFails() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenThrow(new WebloggerException("database down"));

        controller.execute(request, model);

        assertFalse(model.containsAttribute("firstCustomization"),
                "The lookup exception happens while evaluating the addAttribute argument, so the "
                        + "attribute must never be set at all, not merely null");
    }

    // --- save: themes.customtheme.allowed ---
    //
    // The setting gates the Design tab in editor-menu.xml and the customise
    // link on the main menu, and gated nothing else: posting straight to
    // themeEdit!save.rol switched the weblog to a custom theme whatever the
    // setting said. That import is one-way, so a hidden menu entry was the
    // only thing standing between an installation that had turned the option
    // off and a weblog that could never go back to a shared theme.

    @Test
    void saveToCustomIsRefusedWhenTheInstallationDoesNotAllowCustomThemes() throws Exception {
        givenRuntimeProperty("themes.customtheme.allowed", "false");
        weblog.setEditorTheme("shared-1");

        String view = controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        assertEquals(".ThemeEdit", view);
        assertEquals("shared-1", weblog.getEditorTheme(), "the theme must be left alone");
        verify(weblogger.getThemeManager(), never())
                .importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(weblog);
        assertEquals(List.of("themeEditor.error.customThemeNotAllowed"), errors(model));
    }

    /**
     * Turning the option off stops new customisations; it must not strand a
     * weblog that was customised while it was on. There is no way back from a
     * custom theme, so refusing here would leave that weblog unable to save
     * its own theme page at all.
     */
    @Test
    void aWeblogAlreadyOnACustomThemeCanStillSaveWhenTheOptionIsOff() throws Exception {
        givenRuntimeProperty("themes.customtheme.allowed", "false");
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        controller.save(request, model, WeblogTheme.CUSTOM, null, false);

        assertEquals(List.of(), errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    /**
     * The refusal is scoped to the custom switch. Switching between shared
     * themes is unaffected by the setting -- it is what the setting leaves you.
     */
    @Test
    void switchingBetweenSharedThemesIsUnaffectedByTheOption() throws Exception {
        givenRuntimeProperty("themes.customtheme.allowed", "false");
        weblog.setEditorTheme("shared-1");
        SharedTheme target = mock(SharedTheme.class);
        when(target.getName()).thenReturn("Gaurav");
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(target);

        controller.save(request, model, "shared", "shared-2", false);

        assertEquals(List.of(), errors(model));
        assertEquals("shared-2", weblog.getEditorTheme());
    }

    @Test
    void theViewIsToldWhetherCustomThemesAreAllowedSoItCanHideTheChoice() throws Exception {
        givenRuntimeProperty("themes.customtheme.allowed", "false");
        weblog.setEditorTheme("shared-1");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        controller.execute(request, model);

        assertEquals(Boolean.FALSE, model.getAttribute("customThemeAllowed"));
    }

    // --- the site space (site pages, spec "Calls I made") ---
    //
    // The site space renders with one of the four site themes and never a
    // custom one; an ordinary weblog is never offered frontpage. The picker
    // offers only what is allowed, and the save path checks the same rule,
    // since a hidden choice stops nobody who posts the form directly.

    /** The enabled themes, in the order the manager lists them, each resolvable by id. */
    private void givenEnabledThemes(String... ids) {
        List<SharedTheme> themes = new java.util.ArrayList<>();
        for (String id : ids) {
            SharedTheme theme = mock(SharedTheme.class);
            when(theme.getId()).thenReturn(id);
            when(theme.getName()).thenReturn(id);
            themes.add(theme);
            try {
                when(weblogger.getThemeManager().getTheme(id)).thenReturn(theme);
            } catch (WebloggerException e) {
                throw new IllegalStateException(e);
            }
        }
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(themes);
    }

    @SuppressWarnings("unchecked")
    private List<String> offeredThemeIds() {
        return ((List<SharedTheme>) model.getAttribute("themes")).stream().map(SharedTheme::getId).toList();
    }

    @Test
    void theSiteSpaceIsOfferedExactlyTheFourSiteThemes() throws Exception {
        weblog.setSiteSpace(true);
        weblog.setEditorTheme("journal");
        givenEnabledThemes("frontpage", "journal", "legacy", "portfolio", "travel");

        controller.execute(request, model);

        assertEquals(List.of("frontpage", "journal", "portfolio", "travel"), offeredThemeIds());
        assertEquals(Boolean.FALSE, model.getAttribute("customThemeAllowed"),
                "the site space is never offered a custom theme");
    }

    @Test
    void anOrdinaryWeblogIsNotOfferedFrontpage() throws Exception {
        weblog.setEditorTheme("journal");
        givenEnabledThemes("frontpage", "journal", "portfolio", "travel");

        controller.execute(request, model);

        assertEquals(List.of("journal", "portfolio", "travel"), offeredThemeIds());
    }

    @Test
    void postingFrontpageForAnOrdinaryWeblogNotAlreadyOnItIsRefused() throws Exception {
        weblog.setEditorTheme("journal");
        givenEnabledThemes("frontpage", "journal");

        controller.save(request, model, "shared", "frontpage", false);

        assertEquals(List.of("themeEditor.error.notOffered"), errors(model));
        assertEquals("journal", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anOrdinaryWeblogAlreadyOnFrontpageMayStillSaveIt() throws Exception {
        weblog.setEditorTheme("frontpage");
        WeblogTheme current = mock(WeblogTheme.class);
        when(current.getId()).thenReturn("frontpage");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(current);
        givenEnabledThemes("frontpage", "journal");

        controller.save(request, model, "shared", "frontpage", false);

        assertEquals(List.of(), errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void postingACustomThemeForTheSiteSpaceIsRefusedEvenWhenTheOptionIsOn() throws Exception {
        weblog.setSiteSpace(true);
        weblog.setEditorTheme("frontpage");
        givenEnabledThemes("frontpage", "journal");

        controller.save(request, model, WeblogTheme.CUSTOM, "journal", true);

        assertEquals(List.of("themeEditor.error.notOffered"), errors(model));
        assertEquals("frontpage", weblog.getEditorTheme());
        verify(weblogger.getThemeManager(), never())
                .importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void postingANonSiteThemeForTheSiteSpaceIsRefused() throws Exception {
        weblog.setSiteSpace(true);
        weblog.setEditorTheme("frontpage");
        givenEnabledThemes("frontpage", "legacy");

        controller.save(request, model, "shared", "legacy", false);

        assertEquals(List.of("themeEditor.error.notOffered"), errors(model));
        assertEquals("frontpage", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void theSiteSpaceMovesBetweenSiteThemes() throws Exception {
        weblog.setSiteSpace(true);
        weblog.setEditorTheme("frontpage");
        givenEnabledThemes("frontpage", "journal");

        controller.save(request, model, "shared", "journal", false);

        assertEquals(List.of(), errors(model));
        assertEquals("journal", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    // --- save: themeType=custom ---

    @Test
    void saveToCustomWithoutImportSetsTheThemeAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");

        String view = controller.save(request, model, WeblogTheme.CUSTOM, null, false);

        assertEquals(".ThemeEdit", view);
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0);
        assertEquals(List.of("themeEditor.setCustomTheme.enabled",
                        "themeEditor.setCustomTheme.instructions"),
                messages(model),
                "Switching to a custom theme used to be reported through setTheme.success "
                        + "with the literal constant \"custom\" as the theme name, so the banner "
                        + "read \"Theme set to custom\" -- an internal enum value shown to an author.");
    }

    @Test
    void switchingToACustomThemeNeverShowsTheInternalCustomConstantAsAThemeName() throws Exception {
        registerMessage("themeEditor.setTheme.success", "now:{0}");
        weblog.setEditorTheme("shared-1");

        controller.save(request, model, WeblogTheme.CUSTOM, null, false);

        assertFalse(messages(model).contains("now:" + WeblogTheme.CUSTOM),
                "The WeblogTheme.CUSTOM constant is a storage value, not a theme name: "
                        + messages(model));
    }

    @Test
    void saveToCustomWithImportPullsInTheSharedThemeAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(sourceTheme.getName()).thenReturn("Rounders 3.0");
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);
        registerMessage("themeEditor.setCustomTheme.success", "imported:{0}");

        String view = controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        assertEquals(".ThemeEdit", view);
        verify(weblogger.getThemeManager()).importTheme(weblog, sourceTheme, false);
        assertTrue(messages(model).contains("imported:Rounders 3.0"), "Got: " + messages(model));
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void saveToCustomSkipsTheStylesheetWhenImportingTheSameThemeThatHadACustomStylesheetOverride() throws Exception {
        // sharedThemeCustomStylesheet is computed from the weblog's state as
        // save() is entered, before editorTheme is reassigned to CUSTOM below.
        weblog.setEditorTheme("shared-1");
        ThemeTemplate stylesheetTemplate = mock(ThemeTemplate.class);
        when(stylesheetTemplate.getLink()).thenReturn("style.css");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getStylesheet()).thenReturn(stylesheetTemplate);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, "style.css"))
                .thenReturn(new WeblogTemplate());

        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(sourceTheme.getName()).thenReturn("Rounders 3.0");
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);

        controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        verify(weblogger.getThemeManager()).importTheme(weblog, sourceTheme, true);
    }

    @Test
    void saveToCustomWithImportButNoSelectedThemeIdSkipsTheImport() throws Exception {
        weblog.setEditorTheme("shared-1");

        controller.save(request, model, WeblogTheme.CUSTOM, "", true);

        verify(weblogger.getThemeManager(), never()).importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme(),
                "With no theme selected the import is skipped but the weblog still switches to CUSTOM");
    }

    @Test
    void saveToCustomStopsBeforeSavingWhenTheImportFails() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);
        doThrow(new RuntimeException("import blew up"))
                .when(weblogger.getThemeManager()).importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        String view = controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        assertEquals(".ThemeEdit", view);
        assertTrue(errors(model).contains("generic.error.check.logs"), "Got: " + errors(model));
        assertEquals("shared-1", weblog.getEditorTheme(),
                "A failed import must return early and never reach the CUSTOM reassignment/save");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    // --- save: themeType=shared ---

    @Test
    void saveToADifferentSharedThemeSavesAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme newTheme = mock(SharedTheme.class);
        when(newTheme.getName()).thenReturn("Blogblog");
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(newTheme);
        registerMessage("themeEditor.setTheme.success", "now:{0}");

        String view = controller.save(request, model, "shared", "shared-2", false);

        assertEquals(".ThemeEdit", view);
        assertEquals("shared-2", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0);
        assertTrue(messages(model).contains("now:Blogblog"), "Got: " + messages(model));
    }

    @Test
    void saveToTheSameSharedThemeSavesButReportsNoSuccessMessage() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sameTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sameTheme);

        controller.save(request, model, "shared", "shared-1", false);

        assertEquals("shared-1", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0,
                "The save/flush must still happen even though the theme did not change");
        assertTrue(messages(model).isEmpty(),
                "Re-selecting the current theme must not report a change that did not happen");
    }

    @Test
    void saveToASharedThemeRemovesAStylesheetOverrideLeftByTheOldTheme() throws Exception {
        weblog.setEditorTheme("shared-1");
        ThemeTemplate stylesheetTemplate = mock(ThemeTemplate.class);
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getStylesheet()).thenReturn(stylesheetTemplate);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        WeblogTemplate overrideTemplate = new WeblogTemplate();
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(overrideTemplate);

        SharedTheme newTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(newTheme);

        controller.save(request, model, "shared", "shared-2", false);

        verify(weblogger.getWeblogManager()).removeTemplate(overrideTemplate);
    }

    @Test
    void saveToASharedThemeReportsThemeNotFoundAndSkipsTheSaveEntirely() throws Exception {
        weblog.setEditorTheme("shared-1");
        when(weblogger.getThemeManager().getTheme("missing"))
                .thenThrow(new WebloggerException("no such theme"));

        controller.save(request, model, "shared", "missing", false);

        assertTrue(errors(model).contains("themeEditor.error.notFound"), "Got: " + errors(model));
        assertEquals("shared-1", weblog.getEditorTheme(),
                "A theme lookup failure must leave the weblog's theme untouched");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void saveAlwaysReloadsThemeDataAndRepopulatesTheFormRegardlessOfOutcome() throws Exception {
        weblog.setEditorTheme("shared-1");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);
        when(weblogger.getThemeManager().getTheme("missing"))
                .thenThrow(new WebloggerException("no such theme"));

        String view = controller.save(request, model, "shared", "missing", false);

        assertEquals(".ThemeEdit", view);
        assertTrue(model.containsAttribute("themes"),
                "loadThemeData must run again even on the theme-not-found failure path");
        assertEquals("shared-1", model.getAttribute("selectedThemeId"),
                "The form must be repopulated with the weblog's (unchanged) current theme id");
    }

    // --- save failures: the author is told, and nothing claims success ---

    @Test
    void aFailedSaveWhileSwitchingToCustomReportsAnErrorAndNoSuccessBanner() throws Exception {
        weblog.setEditorTheme("shared-1");
        doThrow(new WebloggerException("store down")).when(weblogger.getWeblogManager()).saveWeblog(weblog);

        String view = controller.save(request, model, WeblogTheme.CUSTOM, null, false);

        assertEquals(".ThemeEdit", view);
        assertTrue(errors(model).contains("generic.error.check.logs"), "Got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "a failed switch must not announce itself: " + messages(model));
    }

    @Test
    void aFailedSaveWhileSwitchingSharedThemesReportsAnErrorAndNoSuccessBanner() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme target = mock(SharedTheme.class);
        when(target.getName()).thenReturn("Other");
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(target);
        doThrow(new WebloggerException("store down")).when(weblogger.getWeblogManager()).saveWeblog(weblog);

        controller.save(request, model, "shared", "shared-2", false);

        assertTrue(errors(model).contains("generic.error.check.logs"), "Got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "Got: " + messages(model));
    }

    @Test
    void aThemeTypeOfNeitherCustomNorSharedChangesNothing() throws Exception {
        weblog.setEditorTheme("shared-1");

        String view = controller.save(request, model, "bogus", "shared-2", false);

        assertEquals(".ThemeEdit", view);
        assertEquals("shared-1", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * The javadoc on {@code currentTheme} promises a half-configured weblog
     * still reaches the form. A weblog whose theme cannot be resolved must
     * therefore open the editor (with no current theme named) rather than die
     * on a null.
     */
    @Test
    void aWeblogWhoseCurrentThemeCannotBeResolvedStillOpensTheForm() throws Exception {
        weblog.setEditorTheme("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenThrow(new WebloggerException("theme gone"));

        String view = controller.execute(request, model);

        assertEquals(".ThemeEdit", view);
        assertNull(model.getAttribute("currentThemeName"));
        assertNull(model.getAttribute("themeId"));
    }

    @Test
    void aWeblogWhoseCurrentThemeCannotBeResolvedCanStillBeMovedToASharedTheme() throws Exception {
        weblog.setEditorTheme("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenThrow(new WebloggerException("theme gone"));
        SharedTheme target = mock(SharedTheme.class);
        when(target.getName()).thenReturn("Other");
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(target);

        String view = controller.save(request, model, "shared", "shared-2", false);

        assertEquals(".ThemeEdit", view);
        assertEquals("shared-2", weblog.getEditorTheme());
        assertTrue(errors(model).isEmpty(), "Got: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }
}
