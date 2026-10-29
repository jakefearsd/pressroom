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
        openPath("/pressroom-ui/authoring/submissions.rol?weblog=" + handle);
        $("table.rollertable").shouldHave(text(name));
        $("table.rollertable").shouldHave(text(message));

        $(".submission-select").should(exist).click();
        // Deleting an inquiry is permanent -- there is no trash for these --
        // so the button opens a confirmation modal rather than deleting on the
        // first click. The real submit lives inside it.
        $("#submissionsDeleteSelected").click();
        $("#submissions-delete-modal").shouldBe(visible);
        $("#submissionsDeleteConfirm").click();
        $("#messages").should(exist);
        $$(".submission-select").shouldHave(CollectionCondition.size(0));
        // The empty state is a SIBLING of the table, not a row inside it: with
        // no inquiries the <table> is not rendered at all, so asserting on
        // table.rollertable here would wait for an element that never appears.
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

        openPath("/pressroom-ui/authoring/submissions.rol?weblog=" + handle);
        $$(".submission-select").shouldHave(CollectionCondition.size(0));
        $(".empty-state").shouldHave(text("No inquiries yet."));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    // ---------------------------------------------------------------- helpers

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "contactit" + nonce();

        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("CFIT Weblog " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /**
     * Creates a page slugged {@code contact} whose body is just the
     * {@code [contact]} shortcode, then publishes it -- the two-step
     * draft-then-publish flow {@code PageIT} exercises.
     */
    private void createAndPublishContactPage(String handle) {
        openPath("/pressroom-ui/authoring/pageEdit.rol?weblog=" + handle);
        $("#pageEditForm").should(exist);
        $("#page_bean_slug").setValue("contact");
        $("#page_bean_title").setValue("Contact");
        Editor.setText("[contact]");
        saveOpenPage();

        openPath("/pressroom-ui/authoring/pages.rol?weblog=" + handle);
        $("#pageRemoveForm").should(exist);
        // Scoped to the pages table itself: the admin header carries its own
        // "visit site" link naming the weblog, and a $$("a") search of the
        // WHOLE page can match that one first if the weblog's own name
        // happens to contain the page title as a substring.
        $$("#pageRemoveForm a").findBy(text("Contact")).should(exist).click();
        $("#pageEditForm").should(exist);
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();
    }

    private void saveOpenPage() {
        $("#pageEditForm button[type='submit']").click();
        $("#messages").should(exist);
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
