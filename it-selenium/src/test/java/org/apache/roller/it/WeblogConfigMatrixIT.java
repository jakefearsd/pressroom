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
package org.apache.roller.it;

import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.cssClass;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-weblog settings, each proved by what a reader gets rather than by the
 * checkbox staying ticked.
 *
 * <p>Weblog Settings has around twenty fields, most with no browser coverage
 * before this class existed. A setting that persists but changes nothing is
 * the failure mode this whole page is prone to — the checkbox comes back
 * ticked either way, so only the public side can tell you it worked.
 *
 * <p><b>Every test owns its own weblog</b> and touches no site-wide
 * configuration. That keeps them independent of each other and of the rest of
 * the suite, and it is what would let this class run in parallel if the suite
 * ever does: the global-flag permutations live in a separate class precisely so
 * they can be serialised on their own.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class WeblogConfigMatrixIT extends RollerIT {

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /**
     * The weblog's locale reaches the rendered page.
     *
     * <p>Asserted on the theme's own message lookups rather than on dates: the
     * journal home page's category nav ({@code #showWeblogCategoryLinksList})
     * resolves {@code generic.all} through the bundle for the weblog's locale,
     * so a French weblog says "Tous". A date would work too but drags in
     * month-name and timezone-boundary flakiness for no extra proof.
     */
    @Test
    void theWeblogsLocaleDecidesTheLanguageOfTheRenderedPage() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, "Localised " + suffix);

        // Anchored on the category nav's "All" entry, closing tags included,
        // rather than a bare word search: the page carries other hardcoded
        // English elsewhere (feed titles, meta lines) that a looser match
        // could accidentally hit whatever the locale.
        String englishLabel = ">All</a></li>";
        String frenchLabel = ">Tous</a></li>";

        String english = readerHome(handle);
        assertTrue(english.contains(englishLabel),
                "the default weblog must render the English bundle, got: " + snippet(english));

        selectOption(handle, "bean.locale", "fr");

        String french = readerHome(handle);
        assertTrue(french.contains(frenchLabel),
                "a French weblog must resolve theme messages through the French bundle, got: "
                        + snippet(french));
        assertFalse(french.contains(englishLabel),
                "and must not still be emitting the English label");
        logout();
    }

    /**
     * {@code entryDisplayCount} decides how many entries a reader gets on one
     * page. Two entries and a limit of one is the smallest arrangement that
     * tells the difference between "the setting works" and "there was only ever
     * one entry".
     */
    @Test
    void theEntryDisplayCountLimitsWhatTheFrontPageShows() {
        String suffix = nonce();
        String older = "Older post " + suffix;
        String newer = "Newer post " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, older);
        publishEntry(handle, newer);

        String both = readerHome(handle);
        assertTrue(both.contains(older) && both.contains(newer),
                "both entries must be on the front page before the limit is applied");

        setNumber(handle, "bean.entryDisplayCount", 1);

        String limited = readerHome(handle);
        assertTrue(limited.contains(newer),
                "the newest entry must survive the limit, got: " + snippet(limited));
        assertFalse(limited.contains(older),
                "the older entry must fall off a one-entry page, got: " + snippet(limited));
        logout();
    }

    /**
     * Deactivating a weblog takes it out of the site's SEO surface.
     *
     * <p>{@code active} does not hide the weblog's pages — it withdraws it from
     * the listings a crawler is pointed at, which is the only place the flag is
     * observable. Both checks are anonymous fetches, so this costs almost
     * nothing on top of the fixture.
     *
     * <p>There is deliberately no test for the per-weblog analytics snippet: its
     * textarea renders only when {@code weblogAdminsUntrusted} is off, and this
     * fork keeps it on — a weblog admin cannot inject script into their own page
     * head. The field exists in the bean and in {@code #showAnalyticsTrackingCode},
     * but no supported configuration exposes it.
     */
    @Test
    void deactivatingAWeblogWithdrawsItFromTheSitemaps() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, "Sitemapped " + suffix);

        String perWeblogSitemap = baseUrl() + "/sitemap-" + handle + ".xml";
        assertTrue(getAnonymously(baseUrl() + "/sitemap.xml").contains("sitemap-" + handle + ".xml"),
                "an active weblog must be listed in the sitemap index");
        assertEquals(200, statusAnonymously(perWeblogSitemap),
                "and must serve its own sitemap");

        setCheckbox(handle, "bean.active", false);

        assertFalse(getAnonymously(baseUrl() + "/sitemap.xml").contains("sitemap-" + handle + ".xml"),
                "a deactivated weblog must drop out of the sitemap index");
        assertEquals(404, statusAnonymously(perWeblogSitemap),
                "and its own sitemap must stop being served");
        logout();
    }

    // -------------------------------------------------------- settings edits

    /**
     * A refused value marks its own field and puts the cursor in it (B8).
     *
     * <p>Weblog Settings is the screen this matters most on: the newsletter
     * list uuid sits in the fifth section of a long form, so the error banner
     * that renders at the top of the page can be the ONLY thing visible while
     * the offending box is far below the fold. Two independent facts are
     * asserted because the marker and the focus come from different halves of
     * the mechanism (a class on the element, and a focus() call that runs only
     * for the first id): a browser is also the only place either one can be
     * observed at all -- the unit test proves the controller named the field,
     * not that the page did anything with it.
     */
    @Test
    void anInvalidListUuidMarksAndFocusesTheField() {
        loginAsAdmin();
        String handle = createWeblog();

        openSettings(handle);
        $("#weblog_bean_newsletterListUuid").setValue("not-a-uuid");
        $("button[type='submit'].btn-primary").should(visible).click();

        $("#errors").should(exist);
        $("#weblog_bean_newsletterListUuid").shouldHave(cssClass("is-invalid"));
        $("#weblog_bean_newsletterListUuid").shouldHave(attribute("aria-invalid", "true"));
        assertEquals("weblog_bean_newsletterListUuid",
                executeJavaScript("return document.activeElement.id;"),
                "the refused field must also hold the cursor, so the fix needs no scrolling");

        // A form with nothing wrong must carry no marker at all -- otherwise
        // the attribute would be sticky and every later save would look
        // refused.
        $("#weblog_bean_newsletterListUuid").setValue("");
        saveSettings();
        $("#weblog_bean_newsletterListUuid").shouldNotHave(cssClass("is-invalid"));
        assertTrue($$("body[data-invalid-fields]").isEmpty(),
                "a clean save must not leave data-invalid-fields on the body");
    }

    /**
     * Ticks or unticks one checkbox on Weblog Settings and saves.
     *
     * <p>Reads the value back afterwards. An unticked checkbox posts nothing at
     * all, so a form that binds it wrongly still redirects and still reports
     * success — only re-reading the page proves the setting took.
     */
    private void setCheckbox(String handle, String name, boolean value) {
        openSettings(handle);
        if ($("input[name='" + name + "']").isSelected() != value) {
            $("input[name='" + name + "']").click();
        }
        saveSettings();

        openSettings(handle);
        if (value) {
            $("input[name='" + name + "']").shouldBe(checked);
        } else {
            $("input[name='" + name + "']").shouldNotBe(checked);
        }
    }

    private void selectOption(String handle, String name, String optionValue) {
        openSettings(handle);
        $("select[name='" + name + "']").selectOptionByValue(optionValue);
        saveSettings();

        openSettings(handle);
        $("select[name='" + name + "']").shouldHave(value(optionValue));
    }

    private void setNumber(String handle, String name, int number) {
        openSettings(handle);
        $("input[name='" + name + "']").setValue(Integer.toString(number));
        saveSettings();

        openSettings(handle);
        $("input[name='" + name + "']").shouldHave(value(Integer.toString(number)));
    }

    private void setTextArea(String handle, String name, String text) {
        openSettings(handle);
        $("textarea[name='" + name + "']").setValue(text);
        saveSettings();

        openSettings(handle);
        $("textarea[name='" + name + "']").shouldHave(value(text));
    }

    private void openSettings(String handle) {
        openPath("/roller-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.name']").should(visible);
    }

    private void saveSettings() {
        $("button[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(),
                "saving the weblog settings reported an error: "
                        + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
        BrowserHealth.current().settle();
    }

    // ---------------------------------------------------------------- weblog

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "wcfg" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Weblog Config " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Publishes one entry and returns its permalink. */
    private String publishEntry(String handle, String title) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /**
     * The weblog home page as an anonymous reader gets it. The trailing slash
     * is load-bearing: the bare handle redirects and the anonymous fetch helper
     * does not follow redirects, so without it every assertion reads an empty
     * body.
     */
    private String readerHome(String handle) {
        return getAnonymously(baseUrl() + "/" + handle + "/");
    }

    /** The status code an anonymous reader gets, which a body alone cannot show. */
    private static int statusAnonymously(String url) {
        try {
            return java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(20))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
