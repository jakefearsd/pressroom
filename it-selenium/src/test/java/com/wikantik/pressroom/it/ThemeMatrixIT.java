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

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bundled theme rendering one rich entry, in a real browser.
 *
 * <p>The bundled themes have unit rendering tests pinning their markup and
 * CSP strings, and nothing else. Those tests compare strings; they cannot see
 * a stylesheet that 404s, a webjar path that moved, or a script that throws
 * on load. {@link BrowserHealth} can, and does so for every page this test
 * opens.
 *
 * <p>The fixture entry carries {@code [image]}, {@code [gallery]}, {@code [map]}
 * and {@code [faq]}, so each theme has to cope with a responsive picture, a
 * justified grid with its PhotoSwipe lightbox, a Leaflet map, and an FAQ list —
 * the four surfaces where a theme's head and a shortcode's markup have to agree.
 *
 * <p><b>One test, not one per theme.</b> The fixture costs roughly nine seconds
 * to build (log in, enable uploads, upload an image, create a weblog, publish);
 * one test method per theme would pay that repeatedly for a few seconds of
 * assertion each. Failures are collected per theme and reported together, so a
 * theme that breaks does not hide the state of the ones after it.
 *
 * <p>This test owns its weblog. Switching the seeded one would change the
 * rendering under every other browser test in the suite.
 */
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class ThemeMatrixIT extends PressroomIT {

    /**
     * A bundled theme and the markup that identifies each of its two pages.
     *
     * <p>Separate selectors because a theme renders its home page and its
     * permalink through different templates ({@code weblog.vm} vs
     * {@code permalink.vm}), and only asserting on shared page furniture
     * would let a broken permalink template pass. Every bundled theme marks
     * its entry page distinctly, so each permalink selector names markup the
     * home page does not carry.
     *
     * @param home      matches something only this theme's home page emits
     * @param permalink matches something only this theme's entry page emits
     */
    private record BundledTheme(String id, String home, String permalink) {
    }

    /**
     * The themes offered to an author, in switch order.
     *
     * <p>{@code frontpage} is deliberately absent: it renders through
     * {@code $site}, the site-wide model, which exists only for the weblog
     * named by {@code site.frontpage.weblog.handle}. It is a site front page
     * that happens to live in the themes directory, not a theme an author can
     * wear.
     */
    private static final List<BundledTheme> THEMES = List.of(
            new BundledTheme("journal", "body.qj .qj-entries", "body.qj main.qj-main-entry"),
            new BundledTheme("portfolio", "body.pf .pf-header", "body.pf main.pf-main-entry"),
            new BundledTheme("travel", "body.tg .tg-header", "body.tg main.tg-main-entry"));

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    @Test
    void everyBundledThemeRendersARichEntryWithoutBrokenAssets() {
        String suffix = nonce();
        String title = "Theme matrix " + suffix;

        loginAsAdmin();
        boolean uploadsWere = setGlobalFlag("uploads.enabled", true);
        try {
            // Created on the LAST theme in the list so that every iteration
            // below is a genuine change. The theme editor keeps its Save button
            // hidden until its JS decides something changed, so re-selecting the
            // theme already in force would hang waiting for a control that never
            // appears.
            String handle = createWeblog(lastTheme());
            String mediaFileId = uploadImage(handle);
            String permalink = publishRichEntry(handle, title, mediaFileId);

            List<String> failures = new ArrayList<>();
            for (BundledTheme theme : THEMES) {
                try {
                    switchToSharedTheme(theme.id(), handle);
                    checkFrontPage(theme, handle, title);
                    checkPermalink(theme, permalink);
                } catch (Throwable failure) {
                    failures.add(theme.id() + " -> " + summarise(failure) + whereWeWere());
                }
            }

            assertTrue(failures.isEmpty(),
                    "themes failed to render the fixture entry:\n  "
                            + String.join("\n  ", failures));
        } finally {
            setGlobalFlag("uploads.enabled", uploadsWere);
            logout();
        }
    }

    // ------------------------------------------------------------- assertions

    /**
     * The weblog home page: the theme's own {@code weblog.vm} plus its day
     * template.
     */
    private void checkFrontPage(BundledTheme theme, String handle, String title) {
        openPath("/" + handle + "/");
        $(theme.home()).should(exist);
        // Themes wrap the title in different elements, so this asserts on the
        // rendered text rather than on any one theme's markup.
        $("body").shouldHave(text(title));
    }

    /**
     * The permalink page, which every theme renders through a different
     * template ({@code permalink.vm} or {@code entry.vm}) than the home page —
     * and which is the only page guaranteed to carry the entry's full body,
     * whatever a theme chooses to summarise on its front page.
     */
    private void checkPermalink(BundledTheme theme, String permalink) {
        Selenide.open(permalink);
        BrowserHealth.current().settle();

        $(theme.permalink()).should(exist);

        // The four shortcode surfaces. Each is emitted by shared code but
        // styled and scripted by the theme's head, which is where they break.
        $("figure.shortcode-image picture img").should(exist);
        $(".jgrid figure").should(exist);
        $(".travel-map").should(exist);
        $("dl.faq dt").should(exist);
    }

    // ---------------------------------------------------------------- fixture

    /** Creates a weblog owned by the seeded admin on the named theme. */
    private String createWeblog(String themeId) {
        String handle = "thememx" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Theme Matrix " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue(themeId);
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /**
     * Uploads the bundled fixture image and returns its media-file id.
     *
     * <p>No rename needed, unlike {@code GalleryIT}: this weblog is new, so its
     * default directory cannot already hold a {@code hawk.jpg} to collide with.
     */
    private String uploadImage(String handle) {
        openPath("/roller-ui/authoring/mediaFileAdd.rol?weblog=" + handle);
        $("#uploadedFiles").should(exist).uploadFile(testImage());
        $("#uploadButton").click();

        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        String mediaFileId = $("input[name='selectedImages']").should(exist).getValue();
        assertNotNull(mediaFileId, "the upload success page must carry the new file's id");
        return mediaFileId;
    }

    /**
     * Publishes one entry exercising all four shortcodes, and returns its
     * permalink.
     */
    private String publishRichEntry(String handle, String title, String mediaFileId) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("<p>Fixture body for the theme matrix.</p>"
                        + "<p>[image id=\"" + mediaFileId + "\" alt=\"A hawk\""
                        + " caption=\"Hawk over the valley\"]</p>"
                        + "<p>[gallery dir=\"default\"]</p>"
                        + "<p>[map][pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"][/map]</p>"
                        + "<p>[faq][q]How do I get there?[/q][a]By train.[/a][/faq]</p>");

        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);

        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /**
     * Picks a different shared theme and saves.
     *
     * <p>The Save button lives in a block the page keeps hidden until its JS
     * believes something changed, so selecting the theme is what reveals it.
     * Every call here is a real change — see the ordering note at the call
     * site — so the button always appears.
     */
    private void switchToSharedTheme(String themeId, String handle) {
        openPath("/roller-ui/authoring/themeEdit.rol?weblog=" + handle);
        // Clicking the theme's own card reveals the Save button. The
        // shared/custom radio row is not rendered on a default install (custom
        // themes are off site-wide), so there is nothing else to click first.
        $("label.theme-card input[value='" + themeId + "']").should(visible).click();

        $("#sharedChangeToShared").shouldBe(visible);
        $("#sharedChangeToShared button[type='submit']").click();

        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    // ------------------------------------------------------------- small bits

    private static String lastTheme() {
        return THEMES.get(THEMES.size() - 1).id();
    }

    private static File testImage() {
        URL resource = ThemeMatrixIT.class.getResource("/hawk.jpg");
        assertNotNull(resource, "hawk.jpg must be on the it-selenium test classpath");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve test image", e);
        }
    }

    /**
     * The page the browser was actually on when a check failed.
     *
     * <p>A bare "element not found" says nothing about which of the two pages
     * was open or what it rendered instead, and re-running to find out costs a
     * whole fixture rebuild.
     */
    private static String whereWeWere() {
        try {
            String body = $("body").getText().replaceAll("\\s+", " ").trim();
            if (body.length() > 200) {
                body = body.substring(0, 200) + "...";
            }
            return "\n      at " + WebDriverRunner.url() + "\n      rendered: <<" + body + ">>";
        } catch (Throwable unreadable) {
            return "\n      (page unreadable: " + unreadable.getClass().getSimpleName() + ")";
        }
    }

    /** One readable line from a failure, so the collected report stays legible. */
    private static String summarise(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String firstLine = message.lines().findFirst().orElse(message).trim();
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "...";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
