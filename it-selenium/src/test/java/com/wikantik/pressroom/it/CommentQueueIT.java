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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-blog approval queue (0.18.0 Part B): comments held on two blogs appear together at
 * {@code /comments}, each row naming its blog; approving one takes it out of the queue and drops that blog's
 * rail badge, and the other blog's stays.
 *
 * <p>Set up as {@link AdminAttentionIT} is: the reader is the run's Keycloak {@code reader-it}, the Absolute
 * URL is the run's base URL, and the class holds {@link PressroomIT#GLOBAL_CONFIG} exclusively.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class CommentQueueIT extends PressroomIT {

    private static final String READER = "reader-it";
    private static final String RAIL = "#adminRail";
    private static final String ROWS = "#comment-queue-marker tbody tr";

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

    private boolean fixtureStarted;
    private boolean fixtureBuilt;
    private boolean absoluteUrlSet;
    private String priorAbsoluteUrl;

    /** Sets the Absolute URL once, for the reader sign-in; see the class javadoc. */
    @BeforeEach
    void setTheAbsoluteUrlOnce() {
        if (fixtureStarted) {
            assertTrue(fixtureBuilt, "the class fixture failed in an earlier test; see that failure");
            return;
        }
        fixtureStarted = true;
        loginAsAdmin();
        priorAbsoluteUrl = setSiteAbsoluteUrl(baseUrl());
        absoluteUrlSet = true;
        logout();
        fixtureBuilt = true;
    }

    @AfterAll
    void restoreTheAbsoluteUrl() {
        inBrowserAfterAll(() -> {
            if (absoluteUrlSet) {
                loginAsAdmin();
                setSiteAbsoluteUrl(priorAbsoluteUrl);
                logout();
            }
        });
    }

    @Test
    void commentsOnTwoBlogsQueueTogetherAndApprovingOneDropsItsBadge() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String first = "queuea" + suffix;
        String second = "queueb" + suffix;
        loginAsAdmin();
        createWeblogOnTheReactScreen(first, "Queue A " + suffix, "journal");
        enableComments(first);
        String firstPermalink = publishEntry(first, "Queue entry A " + suffix);
        createWeblogOnTheReactScreen(second, "Queue B " + suffix, "journal");
        enableComments(second);
        String secondPermalink = publishEntry(second, "Queue entry B " + suffix);
        logout();

        signInWithKeycloak(READER, contextPath() + firstPermalink);
        hold("Held on A " + suffix);
        openPath(secondPermalink);
        hold("Held on B " + suffix);
        logout();

        loginAsAdmin();
        openPath("/pressroom-ui/app/comments");
        $("#comment-queue-marker").should(exist);
        SelenideElement rowA = $$(ROWS).findBy(text("Held on A " + suffix)).should(visible);
        SelenideElement rowB = $$(ROWS).findBy(text("Held on B " + suffix)).should(visible);
        rowA.shouldHave(text("Queue A " + suffix));
        rowB.shouldHave(text("Queue B " + suffix));

        rowA.$$("button").findBy(text("Approve")).click();
        $("#messages").shouldHave(text("1 approved."));
        $$(ROWS).findBy(text("Held on A " + suffix)).shouldNot(exist);
        $$(ROWS).findBy(text("Held on B " + suffix)).should(visible);

        openPath(AdminApp.weblog(first, "/entries"));
        $(RAIL + " a[aria-label='Comments, 1 awaiting approval']").shouldNot(exist);
        openPath(AdminApp.weblog(second, "/entries"));
        $(RAIL + " a[aria-label='Comments, 1 awaiting approval']").should(visible);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /** Posts a comment as the signed-in reader on the page shown; it must be held, not published. */
    private void hold(String comment) {
        $(".comment-form textarea").should(visible);
        String answer = Selenide.executeJavaScript(POST_SCRIPT, comment);
        assertNotNull(answer, "the post script answered nothing");
        assertTrue(answer.startsWith("201 ") && answer.contains("PENDING"), "the comment was not held: " + answer);
    }

    private void enableComments(String handle) {
        openPath("/pressroom-ui/app/weblogs/" + handle + "/settings");
        SelenideElement box = $("input[name='bean.commentsEnabled']").should(exist);
        if (!box.isSelected()) {
            box.click();
        }
        $("button[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
    }

    /** Publishes one entry and answers its permalink path below the context. */
    private String publishEntry(String handle, String title) {
        openPath(AdminApp.newEntry(handle));
        AdminApp.write(title, "Body of " + title);
        AdminApp.publishButton().click();
        String permalink = $(AdminApp.PERMALINK).should(exist).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return "/" + handle + "/entry/" + permalink.substring(permalink.lastIndexOf('/') + 1);
    }

    private static String contextPath() {
        String path = URI.create(baseUrl()).getPath();
        return path == null ? "" : path;
    }
}
