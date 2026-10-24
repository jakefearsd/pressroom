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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colophon end to end: a site admin publishes a Page on a blog, makes that
 * blog the front-page blog and names the page as the privacy policy in Global
 * Config; a blog page (fetched anonymously, as a reader sees it) and an admin
 * page then both show {@code Roller <version>} and a "Privacy policy" link whose
 * href resolves to the page. The same run at {@code -Dit.context.path=roller}
 * proves the link carries the prefix.
 *
 * <p>Owns its weblog and its page. {@code site.frontpage.weblog.handle} and
 * {@code site.privacy.page} are global, so the class holds
 * {@link RollerIT#GLOBAL_CONFIG} for WRITE and puts both back in a
 * {@code finally}, before deleting the page it created.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ_WRITE)
class ColophonIT extends RollerIT {

    private static final String GLOBAL_CONFIG_PATH = "/roller-ui/admin/globalConfig.rol";
    private static final String FRONTPAGE_FIELD = "site.frontpage.weblog.handle";
    private static final String PRIVACY_FIELD = "site.privacy.page";

    private static final Pattern VERSION = Pattern.compile(
            "<span class=\"colophon-version\">\\s*(Roller [^<]+?)\\s*</span>");
    private static final Pattern PRIVACY_LINK = Pattern.compile(
            "<span class=\"colophon-privacy\"><a href=\"([^\"]+)\">Privacy policy</a></span>");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    void aBlogPageAndAnAdminPageShowTheVersionAndThePrivacyLink() throws Exception {
        loginAsAdmin();
        String nonce = Long.toString(System.nanoTime(), 36);
        String handle = createWeblog(nonce);
        String slug = "privacy";
        String title = "Privacy " + nonce;

        openPath(GLOBAL_CONFIG_PATH);
        String wasFront = $("select[name='" + FRONTPAGE_FIELD + "']").should(exist).getSelectedOptionValue();
        String wasPrivacy = $("input[name='" + PRIVACY_FIELD + "']").getValue();
        if (wasFront == null) {
            wasFront = "";
        }
        if (wasPrivacy == null) {
            wasPrivacy = "";
        }

        boolean globalsChanged = false;
        Throwable primary = null;
        try {
            // --- a published page on the blog that will be the front-page blog
            openPath("/roller-ui/authoring/pageEdit.rol?weblog=" + handle);
            $("#pageEditForm").should(exist);
            $("#page_bean_slug").setValue(slug);
            $("#page_bean_title").setValue(title);
            $("#page_bean_status").selectOptionByValue("PUBLISHED");
            clickAndAwaitNewPage($("#pageSaveButton"));
            $("#messages").should(exist);
            String pageUrl = baseUrl() + "/" + handle + "/" + slug;
            assertEquals(200, statusOf(pageUrl), "the published page must resolve");

            // --- the setting, through the real Global Config page ----------
            openPath(GLOBAL_CONFIG_PATH);
            $("select[name='" + FRONTPAGE_FIELD + "']").selectOptionByValue(handle);
            $("input[name='" + PRIVACY_FIELD + "']").setValue(slug);
            globalsChanged = true;
            clickAndAwaitNewPage($("#saveButton"));
            $("#messages").should(exist);
            assertTrue($$("#errors").isEmpty(), "saving the privacy page reported an error: "
                    + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));

            // --- an admin page ---------------------------------------------
            openPath(GLOBAL_CONFIG_PATH);
            $(".colophon .colophon-version").should(visible);
            String adminVersion = $(".colophon .colophon-version").getText().trim();
            assertTrue(adminVersion.startsWith("Roller ") && adminVersion.length() > "Roller ".length(),
                    "the admin footer must show the Roller version, got: " + adminVersion);
            String adminHref = $(".colophon .colophon-privacy a").should(exist).getAttribute("href");
            assertPrivacyPageLoads(adminHref, pageUrl, title);
            BrowserHealth.current().assertNoBrokenResources();
            BrowserHealth.current().assertNoFailedRequests();

            // --- a blog page, as an anonymous reader gets it ---------------
            String blog = getAnonymously(baseUrl() + "/" + handle + "/");
            Matcher version = VERSION.matcher(blog);
            assertTrue(version.find(), "the blog footer must show the Roller version: " + colophonOf(blog));
            // Admin adds " (<revision>)" to the same version string.
            assertTrue(adminVersion.startsWith(version.group(1)),
                    "blog '" + version.group(1) + "' and admin '" + adminVersion + "' disagree on the version");
            Matcher link = PRIVACY_LINK.matcher(blog);
            assertTrue(link.find(), "the blog footer must link the privacy policy: " + colophonOf(blog));
            assertPrivacyPageLoads(link.group(1).replace("&amp;", "&"), pageUrl, title);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // A cleanup failure must never replace the failure being reported.
            try {
                cleanUp(handle, title, globalsChanged, wasFront, wasPrivacy);
            } catch (RuntimeException | AssertionError cleanupFailure) {
                if (primary == null) {
                    throw cleanupFailure;
                }
                primary.addSuppressed(cleanupFailure);
            }
        }
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** The link's href must be the page's URL and that URL must load, titled. */
    private void assertPrivacyPageLoads(String href, String expectedUrl, String title) throws Exception {
        assertEquals(expectedUrl, href, "the privacy link must point at the published page");
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(href)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the privacy link must resolve: " + href);
        assertTrue(response.body().contains(title),
                "the privacy page must carry its title '" + title + "'");
    }

    private static String colophonOf(String html) {
        int at = html.indexOf("class=\"colophon\"");
        return at < 0 ? "(no colophon in the page)" : html.substring(at, Math.min(html.length(), at + 600));
    }

    private int statusOf(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Puts both settings back, then deletes the page: identity cleanup. */
    private void cleanUp(String handle, String title, boolean globalsChanged,
                         String wasFront, String wasPrivacy) {
        if (globalsChanged) {
            openPath(GLOBAL_CONFIG_PATH);
            $("input[name='" + PRIVACY_FIELD + "']").setValue(wasPrivacy);
            $("select[name='" + FRONTPAGE_FIELD + "']").selectOptionByValue(wasFront);
            clickAndAwaitNewPage($("#saveButton"));
            $("#messages").should(exist);
            assertTrue($$("#errors").isEmpty(), "restoring the settings reported an error");
            openPath(GLOBAL_CONFIG_PATH);
            assertEquals(wasPrivacy, $("input[name='" + PRIVACY_FIELD + "']").getValue(),
                    "site.privacy.page was not restored");
            assertEquals(wasFront, $("select[name='" + FRONTPAGE_FIELD + "']").getSelectedOptionValue(),
                    "site.frontpage.weblog.handle was not restored");
        }
        openPath("/roller-ui/authoring/pages.rol?weblog=" + handle);
        $("#pageRemoveForm").should(exist);
        if ($$("tr[data-page-slug='privacy']").isEmpty()) {
            return; // never created
        }
        awaitNewPageAfter(() -> {
            $("tr[data-page-slug='privacy'] button[data-confirm]").click();
            Selenide.confirm();
        });
        $("#messages").should(exist);
        $$("tr[data-page-slug='privacy']").shouldHave(com.codeborne.selenide.CollectionCondition.size(0));
    }

    private String createWeblog(String nonce) {
        String handle = "colo" + nonce;
        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Colophon " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        clickAndAwaitNewPage($("button[type='submit']"));
        $("#messages").should(exist);
        return handle;
    }
}
