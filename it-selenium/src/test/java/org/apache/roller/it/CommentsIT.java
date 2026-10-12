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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.KeycloakLoginPage;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comments for signed-in readers end to end: a real browser, the packaged WAR, and the
 * run's Keycloak as the readers' provider. Covers the acceptance criteria the spec marks
 * (I), numbered as in docs/superpowers/specs/2026-10-12-reader-comments-design.md; each
 * test names its own.
 *
 * <p><b>The fixture is built once, by the first test</b> (the guarded {@code @BeforeEach},
 * as {@code SsoSignInIT} and {@code VirtualHostIT} do it, because a {@code @BeforeAll} has
 * no browser yet): {@code site.absoluteurl} pointed at {@link #baseUrl()} (the sign-in
 * callback is built from it), and three weblogs with comments on, each with its entries.
 * <ul>
 *   <li>{@code main}: an open entry, which most tests comment on, and a closed one,
 *       carrying the owner's comment from before it closed (criterion 19).</li>
 *   <li>{@code other}: one open entry, on which a reader is never trusted unless a test
 *       makes them so. Only {@link #READER2}'s held-then-approved comment is ever approved
 *       here, so {@link #READER}'s comments here are always held (criterion 5).</li>
 *   <li>{@code vhost}: one entry carrying the owner's comment, posted before the weblog
 *       got its custom domain, because posting there is refused afterwards (criterion 22).
 *       Removed again in {@code @AfterAll}, as {@code VirtualHostIT} removes its own.</li>
 * </ul>
 *
 * <p><b>The tests do not depend on their order.</b> Trust is per reader and weblog, and an
 * account deleted by {@link #theReaderChangesTheirNameAndDeletesTheirAccount} comes back
 * as a fresh one at the next sign-in. So a test that needs a published comment of a
 * reader's posts it through the session endpoint ({@link #postThroughTheSessionEndpoint})
 * and approves it through the API when it was held; the API test holds its comment with
 * three links, which holds even a trusted reader's (criterion 6).
 *
 * <p><b>The rate limit is raised for this suite.</b> Production allows each account five
 * comments or replies in ten minutes and thirty a day; {@code roller-it.properties} sets
 * {@code comments.throttle.*} to 1000 in ten minutes and 10000 a day (ruling R9), so the
 * seeded admin and the readers post and reply as often as the tests need. The limit
 * itself, at its defaults and as configured, is proven by {@code CommentServiceTest}.
 *
 * <p><b>The custom domain is reached by a real browser.</b> As in {@code VirtualHostIT},
 * Chrome's {@code --host-resolver-rules} maps {@link #VHOST} to {@code 127.0.0.1} for this
 * class's browser ({@link #mapTheCustomDomain()}); {@code jdk.httpclient.allowRestrictedHeaders}
 * (set on the failsafe JVM) lets the raw HTTP checks send its {@code Host} header.
 *
 * <p>The class holds {@link RollerIT#GLOBAL_CONFIG} exclusively, since it sets
 * {@code site.absoluteurl} and restores it in {@code @AfterAll}.
 *
 * <p>Acceptance testing: every behaviour here was driven out by unit tests first, so a
 * failure means the assembled WAR does not behave as those said it would.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(RollerIT.GLOBAL_CONFIG)
class CommentsIT extends RollerIT {

    /** Keycloak readers of this class alone; see roller-it-realm.json. */
    private static final String READER = "commenter-it";
    private static final String READER2 = "commenter2-it";

    /** A custom domain no other class uses; {@code VirtualHostIT} has {@code vhost.example.com}. */
    private static final String VHOST = "comments.example.com";

    /** The page's words, from ApplicationResources.properties. */
    private static final String SIGN_IN_TEXT = "Sign in with Google to comment";
    private static final String HELD_TEXT = "will appear once it is approved";
    private static final String AWAITING_TEXT = "Awaiting approval";
    private static final String CLOSED_TEXT = "Comments are closed.";
    private static final String UNAVAILABLE_TEXT = "Commenting is not available on this address yet.";
    private static final String AUTHOR_BADGE = "Author";

    private static final String LOGIN = "/roller-ui/login.rol";
    private static final String MENU = "/roller-ui/menu.rol";
    private static final String ACCOUNT = "/roller-ui/account.rol";
    private static final String MODERATION = "/roller-ui/authoring/comments.rol";
    private static final String ACCESS_DENIED = "/roller-ui/access-denied.rol";
    private static final String SSO_START = "/roller-ui/sso/start";
    private static final String SESSION_ENDPOINT = "/roller-ui/rendering/comments/session.rol";
    private static final String POST_ENDPOINT = "/roller-ui/rendering/comments.rol";
    private static final String DELETE_ENDPOINT = "/roller-ui/rendering/comments/";

    private static final String SECTION = "section.comments";
    private static final String SLOT = ".comments-slot";
    private static final String TEXTAREA = ".comment-form textarea";
    private static final String POST_BUTTON = ".comment-form button[type='submit']";
    private static final String MESSAGE = ".comment-form .comment-message";
    private static final String PERMALINK = "#entry_bean_permalink";
    private static final String MODERATION_ROWS = "#commentsModerationForm tr";

    /**
     * Posts {@code arguments[0]} as the signed-in browser, exactly as the page script
     * does: the CSRF header the session answer names, then the JSON post. Answers
     * {@code "<status> <body>"}.
     */
    private static final String POST_SCRIPT = String.join("\n",
            "var body = arguments[0];",
            "var slot = document.querySelector('.comments-slot[data-entry]');",
            "var entry = slot.getAttribute('data-entry');",
            "return fetch(slot.getAttribute('data-session') + '?entry=' + encodeURIComponent(entry),",
            "    {credentials: 'same-origin', headers: {'Accept': 'application/json'}})",
            "  .then(function (r) { return r.json(); })",
            "  .then(function (s) {",
            "    var headers = {'Accept': 'application/json', 'Content-Type': 'application/json'};",
            "    headers[s.csrf.header] = s.csrf.token;",
            "    return fetch(slot.getAttribute('data-post'), {method: 'POST', credentials: 'same-origin',",
            "        headers: headers, body: JSON.stringify({entry: entry, body: body})});",
            "  })",
            "  .then(function (r) { return r.text().then(function (t) { return r.status + ' ' + t; }); });");

    /** Deletes comment {@code arguments[0]} as the signed-in browser; answers the status. */
    private static final String DELETE_SCRIPT = String.join("\n",
            "var id = arguments[0];",
            "var slot = document.querySelector('.comments-slot[data-entry]');",
            "var entry = slot.getAttribute('data-entry');",
            "return fetch(slot.getAttribute('data-session') + '?entry=' + encodeURIComponent(entry),",
            "    {credentials: 'same-origin', headers: {'Accept': 'application/json'}})",
            "  .then(function (r) { return r.json(); })",
            "  .then(function (s) {",
            "    var headers = {'Accept': 'application/json'};",
            "    headers[s.csrf.header] = s.csrf.token;",
            "    return fetch(slot.getAttribute('data-delete') + encodeURIComponent(id) + '.rol',",
            "        {method: 'DELETE', credentials: 'same-origin', headers: headers});",
            "  })",
            "  .then(function (r) { return r.status; });");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Never follows a redirect: a 301 or 302 and its Location are what the assertions read. */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** An entry of the fixture: its id, and its permalink path below the context. */
    private record Entry(String id, String handle, String anchor) {

        /** {@code /<handle>/entry/<anchor>}, for {@link #openPath} and the raw GETs. */
        String path() {
            return "/" + handle + "/entry/" + anchor;
        }

        /** The same path with the context path in front: what the browser's URL shows. */
        String fullPath() {
            return contextPath() + path();
        }
    }

    /** A comment the browser posted: its id and the status the server gave it. */
    private record Posted(String id, String status) {
    }

    // ------------------------------------------------------------- fixture

    private boolean fixtureStarted;
    private boolean fixtureBuilt;
    private boolean absoluteUrlSet;
    private String priorAbsoluteUrl;

    private String mainHandle;
    private Entry open;
    private Entry closed;
    private String closedComment;
    private String closedCommentId;

    private String otherHandle;
    private Entry other;

    private String vhostHandle;
    private Entry vhostEntry;
    private String vhostComment;

    /**
     * Maps {@link #VHOST} to {@code 127.0.0.1} for this class's browser. The same
     * arrangement as {@code VirtualHostIT.enableVhostDns()}, whose javadoc explains why it
     * must happen here and why {@link RollerIT#markSelenideConfigured()} must follow it.
     */
    @BeforeAll
    void mapTheCustomDomain() {
        Selenide.closeWebDriver();
        Configuration.baseUrl = baseUrl();
        Configuration.browser = "chrome";
        Configuration.headless = true;
        Configuration.browserSize = BROWSER_SIZE;
        Configuration.browserCapabilities = chromeOptions("--host-resolver-rules=MAP " + VHOST + " 127.0.0.1");
        RollerIT.markSelenideConfigured();
    }

    /**
     * Builds the class fixture the first time a test starts; see the class javadoc. A
     * failure part-way is reported by the test that built it, and every later test fails
     * at once saying so, rather than on a null.
     */
    @BeforeEach
    void buildTheFixtureOnce() {
        if (fixtureStarted) {
            assertTrue(fixtureBuilt, "the class fixture failed in an earlier test; see that failure");
            return;
        }
        fixtureStarted = true;
        loginAsAdmin();
        priorAbsoluteUrl = setSiteAbsoluteUrl(baseUrl());
        absoluteUrlSet = true;

        mainHandle = createWeblogWithComments("cmtmain");
        open = publishEntry(mainHandle, "Open to comments " + nonce());
        closed = publishEntry(mainHandle, "Closed to comments " + nonce());
        closedComment = "Said before the entry closed " + nonce();
        closedCommentId = postAsOwnerThroughThePage(closed, closedComment);
        closeComments(closed);

        otherHandle = createWeblogWithComments("cmtother");
        other = publishEntry(otherHandle, "Another weblog's entry " + nonce());

        vhostHandle = createWeblogWithComments("cmtvhost");
        vhostEntry = publishEntry(vhostHandle, "An entry on a custom domain " + nonce());
        vhostComment = "Said before the custom domain " + nonce();
        postAsOwnerThroughThePage(vhostEntry, vhostComment);
        setCustomDomain(vhostHandle, VHOST);

        logout();
        fixtureBuilt = true;
    }

    /**
     * Removes the custom-domain weblog, restores the Absolute URL, and undoes
     * {@link #mapTheCustomDomain()}. One method because the order matters: the teardown
     * needs the browser this then closes.
     */
    @AfterAll
    void tearDown() {
        inBrowserAfterAll(() -> {
            if (!absoluteUrlSet && vhostHandle == null) {
                return;
            }
            loginAsAdmin();
            if (vhostHandle != null) {
                removeWeblog(vhostHandle);
            }
            if (absoluteUrlSet) {
                setSiteAbsoluteUrl(priorAbsoluteUrl);
            }
            logout();
        });
        Selenide.closeWebDriver();
        Configuration.browserCapabilities = chromeOptions();
    }

    // ---------------------------------------------------------- criterion 21

    /**
     * Criterion 21, first part: an anonymous visitor sees "Sign in with Google to comment",
     * a sign-in that returns to this entry, and no form.
     */
    @Test
    void anAnonymousVisitorSeesTheSignInLink() {
        openPath(open.path());

        SelenideElement link = $(SLOT + " a.comments-sign-in").shouldHave(exactText(SIGN_IN_TEXT));
        String href = link.getAttribute("href");
        assertNotNull(href, "the sign-in link has no href");
        assertEquals(contextPath() + SSO_START, URI.create(href).getPath(), "not a sign-in link: " + href);
        assertEquals(open.fullPath(), queryParameter(href, "return"), "the sign-in does not return here: " + href);
        $(TEXTAREA).shouldNot(exist);
    }

    // ---------------------------------------------------- criteria 4, 21, 24

    /**
     * Criteria 4, 21 and 24: a reader signs in from the link and posts their first comment
     * on a weblog; it is held ("will appear once it is approved", and the awaiting item),
     * and the anonymous page does not show it. The owner approves it on the Comments page,
     * where it moves from the Awaiting tab to the Published one, and the anonymous page
     * then shows it -- the page that was cached without it has expired.
     */
    @Test
    void aNewReadersCommentIsHeldThenApprovedAndShows() throws Exception {
        String body = "A first word from a new reader " + nonce();

        openPath(other.path());
        clickAndAwaitNewPage($(SLOT + " a.comments-sign-in").should(visible));
        new KeycloakLoginPage().signIn(READER2, KeycloakLoginPage.passwordOf(READER2));
        assertEquals(other.fullPath(), currentPath(), "the sign-in did not return to the entry");

        $(TEXTAREA).should(visible).setValue(body);
        $(POST_BUTTON).click();
        $(MESSAGE).shouldHave(text(HELD_TEXT));
        SelenideElement held = $$(".comments-pending .comment-pending").findBy(text(body)).should(exist);
        held.$(".comment-badge").shouldHave(exactText(AWAITING_TEXT));
        String id = held.getAttribute("data-comment");
        assertNotNull(id, "the awaiting item carries no comment id");

        String before = anonymousPage(other);
        assertFalse(before.contains(body), "a held comment is on the public page");
        logout();

        // Criterion 25: Main Menu shows the pending count beside the weblog. The seeded
        // admin created it, so its permission row holds "admin" alone, not "post".
        loginAsAdmin();
        openPath(MENU);
        $("a[href*='/roller-ui/authoring/comments.rol?weblog=" + otherHandle + "'] .badge")
                .shouldHave(text("awaiting approval"));
        openPath(moderation(otherHandle, null));
        $$(MODERATION_ROWS).findBy(text(body)).should(exist);
        clickAndAwaitNewPage($("#commentsModerationForm button[formaction$='comments!approve.rol'][value='" + id + "']"));
        $("#messages").shouldHave(text("1 approved."));
        $$(MODERATION_ROWS).findBy(text(body)).shouldNot(exist);
        openPath(moderation(otherHandle, "PUBLISHED"));
        $$(MODERATION_ROWS).findBy(text(body)).should(exist);
        logout();

        String after = anonymousPage(other);
        assertTrue(after.contains("id=\"comment-" + id + "\"") && after.contains(body),
                "the approved comment is not on the public page; the cached page did not expire");
    }

    // ------------------------------------------------------- criteria 5, 21

    /**
     * Criteria 5 and 21: once one of a reader's comments on a weblog is approved, their
     * next one there is published at once -- the page reloads at it, and it is in the
     * list as theirs to delete -- while their comment on another weblog is still held.
     */
    @Test
    void aTrustedReadersNextCommentShowsAtOnce() throws Exception {
        signInWithKeycloak(READER, open.fullPath());
        $(TEXTAREA).should(visible);
        publishedComment("Earning the weblog's trust " + nonce());

        String body = "Published at once " + nonce();
        openPath(open.path());
        $(TEXTAREA).should(visible).setValue(body);
        awaitNewPageAfter(() -> $(POST_BUTTON).click());

        String url = WebDriverRunner.url();
        String id = queryParameter(url, "c");
        assertNotNull(id, "a published comment did not reload the page at ?c=<id>: " + url);
        assertEquals("comment-" + id, URI.create(url).getFragment(), "the reload is not at the comment: " + url);
        $("article#comment-" + id).shouldHave(text(body));
        $("article#comment-" + id + " > header > button.comment-delete").should(visible);
        $("article#comment-" + id + " .comment-badge").shouldNot(exist);

        String elsewhere = "Held on another weblog " + nonce();
        openPath(other.path());
        $(TEXTAREA).should(visible).setValue(elsewhere);
        $(POST_BUTTON).click();
        $(MESSAGE).shouldHave(text(HELD_TEXT));
        $$(".comments-pending .comment-pending").findBy(text(elsewhere)).should(exist);
    }

    // -------------------------------------------------------- criteria 8, 24

    /**
     * Criteria 8 and 24: the owner replies to a reader's comment from the Comments page;
     * the reply is published, the owner's, and shows directly under the comment with the
     * Author badge.
     */
    @Test
    void anAuthorReplyShowsUnderTheComment() throws Exception {
        signInWithKeycloak(READER2, open.fullPath());
        $(TEXTAREA).should(visible);
        String question = "A question for the author " + nonce();
        String id = publishedComment(question);
        logout();

        String answer = "The author's answer " + nonce();
        loginAsAdmin();
        openPath(moderation(mainHandle, "PUBLISHED"));
        SelenideElement send = $("button[form='reply-" + id + "']").should(exist);
        send.closest("details").$("summary").click();
        $("textarea[form='reply-" + id + "']").should(visible).setValue(answer);
        clickAndAwaitNewPage(send);
        $("#messages").shouldHave(text("Reply posted."));
        $$(MODERATION_ROWS).findBy(text(question)).shouldHave(text(answer));
        logout();

        String replyId = adminApi("GET", comment(mainHandle, id), null, 200).get("reply").asString();
        JsonNode reply = adminApi("GET", comment(mainHandle, replyId), null, 200);
        assertEquals("PUBLISHED", reply.get("status").asString(), "an owner's reply is not published");
        assertTrue(reply.get("authorIsOwner").asBoolean(), "an owner's reply is not marked as the owner's");
        assertEquals(id, reply.get("replyTo").asString(), "the reply answers another comment");

        openPath(open.path());
        SelenideElement under = $("article#comment-" + id + " .comment-reply#comment-" + replyId);
        under.shouldHave(text(answer));
        under.$(".comment-badge").shouldHave(exactText(AUTHOR_BADGE));

        // Ruling R7: the shared, cached page names nobody by account. The reader's
        // username and email appear nowhere on it, and the owner's login name is not in
        // the comments section (an owner's username is half a credential).
        String readerName = adminApi("GET", comment(mainHandle, id), null, 200).get("authorUserName").asString();
        String page = anonymousPage(open);
        assertTrue(page.contains("id=\"comment-" + id + "\"") && page.contains("id=\"comment-" + replyId + "\""),
                "fixture: the comment and its reply are on the page");
        assertFalse(page.contains(readerName), "the anonymous page carries the reader's username " + readerName);
        assertFalse(page.contains(READER2 + "@example.com"), "the anonymous page carries the reader's email");
        assertFalse(section(page).contains(ADMIN_USERNAME), "the comments section carries the owner's username");
        assertFalse(section(page).contains("it-admin@example.invalid"), "the comments section carries the owner's email");
    }

    // ---------------------------------------------------------- criterion 24

    /**
     * Criterion 24, the rows a click changes: the owner hides a held comment on the Comments
     * page, where it leaves the Awaiting tab for the Hidden one, then deletes it there with
     * the confirmed Delete button, after which it is gone. A signed-in reader is refused the
     * page. {@link #READER}'s comments on {@code other} are always held (see the class
     * javadoc), so hiding one changes no other test's trust.
     */
    @Test
    void theOwnerHidesThenDeletesOnTheCommentsPage() throws Exception {
        signInWithKeycloak(READER, other.fullPath());
        $(TEXTAREA).should(visible);
        String body = "Soon hidden, then deleted " + nonce();
        Posted held = postThroughTheSessionEndpoint(body);
        assertEquals("PENDING", held.status(), "the reader is trusted on the other weblog");

        HttpResponse<String> refused = send("GET", moderation(otherHandle, null), null,
                Map.of("Cookie", "JSESSIONID=" + sessionCookie()), -1);
        String location = refused.headers().firstValue("Location").orElse("");
        assertTrue(refused.statusCode() == 403 || (refused.statusCode() == 302 && location.contains(ACCESS_DENIED)),
                "the Comments page answered a reader " + refused.statusCode() + " " + location);
        BrowserHealth.current().expectRefusal(MODERATION);
        BrowserHealth.current().expectRefusal(ACCESS_DENIED);
        openPath(moderation(otherHandle, null));
        $("#commentsModerationForm").shouldNot(exist);
        logout();

        loginAsAdmin();
        openPath(moderation(otherHandle, null));
        clickAndAwaitNewPage($("#commentsModerationForm button[formaction$='comments!hide.rol'][value='"
                + held.id() + "']"));
        $("#messages").shouldHave(text("1 hidden."));
        $$(MODERATION_ROWS).findBy(text(body)).shouldNot(exist);

        openPath(moderation(otherHandle, "HIDDEN"));
        $$(MODERATION_ROWS).findBy(text(body)).should(exist);
        SelenideElement delete = $("#commentsModerationForm button[formaction$='comments!delete.rol'][value='"
                + held.id() + "']").should(visible);
        assertNotNull(delete.getAttribute("data-confirm"), "Delete is not a confirmed button");
        awaitNewPageAfter(() -> {
            delete.click();
            Selenide.confirm();
        });
        $("#messages").shouldHave(text("1 deleted."));
        $$(MODERATION_ROWS).findBy(text(body)).shouldNot(exist);
        logout();

        assertEquals(404, adminApiStatus("GET", comment(otherHandle, held.id())), "the deleted comment survives");
    }

    // ---------------------------------------------------------- criterion 23

    /**
     * Criterion 23: a reader deletes their own comment from the page, and it goes with
     * the author's reply to it; the page offers no Delete on the reply, and deleting
     * another account's comment answers 404.
     */
    @Test
    void aReaderDeletesTheirComment() throws Exception {
        signInWithKeycloak(READER, open.fullPath());
        $(TEXTAREA).should(visible);
        String body = "Soon to be withdrawn " + nonce();
        String id = publishedComment(body);
        String answer = "An answer that goes with it " + nonce();
        String replyId = adminApi("POST", comment(mainHandle, id) + "/replies", json(Map.of("body", answer)), 201)
                .get("id").asString();

        openPath(open.path());
        SelenideElement mine = $("article#comment-" + id).shouldHave(text(body)).shouldHave(text(answer));
        SelenideElement delete = $("article#comment-" + id + " > header > button.comment-delete").should(visible);
        $("#comment-" + replyId + " button.comment-delete").shouldNot(exist);

        BrowserHealth.current().expectRefusal(DELETE_ENDPOINT + replyId + ".rol");
        assertEquals(404L, ((Number) Selenide.executeJavaScript(DELETE_SCRIPT, replyId)).longValue(),
                "a reader deleted another account's comment");

        delete.click();
        assertEquals("Delete your comment?", Selenide.confirm());
        mine.should(disappear);

        String page = anonymousPage(open);
        assertFalse(page.contains("id=\"comment-" + id + "\"") || page.contains(body),
                "the deleted comment is still on the page");
        assertFalse(page.contains("id=\"comment-" + replyId + "\"") || page.contains(answer),
                "the reply to a deleted comment is still on the page");
        assertEquals(404, adminApiStatus("GET", comment(mainHandle, id)), "the deleted comment survives");
        assertEquals(404, adminApiStatus("GET", comment(mainHandle, replyId)), "its reply survives");
    }

    // ---------------------------------------------------------- criterion 19

    /** Criterion 19: a closed entry shows its published comments and "Comments are closed", and no form. */
    @Test
    void aClosedEntryShowsCommentsAndNoForm() {
        openPath(closed.path());

        $$(SECTION + " article.comment").findBy(text(closedComment)).should(exist);
        $(SECTION + " .comments-closed").shouldHave(exactText(CLOSED_TEXT));
        $(SLOT).shouldNot(exist);
        $(SECTION + " textarea").shouldNot(exist);
        $("a.comments-sign-in").shouldNot(exist);
    }

    // ---------------------------------------------------------- criterion 22

    /**
     * Criterion 22: on its custom domain, a weblog's entry page shows its published
     * comments and says commenting is not available on this address, with no form and
     * no sign-in link. There, a signed-in session is told {@code custom-domain} and a
     * post answers 404 and stores nothing.
     */
    @Test
    void aCustomDomainWeblogShowsNoForm() throws Exception {
        openVhostPath("/entry/" + vhostEntry.anchor());
        $$(SECTION + " article.comment").findBy(text(vhostComment)).should(exist);
        $(SLOT + " .comments-note").shouldHave(exactText(UNAVAILABLE_TEXT));
        $(SECTION + " textarea").shouldNot(exist);
        $("a.comments-sign-in").shouldNot(exist);

        loginAsAdmin();
        openPath(open.path());
        String session = sessionCookie();
        JsonNode onSite = JSON.readTree(send("GET", SESSION_ENDPOINT + "?entry=" + open.id(), null,
                Map.of("Cookie", "JSESSIONID=" + session), 200).body());
        JsonNode csrf = onSite.get("csrf");
        assertNotNull(csrf, "the owner may not post on the site host: " + onSite);

        JsonNode onVhost = JSON.readTree(send("GET", SESSION_ENDPOINT + "?entry=" + vhostEntry.id(), null,
                Map.of("Host", VHOST, "Cookie", "JSESSIONID=" + session), 200).body());
        assertEquals("custom-domain", onVhost.path("reason").asString(), "the session answer: " + onVhost);
        assertFalse(onVhost.path("canPost").asBoolean(true), "posting was offered on a custom domain: " + onVhost);

        send("POST", POST_ENDPOINT, json(Map.of("entry", vhostEntry.id(), "body", "Not here " + nonce())),
                Map.of("Host", VHOST, "Cookie", "JSESSIONID=" + session,
                        csrf.get("header").asString(), csrf.get("token").asString()), 404);
        JsonNode stored = adminApi("GET", comments(vhostHandle) + "?entry=" + vhostEntry.id(), null, 200);
        assertEquals(1, stored.get("items").size(), "a refused post stored a comment: " + stored);
    }

    // ------------------------------------------------------- criteria 29, 30

    /**
     * Criteria 29 and 30: the account page sends an anonymous visitor to log in; it
     * refuses a display name that is an author's, whatever its case, and saves another,
     * which the reader's published comment then shows; and "Delete my account" removes
     * the account and its comments and ends the session. An account holding weblog
     * permissions (the seeded admin) is refused the delete.
     */
    @Test
    void theReaderChangesTheirNameAndDeletesTheirAccount() throws Exception {
        assertSentToLogIn(null, "an anonymous visitor reached the account page");

        signInWithKeycloak(READER2, open.fullPath());
        $(TEXTAREA).should(visible);
        String body = "Signed under a new name " + nonce();
        String id = publishedComment(body);
        assertTrue(articleIn(anonymousPage(open), id).contains("Colm Second"),
                "the comment is not signed with the provider's name");

        openPath(ACCOUNT);
        $("#accountNameForm").should(exist);
        $("#screenName").setValue("it admin");
        clickAndAwaitNewPage($("#accountNameForm button[type='submit']"));
        $("#errors").shouldHave(text("That name belongs to an author here."));

        String name = "Renamed " + nonce();
        $("#screenName").setValue(name);
        clickAndAwaitNewPage($("#accountNameForm button[type='submit']"));
        $("#messages").shouldHave(text("Your display name is saved."));
        assertTrue(articleIn(anonymousPage(open), id).contains(name),
                "the published comment does not show the new name; the cached page did not expire");

        openPath(ACCOUNT);
        $("#accountPage").shouldHave(text(body));
        String session = sessionCookie();
        awaitNewPageAfter(() -> {
            $("#accountDelete button[data-confirm]").click();
            Selenide.confirm();
        });

        assertSentToLogIn(session, "the deleted account's session still authenticates");
        assertFalse(anonymousPage(open).contains("id=\"comment-" + id + "\""),
                "the deleted account's comment is still on the page");
        assertEquals(404, adminApiStatus("GET", comment(mainHandle, id)), "the deleted account's comment survives");

        // The seeded admin holds weblog permissions as well as admin, and the member
        // refusal is the one named first: either way, no delete button.
        loginAsAdmin();
        openPath(ACCOUNT);
        $("#accountDelete").shouldHave(text("Leave your weblogs first"));
        $("#accountDelete button").shouldNot(exist);
    }

    // ---------------------------------------------------------- criterion 26

    /**
     * Criterion 26: with a token holding POST on the weblog, the API lists by status and
     * entry and pages, gets one comment, publishes, replies, hides and deletes it, and the
     * rendered page follows each change. A token limited to another weblog, and an unknown
     * id, get 404; a read-only token gets the API's 403 on a write. No answer carries an
     * email address.
     */
    @Test
    void moderationThroughTheApi() throws Exception {
        signInWithKeycloak(READER2, open.fullPath());
        $(TEXTAREA).should(visible);
        String body = "Three links hold it: https://a.example/ https://b.example/ https://c.example/ " + nonce();
        Posted held = postThroughTheSessionEndpoint(body);
        assertEquals("PENDING", held.status(), "a comment with three links was not held");
        logout();

        String answer = "Answered through the API " + nonce();
        List<String> answers = new ArrayList<>();
        Map<String, String> minted = new LinkedHashMap<>();
        try {
            String token = mint(minted, "POST", mainHandle);
            String elsewhere = mint(minted, "POST", WEBLOG_HANDLE);
            String readOnly = mint(minted, "READ", mainHandle);
            String list = comments(mainHandle);
            String item = comment(mainHandle, held.id());

            JsonNode pending = api(answers, token, "GET", list + "?status=PENDING&entry=" + open.id(), null, 200);
            assertTrue(ids(pending).contains(held.id()), "the held comment is not listed as PENDING: " + pending);
            JsonNode published = api(answers, token, "GET", list + "?status=PUBLISHED", null, 200);
            assertFalse(ids(published).contains(held.id()), "a held comment is listed as PUBLISHED");
            JsonNode first = api(answers, token, "GET", list + "?limit=1&offset=0", null, 200);
            JsonNode second = api(answers, token, "GET", list + "?limit=1&offset=1", null, 200);
            assertEquals(1, first.get("items").size(), "limit=1 answered " + first);
            assertTrue(first.get("hasMore").asBoolean(), "a weblog with two comments has no second page: " + first);
            assertNotEquals(ids(first), ids(second), "the second page repeats the first");

            JsonNode one = api(answers, token, "GET", item, null, 200);
            assertEquals("PENDING", one.get("status").asString());
            assertEquals(body, one.get("body").asString());
            assertEquals(open.id(), one.get("entryId").asString());
            assertFalse(one.get("authorIsOwner").asBoolean(), "a reader's comment is marked as an owner's");

            api(answers, elsewhere, "GET", list, null, 404);
            api(answers, elsewhere, "GET", item, null, 404);
            api(answers, token, "GET", comment(mainHandle, "no-such-comment"), null, 404);
            api(answers, readOnly, "PATCH", item, json(Map.of("status", "PUBLISHED")), 403);

            JsonNode approved = api(answers, token, "PATCH", item, json(Map.of("status", "PUBLISHED")), 200);
            assertEquals("PUBLISHED", approved.get("status").asString());
            assertTrue(anonymousPage(open).contains("id=\"comment-" + held.id() + "\""),
                    "the comment published through the API is not on the page");

            JsonNode reply = api(answers, token, "POST", item + "/replies", json(Map.of("body", answer)), 201);
            String replyId = reply.get("id").asString();
            assertEquals("PUBLISHED", reply.get("status").asString());
            assertTrue(reply.get("authorIsOwner").asBoolean(), "the API's reply is not the owner's");
            assertEquals(held.id(), reply.get("replyTo").asString());
            String withReply = articleIn(anonymousPage(open), held.id());
            assertTrue(withReply.contains("id=\"comment-" + replyId + "\"") && withReply.contains(answer),
                    "the reply is not under the comment: " + withReply);

            api(answers, token, "PATCH", item, json(Map.of("status", "HIDDEN")), 200);
            String hidden = anonymousPage(open);
            assertFalse(hidden.contains("id=\"comment-" + held.id() + "\""), "a hidden comment is on the page");
            assertFalse(hidden.contains(answer), "the reply to a hidden comment is on the page");

            api(answers, token, "DELETE", item, null, 204);
            api(answers, token, "GET", item, null, 404);
            api(answers, token, "GET", comment(mainHandle, replyId), null, 404);

            for (String answered : answers) {
                assertFalse(answered.contains("@example."), "an API answer carries an email address: " + answered);
            }
        } finally {
            // Best effort, unasserted: a failure here must not replace the test's own.
            for (String tokenId : minted.values()) {
                send("DELETE", "/api/v1/tokens/" + tokenId, null, Map.of("Authorization", adminBasicAuth()), -1);
            }
        }
    }

    // ---------------------------------------------------------- criterion 14

    /**
     * Criterion 14: with comments turned off on a weblog that has them, its entry pages
     * carry no comments section, and the Comments page still lists the comments. Turned
     * back on in a {@code finally}, since every other test here comments on that weblog.
     */
    @Test
    void commentsOffHidesTheSection() throws Exception {
        assertTrue(anonymousPage(closed).contains(closedComment), "before: the closed entry's comment is not shown");

        loginAsAdmin();
        setCommentsEnabled(mainHandle, false);
        try {
            for (Entry entry : List.of(open, closed)) {
                String page = anonymousPage(entry);
                assertFalse(page.contains("<section class=\"comments\""),
                        "comments are off, yet " + entry.path() + " has a comments section");
            }
            assertFalse(anonymousPage(closed).contains(closedComment), "comments are off, yet a comment shows");

            openPath(moderation(mainHandle, "PUBLISHED"));
            $("#commentsModerationForm input[name='ids'][value='" + closedCommentId + "']").should(exist);
        } finally {
            setCommentsEnabled(mainHandle, true);
        }
        assertTrue(anonymousPage(closed).contains(closedComment), "turned back on, the comment does not show");
    }

    // ------------------------------------------------------------ comments

    /**
     * Posts through the session endpoint as the signed-in browser (on an entry page with a
     * live form slot) and approves the comment through the API when it was held. Returns
     * its id: a published comment of this reader's, whatever their trust was.
     */
    private String publishedComment(String body) throws Exception {
        Posted posted = postThroughTheSessionEndpoint(body);
        if ("PENDING".equals(posted.status())) {
            adminApi("PATCH", comment(mainHandle, posted.id()), json(Map.of("status", "PUBLISHED")), 200);
        }
        return posted.id();
    }

    /** Posts {@code body} on the entry the browser shows, as {@link #POST_SCRIPT} says. */
    private Posted postThroughTheSessionEndpoint(String body) {
        String answer = Selenide.executeJavaScript(POST_SCRIPT, body);
        assertNotNull(answer, "the post script answered nothing");
        assertTrue(answer.startsWith("201 "), "the post was refused: " + answer);
        JsonNode created = JSON.readTree(answer.substring(4));
        return new Posted(created.get("id").asString(), created.get("status").asString());
    }

    /**
     * Posts as the signed-in owner through the page's own form and returns the comment's
     * id, read from the reload a published comment causes.
     */
    private String postAsOwnerThroughThePage(Entry entry, String body) {
        openPath(entry.path());
        $(TEXTAREA).should(visible).setValue(body);
        awaitNewPageAfter(() -> $(POST_BUTTON).click());
        String id = queryParameter(WebDriverRunner.url(), "c");
        assertNotNull(id, "the owner's comment did not reload the page at ?c=<id>: " + WebDriverRunner.url());
        $("article#comment-" + id).shouldHave(text(body));
        $("article#comment-" + id + " .comment-badge").shouldHave(exactText(AUTHOR_BADGE));
        return id;
    }

    /** The anonymous rendering of {@code entry}: no cookie, exactly what the page cache serves. */
    private String anonymousPage(Entry entry) throws Exception {
        return send("GET", entry.path(), null, Map.of(), 200).body();
    }

    /** The page's comments section, from its opening tag to its close. */
    private static String section(String page) {
        int start = page.indexOf("<section class=\"comments\"");
        assertTrue(start >= 0, "the page has no comments section");
        int end = page.indexOf("</section>", start);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    /** The HTML of one rendered comment, from its opening tag to its close. */
    private static String articleIn(String page, String id) {
        int start = page.indexOf("id=\"comment-" + id + "\"");
        assertTrue(start >= 0, "comment " + id + " is not on the page");
        int end = page.indexOf("</article>", start);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private static String moderation(String handle, String status) {
        return MODERATION + "?weblog=" + handle + (status == null ? "" : "&status=" + status);
    }

    // ----------------------------------------------------------------- API

    private static String comments(String handle) {
        return "/api/v1/weblogs/" + handle + "/comments";
    }

    private static String comment(String handle, String id) {
        return comments(handle) + "/" + id;
    }

    /** An API call as the seeded admin, over Basic auth as {@code RedirectIT} does. */
    private JsonNode adminApi(String method, String path, String body, int expected) throws Exception {
        return api(new ArrayList<>(), null, method, path, body, expected);
    }

    private int adminApiStatus(String method, String path) throws Exception {
        return send(method, path, null, Map.of("Authorization", adminBasicAuth()), -1).statusCode();
    }

    /**
     * An API call with a bearer token ({@code null}: the admin's Basic auth), asserting
     * the status and recording the answer's body.
     */
    private JsonNode api(List<String> answers, String token, String method, String path, String body,
            int expected) throws Exception {
        String authorization = token == null ? adminBasicAuth() : "Bearer " + token;
        HttpResponse<String> response = send(method, path, body,
                Map.of("Authorization", authorization, "Accept", "application/json"), expected);
        answers.add(response.body());
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    /** Mints a token for the admin, remembering its id in {@code minted} for revoking. */
    private String mint(Map<String, String> minted, String role, String weblog) throws Exception {
        String label = "CommentsIT " + role + " " + weblog;
        JsonNode token = JSON.readTree(send("POST", "/api/v1/tokens",
                json(Map.of("label", label, "role", role, "weblog", weblog)),
                Map.of("Authorization", adminBasicAuth()), 201).body());
        minted.put(label, token.get("info").get("id").asString());
        return token.get("token").asString();
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private static String adminBasicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(UTF_8));
    }

    private static String json(Map<String, String> fields) {
        return JSON.writeValueAsString(fields);
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * One request below {@link #baseUrl()}, asserting {@code expected} unless it is -1. A
     * {@code Host} header among {@code headers} addresses the custom domain.
     */
    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers,
            int expected) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20));
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (expected != -1) {
            assertEquals(expected, response.statusCode(), method + " " + path + " answered " + response.body());
        }
        return response;
    }

    /**
     * {@code sessionId} (or no session at all) is sent to log in from the account page:
     * a 302 to the login page, which is not how a signed-in account is refused.
     */
    private void assertSentToLogIn(String sessionId, String message) throws Exception {
        HttpResponse<String> response = send("GET", ACCOUNT, null,
                sessionId == null ? Map.of() : Map.of("Cookie", "JSESSIONID=" + sessionId), 302);
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.contains(LOGIN), message + ": sent to " + location);
    }

    /** The browser's Roller session id; the browser must be on a site-host page. */
    private static String sessionCookie() {
        Cookie cookie = WebDriverRunner.getWebDriver().manage().getCookieNamed("JSESSIONID");
        assertNotNull(cookie, "the browser holds no Roller session on " + WebDriverRunner.url());
        return cookie.getValue();
    }

    // -------------------------------------------------------------- weblogs

    /** Creates a weblog owned by the seeded admin, on the journal theme, with comments on. */
    private String createWeblogWithComments(String prefix) {
        String handle = prefix + nonce();
        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Comments IT " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        clickAndAwaitNewPage($("button[type='submit']"));
        $("#messages").should(exist);
        setCommentsEnabled(handle, true);
        return handle;
    }

    /** Sets "Allow comments" on Weblog Settings, saves, and reads it back. */
    private void setCommentsEnabled(String handle, boolean on) {
        openWeblogSettings(handle);
        SelenideElement box = $("input[name='bean.commentsEnabled']").should(exist);
        if (box.isSelected() != on) {
            box.click();
        }
        saveWeblogSettings();
        openWeblogSettings(handle);
        if (on) {
            $("input[name='bean.commentsEnabled']").shouldBe(checked);
        } else {
            $("input[name='bean.commentsEnabled']").shouldNotBe(checked);
        }
    }

    private void setCustomDomain(String handle, String domain) {
        openWeblogSettings(handle);
        $("input[name='bean.customDomain']").setValue(domain);
        saveWeblogSettings();
    }

    private void openWeblogSettings(String handle) {
        openPath("/roller-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.name']").should(visible);
    }

    private void saveWeblogSettings() {
        clickAndAwaitNewPage($("button[type='submit'].btn-primary").should(visible));
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(), "saving the weblog settings reported an error: "
                + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
    }

    /** Publishes one entry and returns it, its id read from the editor the publish lands on. */
    private Entry publishEntry(String handle, String title) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        clickAndAwaitNewPage($("button[formaction$='entryAdd!publish.rol']"));
        String permalink = $(PERMALINK).should(exist).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        String id = $("#entry input[name='bean.id']").should(exist).getValue();
        return new Entry(id, handle, permalink.substring(permalink.lastIndexOf('/') + 1));
    }

    /** Ticks "Comments closed" in the entry editor's SEO drawer and saves the published entry. */
    private void closeComments(Entry entry) {
        openPath("/roller-ui/authoring/entryEdit.rol?weblog=" + entry.handle() + "&bean.id=" + entry.id());
        $("#entry").should(exist);
        $("a[data-bs-target='#collapseSeo']").click();
        $("#comments_closed").should(visible).click();
        clickAndAwaitNewPage($("button[formaction$='entryEdit!publish.rol']"));
        $("#comments_closed").should(exist).shouldBe(checked);
    }

    /** Permanently deletes a weblog through the real confirm-and-remove flow. */
    private void removeWeblog(String handle) {
        openPath("/roller-ui/authoring/weblogRemove.rol?weblog=" + handle);
        clickAndAwaitNewPage($("form[action$='/roller-ui/authoring/weblogRemove!remove.rol'] button[type='submit']")
                .should(visible));
        $("#messages").should(exist);
    }

    // ---------------------------------------------------------------- urls

    /** Navigates the browser to {@code path} on {@link #VHOST}, over plain HTTP on the run's port. */
    private void openVhostPath(String path) {
        Selenide.open("http://" + VHOST + ":" + URI.create(baseUrl()).getPort() + contextPath() + path);
        BrowserHealth.current().settle();
    }

    /** The context path, empty at the root: what every path a browser shows starts with. */
    private static String contextPath() {
        String path = URI.create(baseUrl()).getPath();
        return path == null ? "" : path;
    }

    private static String currentPath() {
        return URI.create(WebDriverRunner.url()).getPath();
    }

    private static String queryParameter(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            }
        }
        return null;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
