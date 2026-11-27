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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.AdminToken;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.hidden;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeAsyncJavaScript;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A signed-in reader's saves, follows and reading history on the real pages
 * (0.18.0, Part E task E4), at whichever context path the run uses: the
 * packaged WAR, a real browser, and the run's Keycloak as the provider.
 *
 * <p><b>The reader is {@link #READER}</b>, this class's own Keycloak user
 * (pressroom-it-realm.json): its saves, follows and history are nobody
 * else's, and every test reads only its own entries, so the tests do not
 * depend on their order.
 *
 * <p><b>The fixture</b> (two journal blogs, two posts each) is built through
 * the API by the first test and removed in {@code @AfterAll}; removing a blog
 * removes the reader's rows for it (V056's cascade). The sign-in callback is
 * built from the Absolute URL, so the class points it at {@link #baseUrl()}
 * and holds {@link PressroomIT#GLOBAL_CONFIG} exclusively, as {@code
 * CommentsIT} does; that also keeps the front page the discovery page.
 *
 * <p>Every behaviour was driven out first by {@code ReaderControlsRenderTest},
 * {@code reader-script.test.mjs} and the controller tests; a failure here
 * means the assembled WAR does not behave as those said it would.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class ReaderStateIT extends PressroomIT {

    private static final String READER = "saver-it";
    private static final String ACCOUNT = "/pressroom-ui/account.rol";
    private static final String SAVE = "[data-reader-save]";
    private static final String SAVE_BUTTON = SAVE + " button";
    private static final String MASTHEAD_FOLLOW = "header [data-reader-follow] button";
    /** Set by the reader script once the signed-in state is shown on the page. */
    private static final String READY = "[data-reader][data-ready]";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Asks the reader endpoint for this page's state as the page script does,
     * then posts {@code arguments[0]} (a JSON string) with the CSRF header it
     * named; calls back with the POST's status, or the GET's state when
     * {@code arguments[0]} is null.
     */
    private static final String ENDPOINT_SCRIPT = String.join("\n",
            "var payload = arguments[0], done = arguments[arguments.length - 1];",
            "var url = document.querySelector('[data-reader]').getAttribute('data-endpoint');",
            "fetch(url + (arguments[1] ? '?' + arguments[1] : ''), {credentials: 'same-origin',",
            "    headers: {'Accept': 'application/json'}})",
            "  .then(function (r) { return r.json(); })",
            "  .then(function (s) {",
            "    if (!payload) { done(JSON.stringify(s)); return; }",
            "    var headers = {'Accept': 'application/json', 'Content-Type': 'application/json'};",
            "    headers[s.csrf.header] = s.csrf.token;",
            "    return fetch(url, {method: 'POST', credentials: 'same-origin', headers: headers, body: payload})",
            "      .then(function (r) { done(String(r.status)); });",
            "  })",
            "  .catch(function (e) { done('error ' + e); });");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private boolean fixtureStarted;
    private boolean fixtureBuilt;
    private boolean absoluteUrlSet;
    private String priorAbsoluteUrl;
    private AdminToken token;
    private final List<String> handles = new ArrayList<>();
    private Blog blogA;
    private Blog blogB;

    /** A blog of the fixture: its handle and name, and its posts oldest first. */
    private record Blog(String handle, String name, List<Post> posts) {
    }

    /** A post: its id, title and permalink path below the context path. */
    private record Post(String id, String title, String path) {
    }

    // ------------------------------------------------------------- fixture

    @BeforeEach
    void buildTheFixtureOnce() throws Exception {
        if (fixtureStarted) {
            assertTrue(fixtureBuilt, "the class fixture failed in an earlier test; see that failure");
            return;
        }
        fixtureStarted = true;
        loginAsAdmin();
        priorAbsoluteUrl = setSiteAbsoluteUrl(baseUrl());
        absoluteUrlSet = true;
        logout();
        token = AdminToken.mint("ReaderStateIT admin", "ADMIN");
        blogA = blog("rsa", "Reader State Alpha");
        blogB = blog("rsb", "Reader State Beta");
        fixtureBuilt = true;
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            for (String handle : handles) {
                send(HttpRequest.newBuilder(api("/weblogs/" + handle)).DELETE());
            }
        } finally {
            if (token != null) {
                token.close();
            }
            inBrowserAfterAll(() -> {
                if (absoluteUrlSet) {
                    loginAsAdmin();
                    setSiteAbsoluteUrl(priorAbsoluteUrl);
                    logout();
                }
            });
        }
    }

    // ------------------------------------------------------------- tests

    /**
     * Review Focus 1: the Save intent a signed-out click stores survives the
     * round trip and completes exactly once. The intent is seeded as the
     * signed-out click leaves it, then the page loads signed in: it saves and
     * confirms inline. After an unsave, a reload does not save again.
     */
    @Test
    void intentCompletesOnce() {
        Post post = blogA.posts().get(0);
        signInWithKeycloak(READER, contextPath() + post.path());
        $(READY).should(exist);
        executeJavaScript("sessionStorage.setItem('pressroom.intent', JSON.stringify("
                + "{action: 'save', entry: arguments[0], at: Date.now()}));", post.id());

        Selenide.refresh();

        $(SAVE_BUTTON).shouldHave(attribute("aria-pressed", "true"));
        $(SAVE + " .reader-note").shouldHave(exactText("Saved to your account."));
        assertNull(executeJavaScript("return sessionStorage.getItem('pressroom.intent');"));
        assertTrue(state("entry=" + post.id()).get("saved").asBoolean());

        assertEquals("200", endpoint("{\"action\":\"unsave\",\"entry\":\"" + post.id() + "\"}", null));
        Selenide.refresh();
        $(SAVE_BUTTON).shouldHave(attribute("aria-pressed", "false"));
        $(SAVE + " .reader-note").shouldNot(exist);
        assertTrue(!state("entry=" + post.id()).get("saved").asBoolean(), "the reload did not save again");
        logout();
    }

    /** Signed out, Save becomes the provider's button in place, counted as a sign-in via save. */
    @Test
    void aSignedOutSaveOffersTheSignInInPlace() {
        Post post = blogB.posts().get(0);
        openPath(post.path());
        $(SAVE_BUTTON).shouldHave(exactText("Save")).click();

        SelenideElement button = $(SAVE + " a.g-signin").should(visible);
        button.shouldHave(attribute("data-umami-event-via", "save"), text("Sign in with Keycloak to save this"));
        String href = button.getAttribute("href");
        assertNotNull(href);
        assertEquals(contextPath() + post.path(), queryParameter(href, "return"), href);
        executeJavaScript("sessionStorage.removeItem('pressroom.intent');");
    }

    /** Save on the entry page, then it is under Saved on the account page, where Remove takes it off. */
    @Test
    void aSavedArticleIsOnTheAccountPage() {
        Post post = blogB.posts().get(1);
        signInWithKeycloak(READER, contextPath() + post.path());
        $(READY).should(exist);
        $(SAVE_BUTTON).shouldHave(attribute("aria-pressed", "false")).click();
        $(SAVE_BUTTON).shouldHave(attribute("aria-pressed", "true"));

        openPath(ACCOUNT + "#saved");
        SelenideElement saved = $("section#saved").shouldHave(text(post.title()));
        SelenideElement row = saved.$$("li").findBy(text(post.title()));
        clickAndAwaitNewPage(row.$("button[type='submit']"));
        assertTrue(currentPath().endsWith(ACCOUNT), currentPath());
        $("section#saved").shouldNotHave(text(post.title()));
        logout();
    }

    /**
     * Follow in the masthead, then the front page's "From blogs you follow"
     * shows that blog's newest post; the "You" menu leads to Following.
     */
    @Test
    void aFollowedBlogsNewestPostIsOnTheFrontPage() {
        Post newest = blogA.posts().get(1);
        signInWithKeycloak(READER, contextPath() + blogA.posts().get(0).path());
        $(READY).should(exist);
        SelenideElement follow = $(MASTHEAD_FOLLOW).should(visible);
        if (!"true".equals(follow.getAttribute("aria-pressed"))) {
            follow.click();
        }
        follow.shouldHave(attribute("aria-pressed", "true"));

        openPath("/");
        SelenideElement row = $("section.fd-followed [data-reader-row='followed']").should(visible);
        row.$$("a").findBy(exactText(newest.title())).should(exist)
                .shouldHave(attribute("href", baseUrl().replaceFirst("/$", "") + newest.path()));

        $(".fd-mast details.you-menu summary").click();
        clickAndAwaitNewPage($(".fd-mast details.you-menu a[href$='account.rol#following']").should(visible));
        $("section#following").shouldHave(text(blogA.name()));
        logout();
    }

    /**
     * Ten visible seconds on an entry record it; in a fresh browser (no
     * cookies, the provider's included, and no storage) the same reader, signed in again, finds
     * it under "Continue reading" on the blog home, with its Read mark.
     */
    @Test
    void aReadFollowsTheReaderToAFreshBrowser() {
        Post post = blogB.posts().get(0);
        signInWithKeycloak(READER, contextPath() + post.path());
        $(READY).should(exist);
        Selenide.sleep(11_500);
        // A fresh browser: every cookie gone (the site's and the provider's: logout clears the
        // browser's whole jar and ends the server session), and this origin's storage emptied.
        // Not a new driver: BrowserHealth's DevTools session belongs to this one.
        logout();
        openPath("/" + blogB.handle() + "/");
        executeJavaScript("localStorage.clear(); sessionStorage.clear();");
        $("[data-reader][data-signed-in]").shouldNot(exist);

        signInWithKeycloak(READER, contextPath() + "/" + blogB.handle() + "/");
        SelenideElement row = $("[data-reader-row='continue']").should(visible);
        row.$$("a").findBy(exactText(post.title())).should(exist);
        $("[data-reader-entry='" + post.id() + "'] + .reader-read-mark").shouldHave(exactText("Read"));
        logout();
    }

    /**
     * Signed in, Places you've been comes from the server's history, and the
     * browser's own list is left untouched (it shows again after sign-out).
     */
    @Test
    void signedInPlacesComeFromTheServer() {
        Post onA = blogA.posts().get(0);
        signInWithKeycloak(READER, contextPath() + onA.path());
        executeJavaScript("localStorage.setItem('pressroom.places', '[]');");
        assertEquals("200", endpoint("{\"action\":\"read\",\"entry\":\"" + blogB.posts().get(1).id() + "\"}", null));

        Selenide.refresh();
        $(READY).should(exist);
        SelenideElement places = $(".more-from-site [data-places-source='server']").should(visible);
        places.$$("a").findBy(exactText(blogB.name())).should(exist);
        places.$$("a").findBy(exactText(blogA.name())).shouldNot(exist);
        assertEquals("[]", executeJavaScript("return localStorage.getItem('pressroom.places');"),
                "the browser's own list is not written while signed in");
        logout();
        executeJavaScript("localStorage.removeItem('pressroom.places');");
    }

    /** An anonymous visitor's page has no reader rows and asks the endpoint nothing. */
    @Test
    void anAnonymousHomeHasNoRows() {
        openPath("/" + blogA.handle() + "/");
        $("[data-reader-row]").shouldNot(exist);
        $("[data-reader]").should(exist).shouldBe(hidden);
        $("[data-reader][data-signed-in]").shouldNot(exist);
    }

    // ------------------------------------------------------------- helpers

    /** The reader endpoint's state for {@code query}, as the signed-in browser sees it. */
    private static JsonNode state(String query) {
        String answer = endpoint(null, query);
        try {
            return JSON.readTree(answer);
        } catch (RuntimeException e) {
            throw new AssertionError("not JSON: " + answer, e);
        }
    }

    private static String endpoint(String payload, String query) {
        Object answer = executeAsyncJavaScript(ENDPOINT_SCRIPT, payload, query);
        return String.valueOf(answer);
    }

    /** A journal blog with two published posts, a minute apart, through the API. */
    private Blog blog(String prefix, String name) throws Exception {
        String handle = prefix + Long.toString(System.nanoTime(), 36);
        Map<String, Object> weblog = new LinkedHashMap<>();
        weblog.put("handle", handle);
        weblog.put("name", name);
        weblog.put("theme", "journal");
        weblog.put("emailAddress", handle + "@example.invalid");
        HttpResponse<String> created = send(HttpRequest.newBuilder(api("/weblogs"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(weblog))));
        assertEquals(201, created.statusCode(), created.body());
        handles.add(handle);
        List<Post> posts = new ArrayList<>();
        for (String which : List.of("first", "second")) {
            String title = name + " " + which + " post";
            Map<String, Object> entry = Map.of("title", title, "text", "<p>The " + which + " post.</p>",
                    "status", "PUBLISHED");
            HttpResponse<String> made = send(HttpRequest.newBuilder(api("/weblogs/" + handle + "/entries"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(JSON.writeValueAsString(entry))));
            assertEquals(201, made.statusCode(), made.body());
            JsonNode json = JSON.readTree(made.body());
            String permalink = json.get("permalink").asString();
            posts.add(new Post(json.get("id").asString(), title,
                    URI.create(permalink).getPath().substring(contextPath().length())));
            Thread.sleep(1100);
        }
        return new Blog(handle, name, posts);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(20)).header("Authorization", token.bearer()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI api(String path) {
        return URI.create(baseUrl() + "/api/v1" + path);
    }
}
