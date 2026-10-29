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
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * URL redirects end to end: renaming a page's slug keeps the old URL alive
 * with a 301 an anonymous reader actually follows, a manual rule created
 * over the API answers a migrated multi-segment path, and the hit
 * bookkeeping is readable back over the API.
 *
 * <p>Owns its weblog; touches no global state, so no resource locks. The
 * would-404 / redirect probes are plain HTTP (never browser navigations) for
 * the same reason {@code PageIT} checks a draft's 404 that way -- steering
 * the browser into a 3xx/4xx chain would trip {@code BrowserHealth}'s
 * teardown checks for responses the test asked for on purpose.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class RedirectIT extends PressroomIT {

    private static final String CREATE_WEBLOG = "/roller-ui/createWeblog.rol";

    /** Follows redirects: the reader's experience of a stale link. */
    private HttpClient following;
    /** Never follows: what the server literally answered. */
    private HttpClient raw;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        following = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        raw = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Test
    void aRenamedSlugAndAManualRuleBothAnswerWith301sAndCountTheirHits() throws Exception {
        String handle = createWeblog();
        String oldUrl = baseUrl() + "/" + handle + "/old-name";
        String newUrl = baseUrl() + "/" + handle + "/new-name";

        // --- a published page at its original slug ------------------------
        openPath("/roller-ui/authoring/pageEdit.rol?weblog=" + handle);
        $("#pageEditForm").should(exist);
        $("#page_bean_slug").setValue("old-name");
        $("#page_bean_title").setValue("Moving Target");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        Editor.setText("This page is about to move.");
        saveOpenPage();
        assertEquals(200, send(raw, oldUrl).statusCode(),
                "the page must be live at its original slug before the rename");

        // --- rename the slug through the editor ---------------------------
        openPageByTitle(handle, "Moving Target");
        $("#page_bean_slug").setValue("new-name");
        saveOpenPage();

        // --- the old URL is a 301 to the new one, not a 404 ---------------
        HttpResponse<Void> answer = send(raw, oldUrl);
        assertEquals(301, answer.statusCode(),
                "renaming a slug must keep the old URL alive with a permanent redirect");
        String location = answer.headers().firstValue("Location").orElse("");
        assertTrue(location.endsWith("/" + handle + "/new-name"),
                "the Location must point at the renamed page, got: " + location);

        HttpResponse<String> followed = sendForBody(following, oldUrl);
        assertEquals(200, followed.statusCode());
        assertTrue(followed.body().contains("Moving Target"),
                "a reader following the stale link must land on the page itself");
        assertEquals(200, send(raw, newUrl).statusCode(),
                "and the new URL serves directly, no redirect involved");

        // --- a manual rule over the API covers a migrated path ------------
        HttpResponse<String> created = apiPost("/api/v1/weblogs/" + handle + "/redirects",
                "{\"source\":\"/2019/05/legacy-post.html\",\"target\":\"/new-name\"}");
        assertEquals(201, created.statusCode(), "mint failed: " + created.body());

        String migratedUrl = baseUrl() + "/" + handle + "/2019/05/legacy-post.html";
        HttpResponse<Void> migrated = send(raw, migratedUrl);
        assertEquals(301, migrated.statusCode(),
                "a manual rule must answer a migrated multi-segment path");
        assertTrue(sendForBody(following, migratedUrl).body().contains("Moving Target"));

        // --- the hit bookkeeping is readable back over the API ------------
        HttpResponse<String> listed = apiGet("/api/v1/weblogs/" + handle + "/redirects");
        assertEquals(200, listed.statusCode(), listed.body());
        String slugRule = ruleObject(listed.body(), "/old-name");
        String manualRule = ruleObject(listed.body(), "/2019/05/legacy-post.html");
        assertTrue(slugRule.contains("\"origin\":\"SLUG_HISTORY\""),
                "the rename-minted rule must say where it came from: " + slugRule);
        assertTrue(manualRule.contains("\"origin\":\"MANUAL\""), manualRule);
        assertTrue(hitCountOf(slugRule) >= 2,
                "both probes of the old URL must have been counted: " + slugRule);
        assertTrue(slugRule.contains("\"lastHitAt\":\""), slugRule);
        assertTrue(hitCountOf(manualRule) >= 1, manualRule);
    }

    // ---------------------------------------------------------------- helpers

    private String createWeblog() {
        String handle = "redirit" + Long.toString(System.nanoTime(), 36);

        openPath(CREATE_WEBLOG);
        $("#name").should(visible).setValue("Redirects " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    private void openPageByTitle(String handle, String title) {
        openPath("/roller-ui/authoring/pages.rol?weblog=" + handle);
        $("#pageRemoveForm").should(exist);
        $$("a").findBy(text(title)).should(exist).click();
        $("#pageEditForm").should(exist);
    }

    private void saveOpenPage() {
        $("#pageEditForm button[type='submit']").click();
        $("#messages").should(exist);
    }

    private HttpResponse<Void> send(HttpClient client, String url) throws Exception {
        return client.send(anonymousGet(url), HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> sendForBody(HttpClient client, String url) throws Exception {
        return client.send(anonymousGet(url), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest anonymousGet(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET().build();
    }

    private HttpResponse<String> apiGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .GET().build();
        return raw.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiPost(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return raw.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String basicAuthHeader() {
        String credentials = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    /** The one JSON object in an array whose "source" equals the given path. */
    private static String ruleObject(String jsonArray, String source) {
        Matcher m = Pattern.compile("\\{[^{}]*\"source\":\"" + Pattern.quote(source) + "\"[^{}]*\\}")
                .matcher(jsonArray);
        if (!m.find()) {
            fail("no rule with source " + source + " in: " + jsonArray);
        }
        return m.group();
    }

    private static long hitCountOf(String ruleJson) {
        Matcher m = Pattern.compile("\"hitCount\":(\\d+)").matcher(ruleJson);
        assertTrue(m.find(), "no hitCount in: " + ruleJson);
        assertNotNull(m.group(1));
        return Long.parseLong(m.group(1));
    }
}
