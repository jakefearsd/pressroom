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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Selenide;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Site pages and the front door, end to end: a global admin follows the main
 * menu's Site section to Pages, publishes {@code it-front} with a heading,
 * chooses it as the front door in Global Config; an anonymous {@code GET /}
 * then shows the heading, {@code GET /it-front} lands on {@code /}, and once
 * the page is unpublished {@code /} shows the discovery page again and
 * {@code /it-front} is a 404.
 *
 * <p>Written after the behaviour, so the watch-it-fail step was a deliberate
 * mutation: with the "choose it as the front door" step removed the anonymous
 * {@code GET /} assertion failed (the directory, not the heading); with the
 * step restored it passes at the root and at {@code -Dit.context.path=pressroom}.
 *
 * <p>{@code site.frontpage.page} and {@code site.frontpage.weblog.handle} are
 * global, so the class holds {@link PressroomIT#GLOBAL_CONFIG} for WRITE and
 * restores the front-door choice in a {@code finally}, before deleting the
 * page it created (by its slug, which only this class uses).
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ_WRITE)
class SitePagesIT extends PressroomIT {

    private static final String GLOBAL_CONFIG_PATH = "/pressroom-ui/admin/globalConfig.rol";
    private static final String FRONTPAGE_FIELD = "site.frontpage.weblog.handle";
    private static final String SLUG = "it-front";
    private static final String HEADING = "Front door heading";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    void aSitePageIsTheFrontDoorUntilItIsUnpublished() throws Exception {
        loginAsAdmin();

        openPath(GLOBAL_CONFIG_PATH);
        String wasFront = $("select[name='" + FRONTPAGE_FIELD + "']").should(exist).getSelectedOptionValue();
        if (wasFront == null) {
            wasFront = "";
        }

        boolean globalsChanged = false;
        Throwable primary = null;
        try {
            // --- Site -> Pages from the main menu, then create and publish --
            openPath("/pressroom-ui/menu.rol");
            $(".site-row").should(exist);
            clickAndAwaitNewPage($$(".site-row a.quiet-link").first());
            $("#pageRemoveForm").should(exist);
            removeSitePage(); // a leftover from an aborted run would clash
            openPath(sitePageEditPath());
            $("#pageEditForm").should(exist);
            $("#page_bean_slug").setValue(SLUG);
            $("#page_bean_title").setValue("It Front");
            Editor.setText("## " + HEADING + "\n\nWelcome.");
            $("#page_bean_status").selectOptionByValue("PUBLISHED");
            clickAndAwaitNewPage($("#pageSaveButton"));
            $("#messages").should(exist);

            // --- choose it as the front door ---------------------------------
            openPath(GLOBAL_CONFIG_PATH);
            $("select[name='" + FRONTPAGE_FIELD + "']").selectOptionByValue("page:" + SLUG);
            globalsChanged = true;
            clickAndAwaitNewPage($("#saveButton"));
            $("#messages").should(exist);
            assertTrue($$("#errors").isEmpty(), "choosing the front door reported an error: "
                    + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));

            // --- anonymous readers ------------------------------------------
            String root = baseUrl() + "/";
            Fetched home = fetch(root);
            assertEquals(200, home.status, "the front door must answer");
            assertTrue(home.body.contains(HEADING),
                    "GET / must show the front-door page's heading, got: " + truncate(home.body));

            Fetched slugged = fetch(baseUrl() + "/" + SLUG);
            assertEquals(200, slugged.status, "/" + SLUG + " must land on the front door");
            assertEquals(root, slugged.finalUri, "/" + SLUG + " must redirect to /");
            assertTrue(slugged.body.contains(HEADING), "the page behind the redirect is the front door");

            // --- the site sitemap lists the published page --------------------
            Fetched sitemap = fetch(baseUrl() + "/sitemap-site-pages.xml");
            assertEquals(200, sitemap.status, "a published site page puts /sitemap-site-pages.xml in service");
            assertTrue(sitemap.body.contains("<urlset") && sitemap.body.contains("<loc>"),
                    "the site sitemap must be a urlset with the page, got: " + truncate(sitemap.body));

            // --- unpublish: / shows the discovery page, the slug is a 404 -----
            openPath("/pressroom-ui/authoring/pages.rol?weblog=site-space");
            $("#pageRemoveForm").should(exist);
            $$("a").findBy(text("It Front")).should(exist).click();
            $("#pageEditForm").should(exist);
            $("#page_bean_status").selectOptionByValue("DRAFT");
            clickAndAwaitNewPage($("#pageSaveButton"));
            $("#messages").should(exist);

            Fetched after = fetch(root);
            assertEquals(200, after.status, "an unpublished front door still leaves a working /");
            assertTrue(!after.body.contains(HEADING), "an unpublished page must not be served at /");
            assertTrue(after.body.contains("fd-discovery-all"),
                    "/ must fall back to the discovery page, got: " + truncate(after.body));
            assertEquals(404, fetch(baseUrl() + "/" + SLUG).status,
                    "a draft site page must be a 404 at /" + SLUG);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // A cleanup failure must never replace the failure being reported.
            try {
                cleanUp(globalsChanged, wasFront);
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

    private static String sitePageEditPath() {
        return "/pressroom-ui/authoring/pageEdit.rol?weblog=site-space";
    }

    /** Restores the front-door choice, then deletes the page: identity cleanup. */
    private void cleanUp(boolean globalsChanged, String wasFront) {
        if (globalsChanged) {
            openPath(GLOBAL_CONFIG_PATH);
            String restore = wasFront;
            // A page that was the front door before this run may be a draft now,
            // and so absent from the select; fall back to the directory.
            if ($$("select[name='" + FRONTPAGE_FIELD + "'] option[value='" + wasFront + "']").isEmpty()) {
                restore = "";
            }
            $("select[name='" + FRONTPAGE_FIELD + "']").selectOptionByValue(restore);
            clickAndAwaitNewPage($("#saveButton"));
            $("#messages").should(exist);
            assertTrue($$("#errors").isEmpty(), "restoring the front door reported an error");
            openPath(GLOBAL_CONFIG_PATH);
            assertEquals(restore, $("select[name='" + FRONTPAGE_FIELD + "']").getSelectedOptionValue(),
                    "the front door was not restored");
        }
        openPath("/pressroom-ui/authoring/pages.rol?weblog=site-space");
        $("#pageRemoveForm").should(exist);
        removeSitePage();
    }

    /** Deletes the page with this class's slug from the open site Pages list, if present. */
    private void removeSitePage() {
        if ($$("tr[data-page-slug='" + SLUG + "']").isEmpty()) {
            return;
        }
        awaitNewPageAfter(() -> {
            $("tr[data-page-slug='" + SLUG + "'] button[data-confirm]").click();
            Selenide.confirm();
        });
        $("#messages").should(exist);
        $$("tr[data-page-slug='" + SLUG + "']").shouldHave(CollectionCondition.size(0));
    }

    private record Fetched(int status, String finalUri, String body) { }

    private Fetched fetch(String url) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Fetched(response.statusCode(), response.uri().toString(), response.body());
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "...";
    }
}
