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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What waits for the author, shown without being asked (0.18.0 Part A): a reader comment held for approval
 * puts a count on that weblog's Comments rail entry and a link on its home row, and approving it takes both
 * away; a contact-form inquiry does the same for Inquiries until the inbox has been opened.
 *
 * <p>The reader comes from the run's Keycloak ({@code reader-it}); the sign-in callback is built from the
 * Absolute URL, which the IT seed sets to the run's base URL, as production always has it set. The class
 * holds {@link PressroomIT#GLOBAL_CONFIG} for read, so no class that changes the Absolute URL (or any other
 * global setting) runs beside it. Each test owns a weblog.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AdminAttentionIT extends PressroomIT {

    private static final String READER = "reader-it";
    private static final String CONTACT_ENDPOINT = "/pressroom-ui/rendering/contact.rol";
    private static final String RAIL = "#adminRail";

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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Test
    void aHeldCommentBadgesCommentsAndApprovingItClearsTheBadge() throws Exception {
        String suffix = nonce();
        loginAsAdmin();
        String handle = createWeblog("attncmt" + suffix);
        enableComments(handle);
        String permalink = publishEntry(handle, "Attention " + suffix);
        logout();

        signInWithKeycloak(READER, contextPath() + permalink);
        $(".comment-form textarea").should(visible);
        String answer = Selenide.executeJavaScript(POST_SCRIPT, "Held for approval " + suffix);
        assertNotNull(answer, "the post script answered nothing");
        assertTrue(answer.startsWith("201 ") && answer.contains("PENDING"), "the comment was not held: " + answer);
        logout();

        loginAsAdmin();
        openPath("/pressroom-ui/app/");
        SelenideElement row = $$(".weblog-row").findBy(text(handle)).should(exist);
        row.$$("a").findBy(text("1 awaiting approval")).should(exist);
        $(".attention-summary").shouldHave(text("awaiting approval across"));

        openPath(AdminApp.weblog(handle, "/entries"));
        $(RAIL + " a[aria-label='Comments, 1 awaiting approval']").should(visible);

        openPath(AdminApp.comments(handle));
        $$("button").findBy(text("Approve")).should(visible).click();
        $("#messages").shouldHave(text("1 approved."));
        $(RAIL + " a[aria-label='Comments, 1 awaiting approval']").shouldNot(exist);
        $(RAIL + " a[href$='/comments']").shouldHave(exactText("Comments"));

        openPath("/pressroom-ui/app/");
        $$(".weblog-row").findBy(text(handle)).$$("a").findBy(text("awaiting approval")).shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    @Test
    void anInquiryBadgesInquiriesUntilTheInboxIsOpened() throws Exception {
        String suffix = nonce();
        loginAsAdmin();
        String handle = createWeblog("attninq" + suffix);
        logout();
        postInquiry(handle, "Ann " + suffix, "Subject " + suffix, "Is the cottage free in May? " + suffix);

        loginAsAdmin();
        openPath("/pressroom-ui/app/");
        $$(".weblog-row").findBy(text(handle)).$$("a").findBy(text("1 new inquiry")).should(exist);

        openPath(AdminApp.weblog(handle, "/entries"));
        $(RAIL + " a[aria-label='Inquiries, 1 new']").should(visible);

        $(RAIL + " a[href$='/inbox']").click();
        $("table.pressroomtable").shouldHave(text("Subject " + suffix));
        $(".new-pill").shouldHave(exactText("New"));
        // The mark stays on the page for this visit, while the sidebar count clears without a reload.
        $(RAIL + " a[aria-label='Inquiries, 1 new']").shouldNot(exist);
        $(".new-pill").should(exist);

        // The next visit shows it handled: no pill, and the home has no inquiry link for the weblog.
        openPath(AdminApp.weblog(handle, "/inbox"));
        $("table.pressroomtable").shouldHave(text("Subject " + suffix));
        $(".new-pill").shouldNot(exist);
        openPath("/pressroom-ui/app/");
        $$(".weblog-row").findBy(text(handle)).$$("a").findBy(text("new inquiry")).shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    // ------------------------------------------------------------- helpers

    private String createWeblog(String handle) {
        createWeblogOnTheReactScreen(handle, "Attention IT " + handle, "journal");
        return handle;
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

    private void postInquiry(String handle, String name, String subject, String message) throws Exception {
        String json = "{\"weblog\":\"" + handle + "\",\"name\":\"" + name + "\",\"email\":\"reader@example.invalid\","
                + "\"subject\":\"" + subject + "\",\"message\":\"" + message + "\",\"website\":\"\","
                + "\"elapsedMs\":5000,\"source\":\"/" + handle + "/contact\"}";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + CONTACT_ENDPOINT))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        assertEquals(204, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(),
                "a genuine submission must be accepted");
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
