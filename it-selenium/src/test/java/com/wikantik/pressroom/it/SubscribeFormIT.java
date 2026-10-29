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
import java.util.UUID;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code [subscribe]}/{@code #showSubscribeForm} newsletter block: a
 * weblog with a list uuid configured shows the footer block and its
 * client-injected form ({@code #showAudienceAssets}); clearing the uuid
 * removes it; the forwarding endpoint fails closed without Listmonk and 404s
 * for a uuid no weblog owns; and a real browser submit reaches that same
 * server-built endpoint and shows the graceful "Sorry" message for the 503.
 *
 * <p>Owns its own weblog rather than the seeded {@code it_weblog}, and never
 * switches that seeded weblog's theme -- per the suite-wide rule.
 *
 * <p>The 404/503-by-status checks go through raw {@code HttpClient}, not the
 * browser: the forward legitimately fails in this environment (no Listmonk
 * configured, {@code newsletter.listmonk.baseurl} blank -- see {@code
 * pressroom-it.properties}), and asserting an exact status code from a browser
 * {@code fetch} would need reading the response back out of page script for
 * no benefit raw HTTP doesn't already give directly.
 *
 * <p>The one browser-driven submit below is a different, narrower claim:
 * that {@code data-endpoint} -- not a client-guessed absolute-root path --
 * is what the injected form actually posts to, and that the graceful-failure
 * message the reader sees is wired correctly to a non-2xx response. A 503
 * <em>response</em> is not a failed <em>request</em> in {@code BrowserHealth}'s
 * vocabulary -- {@code assertNoFailedRequests} only catches a request that
 * never got a response at all (a refused stylesheet, a blocked resource; see
 * its own javadoc) -- so it stays asserted unconditionally. {@code
 * assertNoBrokenResources} DOES flag any 4xx/5xx sub-resource, so the
 * deliberate 503 has to be scoped out with {@code expectRefusal}, exactly
 * the {@code MultiUserJourneyIT} precedent for a refusal the test asked for
 * on purpose.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class SubscribeFormIT extends PressroomIT {

    private static final String SUBSCRIBE_ENDPOINT = "/newsletter/subscribe";

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void theSubscribeBlockAppearsWithAListAndDisappearsWithoutOne() throws Exception {
        String uuid = UUID.randomUUID().toString();

        loginAsAdmin();
        String handle = createWeblog();
        setNewsletterUuid(handle, uuid);
        logout();

        // --- the footer block, as an anonymous reader sees it -----------------
        openPath("/" + handle + "/");
        $(".newsletter-subscribe-block").should(exist);
        $(".subscribe-form-slot").shouldHave(attribute("data-list-uuid", uuid));
        // Server-built, context-path-aware (SubscribeShortcode/#showSubscribeForm),
        // never a client-guessed absolute-root "/newsletter/subscribe" -- that
        // 404s under any non-root context path. baseUrl() already carries the
        // context path (e.g. ".../pressroom"), so its own path component is what
        // the injected form must be pointed at.
        String expectedEndpoint = URI.create(baseUrl()).getPath() + SUBSCRIBE_ENDPOINT;
        $(".subscribe-form-slot").shouldHave(attribute("data-endpoint", expectedEndpoint));
        $(".newsletter-subscribe-block form.newsletter-subscribe input[name='email']")
                .should(visible);
        $(".newsletter-subscribe-block form.newsletter-subscribe input[name='website']")
                .should(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- raw HTTP: fails closed without Listmonk; 404s for an unknown list
        assertEquals(503, postSubscribe(uuid, 5000),
                "an unconfigured Listmonk must fail closed (503), not silently succeed");
        assertEquals(404, postSubscribe(UUID.randomUUID().toString(), 5000),
                "a list uuid matching no weblog must 404");

        // --- the SAME endpoint, this time driven by a real browser submit ------
        // Clears the naive-bot timer (MIN_ELAPSED_MS, 3s) first, as ContactFormIT
        // does, so this genuine submission is not itself mistaken for automation
        // and short-circuited into the honeypot's look-alike-success branch --
        // this test wants the real unconfigured-Listmonk 503 to be exercised.
        sleepFor(Duration.ofMillis(3200));
        $(".newsletter-subscribe-block form.newsletter-subscribe input[name='email']")
                .setValue("reader@example.invalid");
        BrowserHealth.current().expectRefusal(SUBSCRIBE_ENDPOINT);
        $(".newsletter-subscribe-block form.newsletter-subscribe button[type='submit']").click();

        $(".newsletter-subscribe-block .audience-message")
                .shouldHave(text("Sorry, that did not work. Please try again later."));
        BrowserHealth.current().settle();
        // The 503 IS a completed response, not a request that produced no
        // response at all, so this must still pass unconditionally.
        BrowserHealth.current().assertNoFailedRequests();
        // The 503 status IS a broken sub-resource by BrowserHealth's status-code
        // rule, and it is the one this test asked for on purpose -- scoped out
        // above, so this passes too.
        BrowserHealth.current().assertNoBrokenResources();

        // --- clearing the uuid removes the block -------------------------------
        loginAsAdmin();
        setNewsletterUuid(handle, "");
        logout();

        openPath("/" + handle + "/");
        $(".newsletter-subscribe-block").shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    // ---------------------------------------------------------------- helpers

    /** Posts a subscribe request for one list uuid and returns the HTTP status. */
    private int postSubscribe(String uuid, long elapsedMs) throws Exception {
        String json = "{"
                + "\"email\":\"reader@example.invalid\","
                + "\"list_uuids\":[\"" + uuid + "\"],"
                + "\"website\":\"\","
                + "\"elapsedMs\":" + elapsedMs
                + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + SUBSCRIBE_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "subscribeit" + nonce();

        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Subscribe " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Sets (or, given a blank uuid, clears) the weblog's newsletter list uuid through Settings. */
    private void setNewsletterUuid(String handle, String uuid) {
        openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.newsletterListUuid']").should(visible).setValue(uuid);
        $("button[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static void sleepFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out the bot timer", e);
        }
    }
}
