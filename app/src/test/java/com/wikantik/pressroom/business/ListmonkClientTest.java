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
package com.wikantik.pressroom.business;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ListmonkClientTest {

    private static final String LIST_UUID = "2f0f1b0c-1111-2222-3333-444455556666";
    private static final String API_USER = "roller-admin";
    private static final String API_TOKEN = "s3cr3t-token";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int respondWith = 200;

    /** Every request path this server saw, in order -- proves call ordering. */
    private final List<String> requestsSeen = new ArrayList<>();
    /** The Authorization header the campaign endpoints saw, captured for the basic-auth assertion. */
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    /** Which step (lists/campaigns/status) should answer with {@code respondWith} instead of success. */
    private volatile String failingStep;
    /** The lists response body, overridable per test so a uuid can be "missing". */
    private volatile String listsResponseBody =
            "{\"data\":{\"results\":[{\"id\":7,\"uuid\":\"" + LIST_UUID + "\"}]}}";
    private final AtomicReference<String> campaignBody = new AtomicReference<>();
    private final AtomicReference<String> statusBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/subscription", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondWith, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.createContext("/api/lists", exchange -> {
            requestsSeen.add("lists");
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "lists", listsResponseBody);
        });
        server.createContext("/api/campaigns/", exchange -> {
            // /api/campaigns/{id}/status
            requestsSeen.add("status");
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            statusBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "status", "{}");
        });
        server.createContext("/api/campaigns", exchange -> {
            requestsSeen.add("campaigns");
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            campaignBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "campaigns", "{\"data\":{\"id\":42}}");
        });
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String step, String okBody)
            throws IOException {
        int status = step.equals(failingStep) ? respondWith : 200;
        byte[] out = (status == 200 ? okBody : "{\"message\":\"boom\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ListmonkClient client() {
        return new ListmonkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());
    }

    private ListmonkClient campaignClient() {
        return new ListmonkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                API_USER, API_TOKEN, HttpClient.newHttpClient());
    }

    @Test
    void subscribePostsTheListmonkPublicShapeAndReturnsTheStatus() throws Exception {
        int status = client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666");

        assertEquals(200, status);
        assertTrue(lastBody.get().contains("\"email\":\"reader@example.com\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"list_uuids\":[\"2f0f1b0c-1111-2222-3333-444455556666\"]"),
                lastBody.get());
    }

    @Test
    void aConflictPassesThroughAsItsOwnStatus() throws Exception {
        respondWith = 409;
        assertEquals(409, client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666"));
    }

    @Test
    void anUnconfiguredClientReportsItself() {
        assertTrue(new ListmonkClient("", HttpClient.newHttpClient()).isUnconfigured());
        assertTrue(new ListmonkClient(null, HttpClient.newHttpClient()).isUnconfigured());
    }

    /**
     * A quote or backslash in the email must not break the hand-built JSON
     * body -- {@code jsonString} is the only thing standing between
     * {@code subscribe}'s two interpolated values and a malformed request.
     * Round-tripping the captured body through a real JSON parser is a
     * stronger check than matching an escaped literal: it fails on ANY
     * escaping bug, not just the one this hand-picked value happens to hit.
     */
    @Test
    void aQuoteAndBackslashInTheEmailAreEscapedSoTheBodyStaysValidJson() throws Exception {
        String tricky = "weird\"name\\test@example.com";

        client().subscribe(tricky, "2f0f1b0c-1111-2222-3333-444455556666");

        JsonNode body = new ObjectMapper().readTree(lastBody.get());
        assertEquals(tricky, body.get("email").asString(), lastBody.get());
    }

    // ------------------------------------------------------------- sendCampaign

    @Test
    void isCampaignConfiguredNeedsBaseUrlAndBothCredentials() {
        assertTrue(campaignClient().isCampaignConfigured());
        assertFalse(new ListmonkClient("http://x", "", API_TOKEN, HttpClient.newHttpClient())
                .isCampaignConfigured(), "a blank apiuser must not count as configured");
        assertFalse(new ListmonkClient("http://x", API_USER, null, HttpClient.newHttpClient())
                .isCampaignConfigured(), "a missing apitoken must not count as configured");
        assertFalse(client().isCampaignConfigured(),
                "the plain subscribe-only constructor must not report campaign-configured");
    }

    @Test
    void sendCampaignHitsAllThreeEndpointsInOrderWithBasicAuth() throws Exception {
        campaignClient().sendCampaign(LIST_UUID, "New trip report", "<p>Body</p>");

        assertEquals(List.of("lists", "campaigns", "status"), requestsSeen,
                "the list must be resolved, then the campaign created, then started -- in that order");

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (API_USER + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedAuth, lastAuthHeader.get());
    }

    @Test
    void theCampaignBodyCarriesTheSubjectHtmlListAndFixedFields() throws Exception {
        campaignClient().sendCampaign(LIST_UUID, "New trip report", "<p>Body</p>");

        JsonNode body = new ObjectMapper().readTree(campaignBody.get());
        assertEquals("New trip report", body.get("subject").asString(), campaignBody.get());
        assertEquals("<p>Body</p>", body.get("body").asString(), campaignBody.get());
        assertEquals(7, body.get("lists").get(0).asInt(), campaignBody.get());
        assertEquals(1, body.get("lists").size(), campaignBody.get());
        assertEquals("regular", body.get("type").asString(), campaignBody.get());
        assertEquals("html", body.get("content_type").asString(), campaignBody.get());
    }

    @Test
    void theStatusBodyStartsTheCampaignRunning() throws Exception {
        campaignClient().sendCampaign(LIST_UUID, "New trip report", "<p>Body</p>");

        JsonNode body = new ObjectMapper().readTree(statusBody.get());
        assertEquals("running", body.get("status").asString(), statusBody.get());
    }

    @Test
    void aListsResponseNotContainingTheUuidThrowsNamingTheUuid() {
        listsResponseBody = "{\"data\":{\"results\":[{\"id\":9,\"uuid\":\"some-other-uuid\"}]}}";

        IOException ex = assertThrows(IOException.class,
                () -> campaignClient().sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));

        assertTrue(ex.getMessage().contains(LIST_UUID), ex.getMessage());
        assertEquals(List.of("lists"), requestsSeen,
                "an unresolved list must not go on to create a campaign");
    }

    @Test
    void a500FromListsThrowsNamingThatStep() {
        failingStep = "lists";
        respondWith = 500;

        IOException ex = assertThrows(IOException.class,
                () -> campaignClient().sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));

        assertTrue(ex.getMessage().toLowerCase().contains("list"), ex.getMessage());
    }

    @Test
    void a500FromCreatingTheCampaignThrowsNamingThatStep() {
        failingStep = "campaigns";
        respondWith = 500;

        IOException ex = assertThrows(IOException.class,
                () -> campaignClient().sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));

        assertTrue(ex.getMessage().toLowerCase().contains("campaign"), ex.getMessage());
        assertEquals(List.of("lists", "campaigns"), requestsSeen,
                "a failed campaign creation must not go on to start it");
    }

    @Test
    void a500FromStartingTheCampaignThrowsNamingThatStep() {
        failingStep = "status";
        respondWith = 500;

        IOException ex = assertThrows(IOException.class,
                () -> campaignClient().sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));

        assertTrue(ex.getMessage().toLowerCase().contains("start"), ex.getMessage());
        assertEquals(List.of("lists", "campaigns", "status"), requestsSeen);
    }

    /**
     * A response that is not valid JSON at all (a proxy error page, say) must
     * not surface as an unhandled parse exception -- it has to become an
     * {@code IOException} naming the step, exactly like an HTTP-level
     * failure.
     */
    @Test
    void anUnparseableListsResponseThrowsNamingTheStep() {
        listsResponseBody = "this is not json";

        IOException ex = assertThrows(IOException.class,
                () -> campaignClient().sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));

        assertTrue(ex.getMessage().toLowerCase().contains("list"), ex.getMessage());
    }

    /** {@code Thread.interrupted()} during the HTTP call must restore the interrupt flag and fail cleanly. */
    @Test
    void anInterruptedSendRestoresTheFlagAndThrowsIoException() throws Exception {
        HttpClient interrupting = org.mockito.Mockito.mock(HttpClient.class);
        when(interrupting.<String>send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new InterruptedException("stop"));
        ListmonkClient client = new ListmonkClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), API_USER, API_TOKEN, interrupting);

        try {
            IOException ex = assertThrows(IOException.class,
                    () -> client.sendCampaign(LIST_UUID, "Subject", "<p>Body</p>"));
            assertTrue(ex.getMessage().contains("interrupted"), ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt flag must be restored, not swallowed");
        } finally {
            // Clear the flag so it cannot bleed into whatever the test runner does next.
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------ fromConfig

    /**
     * {@code fromConfig} reads the same three properties {@link #campaignClient}
     * sets directly; the shipped default for all three is blank, so a client
     * built from unmodified config must report itself unconfigured on both
     * axes.
     */
    @Test
    void fromConfigBuildsAnUnconfiguredClientFromTheShippedDefaults() {
        ListmonkClient client = ListmonkClient.fromConfig();

        assertTrue(client.isUnconfigured());
        assertFalse(client.isCampaignConfigured());
    }
}
