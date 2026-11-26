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
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PagesMediaApp;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code [contact]} shortcode end to end: a page carrying it renders the
 * client-injected form ({@code #showAudienceAssets}), a real browser submit
 * lands in the per-weblog Inquiries inbox, and the honeypot answers exactly
 * like success while storing nothing.
 *
 * <p>Owns its own weblog rather than the seeded {@code it_weblog} (see
 * {@code PageIT} for the same reasoning) and follows its page-creation flow:
 * a page is a {@code WeblogEntry}, created as a draft through the real Pages
 * editor and then published, served at {@code /<handle>/<slug>} directly
 * (never through {@code /page/...}, which is a CUSTOM-template route).
 *
 * <p>The honeypot check goes through raw {@code HttpClient} rather than the
 * browser: the "website" field is never filled by a human (or by this test's
 * own field-filling), so exercising it is naturally a direct POST, matching
 * the {@code PageIT} precedent for deliberate non-2xx/JSON assertions.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class ContactFormIT extends PressroomIT {

    private static final String CONTACT_ENDPOINT = "/pressroom-ui/rendering/contact.rol";

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void theContactFormReachesTheInboxAndTheHoneypotDropsSilently() throws Exception {
        String suffix = nonce();
        String name = "Reader " + suffix;
        String email = "reader" + suffix + "@example.invalid";
        String message = "Contact message " + suffix + ".";

        loginAsAdmin();
        String handle = createWeblog();
        createAndPublishContactPage(handle);
        logout();

        // --- the injected form, as an anonymous reader sees it ---------------
        openPath("/" + handle + "/contact");

        $("form.contact-form input[name='name']").should(visible);
        $("form.contact-form input[name='email']").should(visible);
        $("form.contact-form input[name='subject']").should(visible);
        $("form.contact-form textarea[name='message']").should(visible);
        $("form.contact-form input[name='website']").should(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // The server refuses a submit faster than a human could type
        // (contact.form.min.seconds, default 3s) -- clear that before filling
        // the form so the real submission below is not itself mistaken for a bot.
        sleepFor(Duration.ofMillis(3200));

        $("form.contact-form input[name='name']").setValue(name);
        $("form.contact-form input[name='email']").setValue(email);
        $("form.contact-form textarea[name='message']").setValue(message);
        $("form.contact-form button[type='submit']").click();

        $("form.contact-form .audience-message")
                .shouldHave(text("Thanks — your message has been sent."));
        BrowserHealth.current().settle();

        // --- the Inquiries admin screen ---------------------------------------
        loginAsAdmin();
        openPath("/pressroom-ui/app/weblogs/" + handle + "/inbox");
        $("table.pressroomtable").shouldHave(text(name));
        $("table.pressroomtable").shouldHave(text(message));

        $(".submission-select").should(exist).click();
        // Deleting an inquiry is permanent -- there is no trash for these --
        // so the button opens a confirmation modal rather than deleting on the
        // first click. The real submit lives inside it.
        $("#submissionsDeleteSelected").click();
        // The React confirmation modal (a ConfirmDialog), whose destructive button deletes.
        $(".modal.show").shouldBe(visible);
        $(".modal.show .modal-footer button.btn-danger").click();
        $("#messages").should(exist);
        $$(".submission-select").shouldHave(CollectionCondition.size(0));
        // The empty state is a SIBLING of the table, not a row inside it: with
        // no inquiries the <table> is not rendered at all, so asserting on
        // table.pressroomtable here would wait for an element that never appears.
        $(".empty-state").shouldHave(text("No inquiries yet."));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- the honeypot, via raw HTTP: answers like success, stores nothing -
        String honeypotJson = "{"
                + "\"weblog\":\"" + handle + "\","
                + "\"name\":\"Bot " + suffix + "\","
                + "\"email\":\"bot" + suffix + "@example.invalid\","
                + "\"subject\":\"\","
                + "\"message\":\"spam " + suffix + "\","
                + "\"website\":\"http://spam.example\","
                + "\"elapsedMs\":5000,"
                + "\"source\":\"/" + handle + "/contact\""
                + "}";
        assertEquals(204, postJson(CONTACT_ENDPOINT, honeypotJson),
                "a honeypot-filled submission must answer exactly like success");

        openPath("/pressroom-ui/app/weblogs/" + handle + "/inbox");
        $$(".submission-select").shouldHave(CollectionCondition.size(0));
        $(".empty-state").shouldHave(text("No inquiries yet."));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * The inbox's bulk delete: a long message is cut with a way to read it all; ticking some inquiries and
     * cancelling the confirmation deletes nothing; confirming deletes exactly the ticked ones, says how many,
     * and the rest are still there after a reload; "select all" then clears the inbox to its empty state.
     */
    @Test
    void theInboxDeletesTheSelectedInquiriesOnlyAndShowsLongMessagesInFull() throws Exception {
        String suffix = nonce();
        String handle = createWeblogAsAdmin();
        String longTail = "END-OF-LONG-" + suffix;
        String longMessage = ("A long inquiry about a booking. ".repeat(8)) + longTail;
        assertTrue(longMessage.length() > 140);
        try {
            logout();
            postInquiry(handle, "Ann " + suffix, "Subject A " + suffix, longMessage);
            postInquiry(handle, "Ben " + suffix, "Subject B " + suffix, "Short message B " + suffix);
            postInquiry(handle, "Cy " + suffix, "Subject C " + suffix, "Short message C " + suffix);

            loginAsAdmin();
            openPath("/pressroom-ui/app/weblogs/" + handle + "/inbox");
            $$(".submission-select").shouldHave(CollectionCondition.size(3));

            // --- the long message is cut, and the whole of it is one click away
            $("table.pressroomtable").shouldNotHave(text(longTail));
            $("table.pressroomtable details summary").shouldHave(text("Show full message")).click();
            $("table.pressroomtable details p").shouldHave(text(longTail));

            // --- tick A and B, cancel: nothing is deleted
            $("input[aria-label='Subject A " + suffix + "']").click();
            $("input[aria-label='Subject B " + suffix + "']").click();
            $("#submissionsDeleteSelected").click();
            $(".modal.show").shouldBe(visible).shouldHave(text("2"));
            $(".modal.show .modal-footer button.btn-secondary").click();
            $(".modal.show").should(com.codeborne.selenide.Condition.disappear);
            $$(".submission-select").shouldHave(CollectionCondition.size(3));

            // --- confirm: exactly A and B go, C stays, across a reload
            $("#submissionsDeleteSelected").click();
            $(".modal.show .modal-footer button.btn-danger").click();
            $("#messages").shouldHave(text("Inquiries deleted: 2."));
            $$(".submission-select").shouldHave(CollectionCondition.size(1));
            openPath("/pressroom-ui/app/weblogs/" + handle + "/inbox");
            $$(".submission-select").shouldHave(CollectionCondition.size(1));
            $("table.pressroomtable").shouldHave(text("Subject C " + suffix));
            $("table.pressroomtable").shouldNotHave(text("Subject A " + suffix));
            $("table.pressroomtable").shouldNotHave(text("Subject B " + suffix));

            // --- select all, delete: the empty state
            $("#selectAllSubmissions").click();
            $("#submissionsDeleteSelected").click();
            $(".modal.show .modal-footer button.btn-danger").click();
            $("#messages").shouldHave(text("Inquiries deleted: 1."));
            $(".empty-state").shouldHave(text("No inquiries yet."));
        } finally {
            loginAsAdmin();
            openPath("/pressroom-ui/app/weblogs/" + handle + "/remove");
            $("button.btn-danger").should(visible).click();
            $("h2.mm_weblog_name").should(exist);
            $$("h2.mm_weblog_name .data").findBy(com.codeborne.selenide.Condition.exactText(handle))
                    .shouldNot(exist);
            logout();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String createWeblogAsAdmin() {
        loginAsAdmin();
        return createWeblog();
    }

    /** One genuine submission over the public endpoint, as the page script sends it. */
    private void postInquiry(String handle, String name, String subject, String message) throws Exception {
        String json = "{\"weblog\":\"" + handle + "\",\"name\":\"" + name + "\",\"email\":\"reader@example.invalid\","
                + "\"subject\":\"" + subject + "\",\"message\":\"" + message + "\",\"website\":\"\","
                + "\"elapsedMs\":5000,\"source\":\"/" + handle + "/contact\"}";
        assertEquals(204, postJson(CONTACT_ENDPOINT, json), "a genuine submission must be accepted");
    }

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "contactit" + nonce();

        createWeblogOnTheReactScreen(handle, "CFIT Weblog " + handle, "journal");
        return handle;
    }

    /**
     * Creates a page slugged {@code contact} whose body is just the
     * {@code [contact]} shortcode, then publishes it -- the two-step
     * draft-then-publish flow {@code PageIT} exercises.
     */
    private void createAndPublishContactPage(String handle) {
        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("contact");
        $("#page_bean_title").setValue("Contact");
        Editor.setText("[contact]");
        saveOpenPage();

        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        // Scoped to the pages table itself: the admin header carries its own
        // "visit site" link naming the weblog, and a $$("a") search of the
        // WHOLE page can match that one first if the weblog's own name
        // happens to contain the page title as a substring.
        $$("tr.page-row td a").findBy(text("Contact")).should(exist).click();
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();
    }

    private void saveOpenPage() {
        PagesMediaApp.savePage();
        BrowserHealth.current().settle();
    }

    /** Posts a JSON body to a path on the instance under test and returns the HTTP status. */
    private int postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private static void sleepFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out the bot timer", e);
        }
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
