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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.config.WebloggerConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The only outbound HTTP in the audience wave, and it points at deployer
 * configuration, never at author or reader input: the Listmonk base URL
 * comes from pressroom.properties, and the request bodies carry an email (or a
 * blog entry an editor has already chosen to send) plus a list uuid that has
 * already been matched against a weblog's configured list. There is nothing
 * here a reader can aim at an internal address.
 *
 * <p><b>Two credential tiers.</b> {@code subscribe} uses Listmonk's public
 * subscription endpoint and needs no credentials. {@code sendCampaign} uses
 * the admin API (creating and starting a campaign is a privileged action,
 * unlike a reader opting themselves in) and requires
 * {@code newsletter.listmonk.apiuser}/{@code apitoken}, checked separately by
 * {@link #isCampaignConfigured()} -- a deploy can have the public subscribe
 * form working while "Send as newsletter" stays disabled for want of an API
 * user.
 */
public class ListmonkClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiUser;
    private final String apiToken;
    private final HttpClient http;

    public ListmonkClient(String baseUrl, HttpClient http) {
        this(baseUrl, null, null, http);
    }

    public ListmonkClient(String baseUrl, String apiUser, String apiToken, HttpClient http) {
        this.baseUrl = StringUtils.stripEnd(StringUtils.trimToNull(baseUrl), "/");
        this.apiUser = StringUtils.trimToNull(apiUser);
        this.apiToken = StringUtils.trimToNull(apiToken);
        this.http = http;
    }

    public static ListmonkClient fromConfig() {
        return new ListmonkClient(
                WebloggerConfig.getProperty("newsletter.listmonk.baseurl"),
                WebloggerConfig.getProperty("newsletter.listmonk.apiuser"),
                WebloggerConfig.getProperty("newsletter.listmonk.apitoken"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public boolean isUnconfigured() {
        return baseUrl == null;
    }

    /**
     * Whether the admin-API credentials {@link #sendCampaign} needs are
     * present, on top of the base URL {@link #isUnconfigured()} already
     * checks.
     */
    public boolean isCampaignConfigured() {
        return baseUrl != null && apiUser != null && apiToken != null;
    }

    /** Forwards a subscription; returns Listmonk's status code (200, 409, ...). */
    public int subscribe(String email, String listUuid) throws IOException {
        String body = "{\"email\":" + jsonString(email)
                + ",\"list_uuids\":[" + jsonString(listUuid) + "]}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/public/subscription"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted forwarding subscription", ex);
        }
    }

    /**
     * Creates a Listmonk campaign for {@code listUuid} and immediately starts
     * it sending -- three admin-API calls in order: resolve the list's
     * numeric id from its uuid, create the campaign, then flip it to
     * "running" (Listmonk never sends on creation alone). Each step is
     * synchronous and any failure raises an {@code IOException} that names
     * the step that failed, so the caller -- and the author reading the
     * error -- knows which leg of the send to investigate or retry.
     */
    public void sendCampaign(String listUuid, String subject, String html) throws IOException {
        int listId = resolveListId(listUuid);
        int campaignId = createCampaign(listId, subject, html);
        startCampaign(campaignId);
    }

    private int resolveListId(String listUuid) throws IOException {
        HttpRequest request = authedRequest("/api/lists?per_page=all").GET().build();
        HttpResponse<String> response = send(request, "looking up the newsletter list");
        JsonNode results = readJson(response.body(), "looking up the newsletter list")
                .path("data").path("results");
        for (JsonNode list : results) {
            if (listUuid.equals(list.path("uuid").asString())) {
                return list.path("id").asInt();
            }
        }
        throw new IOException("Listmonk has no list matching uuid " + listUuid);
    }

    private int createCampaign(int listId, String subject, String html) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", subject);
        body.put("subject", subject);
        body.putArray("lists").add(listId);
        body.put("type", "regular");
        body.put("content_type", "html");
        body.put("body", html);

        HttpRequest request = authedRequest("/api/campaigns")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = send(request, "creating the newsletter campaign");
        return readJson(response.body(), "creating the newsletter campaign")
                .path("data").path("id").asInt();
    }

    private void startCampaign(int campaignId) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", "running");

        HttpRequest request = authedRequest("/api/campaigns/" + campaignId + "/status")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        send(request, "starting the newsletter campaign");
    }

    private HttpRequest.Builder authedRequest(String path) {
        String credentials = apiUser + ":" + apiToken;
        String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + encoded);
    }

    /** Sends the request and returns its body, or throws naming {@code step} on a non-2xx status. */
    private HttpResponse<String> send(HttpRequest request, String step) throws IOException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while " + step, ex);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Listmonk error while " + step + ": HTTP " + response.statusCode());
        }
        return response;
    }

    /** Parses a response body as JSON, wrapping a malformed body into an {@code IOException} naming {@code step}. */
    private static JsonNode readJson(String body, String step) throws IOException {
        try {
            return JSON.readTree(body);
        } catch (RuntimeException ex) {
            throw new IOException("Listmonk sent an unparseable response while " + step, ex);
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
