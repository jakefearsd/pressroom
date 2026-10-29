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
package com.wikantik.pressroom.it;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching a weblog's theme.
 *
 * <p>The theme is the one setting that changes every public page at once, and
 * switching to a custom theme is destructive in a way nothing else in the admin
 * UI is: it copies the shared theme's templates into the weblog, after which
 * the weblog owns them and shared-theme updates no longer reach it. Nothing
 * covered it.
 *
 * <p>Every test here creates its own weblog. Switching the shared fixture to a
 * custom theme would change the rendering of every other browser test in the
 * suite, and there is no clean way back -- the import is one-way.
 */
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class ThemeIT extends PressroomIT {

    /** Site-wide switch for whether a weblog may adopt a custom theme. */
    private static final String CUSTOM_THEMES_ALLOWED = "themes.customtheme.allowed";

    /** Distinctive markup from the journal theme's weblog.vm. */
    private static final String JOURNAL_MARKER = "qj-entries";

    /** Distinctive markup from the travel theme's weblog.vm. */
    private static final String TRAVEL_MARKER = "tg-header";

    /**
     * The reader's view has to follow the switch.
     *
     * <p>Reading the page BEFORE the switch is load-bearing: it puts the old
     * theme's rendering into WeblogPageCache, so the second read only sees the
     * new theme if that cache actually expired. It has no CacheHandler, so
     * CacheManager.invalidate(weblog) does not reach it -- what saves this is
     * saveWeblog bumping lastModified, which the lazy expiry checks. Skip the
     * first read and this passes without proving any of that.
     */
    @Test
    void switchingSharedThemesChangesWhatReadersSee() {
        String handle = createWeblogWithTheme("journal");

        String before = readerView(handle);
        assertTrue(before.contains(JOURNAL_MARKER),
                "the new weblog must render with the theme it was created with, got: "
                        + snippet(before));

        switchToSharedTheme("travel", handle);

        String after = readerView(handle);
        assertTrue(after.contains(TRAVEL_MARKER),
                "a reader must get the newly chosen theme's markup, got: " + snippet(after));
        assertFalse(after.contains(JOURNAL_MARKER),
                "and must not still be getting the old theme's");
        logout();
    }

    /**
     * Customising a shared theme copies its templates onto the weblog.
     *
     * <p>That copy is the whole point of the operation and it is also the
     * irreversible part: afterwards the weblog owns the templates and stops
     * tracking the shared theme. Before it, the weblog has no templates of its
     * own at all -- the list on the Templates page is built from the weblog's
     * own rows, not the theme's.
     */
    @Test
    void switchingToACustomThemeCopiesTheSharedTemplatesIntoTheWeblog() {
        String handle = createWeblogWithTheme("journal");

        openPath(templatesPath(handle));
        assertTrue(templateNames().isEmpty(),
                "a weblog on a shared theme owns no templates yet, found: " + templateNames());

        boolean wasAllowed = setGlobalFlag(CUSTOM_THEMES_ALLOWED, true);
        try {
            switchToCustomTheme(handle);

            openPath(templatesPath(handle));
            String names = templateNames();
            assertFalse(names.isEmpty(),
                    "customising must copy the shared theme's templates onto the weblog");
            assertTrue(names.contains("Weblog"),
                    "the theme's main Weblog template must be among them: " + names);

            // And the blog still renders -- a copied theme that does not serve
            // is worse than no copy at all.
            String rendered = readerView(handle);
            assertTrue(rendered.contains(JOURNAL_MARKER),
                    "the weblog must still render after being customised, got: "
                            + snippet(rendered));
        } finally {
            setGlobalFlag(CUSTOM_THEMES_ALLOWED, wasAllowed);
            logout();
        }
    }

    /**
     * The theme editor must report the theme actually in force, not the one
     * that was chosen last. This reopens the page after the switch, which is
     * what an author does when they come back to check.
     */
    @Test
    void theThemeEditorComesBackShowingTheThemeInForce() {
        String handle = createWeblogWithTheme("journal");

        switchToSharedTheme("travel", handle);

        openPath(themeEditPath(handle));
        $("label.theme-card input[value='travel']").shouldBe(checked);
        // No type chooser at all while custom themes are off site-wide.
        $("#customRadio").shouldNot(exist);

        boolean wasAllowed = setGlobalFlag(CUSTOM_THEMES_ALLOWED, true);
        try {
            switchToCustomTheme(handle);

            openPath(themeEditPath(handle));
            $("#customRadio").shouldBe(checked);
        } finally {
            setGlobalFlag(CUSTOM_THEMES_ALLOWED, wasAllowed);
            logout();
        }
    }

    /**
     * With custom themes switched off site-wide, the weblog must stay on its
     * shared theme -- and not merely be told so.
     *
     * <p>The setting used to gate only the Design tab in the menu, so a POST
     * straight to {@code themeEdit!save.rol} converted the weblog anyway. That
     * import is one-way, which made a hidden menu entry the only thing between
     * an installation that had turned the option off and a weblog that could
     * never go back. This posts the way that page's own form does and then
     * checks the weblog, not the wording.
     */
    @Test
    void aCustomThemeIsRefusedWhenTheSiteHasThemTurnedOff() {
        String handle = createWeblogWithTheme("journal");

        boolean wasAllowed = setGlobalFlag(CUSTOM_THEMES_ALLOWED, false);
        try {
            postCustomThemeSwitch(handle);

            // The weblog is still on the shared theme it started with, which
            // only its own rendering can show: a custom copy would serve the
            // same markup until someone edited it.
            openPath(templatesPath(handle));
            assertTrue(templateNames().isEmpty(),
                    "a refused switch must not have copied any templates onto the weblog, found: "
                            + templateNames());

            openPath(themeEditPath(handle));
            // The whole type-chooser row is omitted when there is nothing to
            // choose between -- it used to render a lone "Shared theme" radio,
            // already selected, beside an empty column.
            $("#sharedRadio").shouldNot(exist);
            $("#customRadio").shouldNot(exist);
            $(".theme-cards").should(exist);
        } finally {
            setGlobalFlag(CUSTOM_THEMES_ALLOWED, wasAllowed);
            logout();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Creates a weblog owned by the seeded admin, on the named shared theme,
     * and returns its handle.
     */
    private String createWeblogWithTheme(String themeId) {
        String handle = "theme" + nonce();

        loginAsAdmin();
        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Theme Blog " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue(themeId);
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /**
     * Picks a different shared theme and saves.
     *
     * <p>The Save button lives in a block the page keeps hidden until it
     * believes something changed, so selecting the theme is what reveals it --
     * going straight for the button would find it invisible.
     */
    private void switchToSharedTheme(String themeId, String handle) {
        openPath(themeEditPath(handle));
        // Clicking the theme's own card is what reveals the Save button, and
        // it is now the ONLY way in on a default install: with
        // themes.customtheme.allowed off there is nothing to choose between,
        // so the shared/custom radio row is not rendered at all.
        $("label.theme-card input[value='" + themeId + "']").should(visible).click();

        $("#sharedChangeToShared").shouldBe(visible);
        $("#sharedChangeToShared button[type='submit']").click();

        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    /**
     * Posts the theme form asking for a custom theme, the way somebody who
     * knows the URL would.
     *
     * <p>Deliberately not through the radio button: with the option off the
     * page no longer offers one, and that hidden control was exactly what used
     * to be doing the "enforcing". The form is otherwise real -- same action,
     * same session, and the page's own CSRF token and weblog parameter ride
     * along with it.
     */
    private void postCustomThemeSwitch(String handle) {
        openPath(themeEditPath(handle));
        // SETS the themeType the page already carries rather than appending a
        // second one: with custom themes off the form ships a hidden
        // themeType=shared, and two parameters of the same name would bind the
        // FIRST -- so the POST would quietly be an ordinary shared-theme save
        // and this test would pass without ever exercising the refusal.
        //
        // Awaited, not settled: form.submit() starts the navigation in a task of its
        // own, and a templates check that overtook the POST would pass on a weblog
        // nobody had yet asked to switch.
        awaitNewPageAfter(() -> executeJavaScript(
                "var f = document.querySelector(\"form[action$='themeEdit!save.rol']\");"
                        + "var t = f.querySelector(\"input[name='themeType']\");"
                        + "if (!t) { t = document.createElement('input');"
                        + "  t.type = 'hidden'; t.name = 'themeType'; f.appendChild(t); }"
                        + "t.value = 'custom'; f.submit();"));
    }

    /** Switches to a custom theme, importing the current shared theme. */
    private void switchToCustomTheme(String handle) {
        openPath(themeEditPath(handle));
        $("#customRadio").should(visible).click();

        $("#sharedChangeToCustom").shouldBe(visible);
        $("#sharedChangeToCustom button[type='submit']").click();

        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    /**
     * The weblog homepage as an anonymous reader gets it.
     *
     * <p>Trailing slash: the bare handle redirects, and the anonymous fetch
     * helper does not follow redirects, so without it this reads an empty body
     * and every content assertion fails for the wrong reason.
     */
    private String readerView(String handle) {
        return getAnonymously(baseUrl() + "/" + handle + "/");
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }

    private String templatesPath(String handle) {
        return "/roller-ui/authoring/templates.rol?weblog=" + handle;
    }

    private String themeEditPath(String handle) {
        return "/roller-ui/authoring/themeEdit.rol?weblog=" + handle;
    }

    private String templateNames() {
        $("table").should(exist);
        return $$("a[href*='templateEdit.rol']").texts().toString()
                .replace("[", "").replace("]", "");
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
