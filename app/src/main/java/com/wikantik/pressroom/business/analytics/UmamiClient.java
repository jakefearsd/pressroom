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
package com.wikantik.pressroom.business.analytics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Umami's HTTP API, with the login handled: it logs in lazily, keeps the
 * bearer token, and on a 401 for a token it had been holding logs in again
 * once and retries once. The URL comes from deployer configuration, never
 * from author or reader input.
 *
 * <p>The password and token live in private fields, are never logged, never
 * put in an exception message, and not in {@link #toString()}. HTTP/1.1 is
 * pinned: against the Umami image the default h2c upgrade attempt on a POST
 * gets an empty reply (docs/dev/umami-api.md).
 */
final class UmamiClient {

    /**
     * HTTP 401. A reply with this status from {@link #get} or {@link #post}
     * was answered to a token fetched by a login made for that very call (see
     * {@link #send}), so it is never a stale token's: Umami means the caller
     * may not see what it asked for, which for a website is that it is missing.
     */
    static final int UNAUTHORIZED = 401;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(UmamiClient.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** A response: status and raw body (callers must not log the body). */
    record Reply(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        JsonNode json() throws AnalyticsException {
            try {
                return JSON.readTree(body);
            } catch (RuntimeException e) {
                throw new AnalyticsException("Umami sent an unparseable response", e);
            }
        }
    }

    private final String baseUrl;
    private final String username;
    private final String password;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final boolean urlValid;
    private volatile String token;
    private int logins;

    UmamiClient(String baseUrl, String username, String password) {
        this(baseUrl, username, password, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    UmamiClient(String baseUrl, String username, String password,
                Duration connectTimeout, Duration requestTimeout) {
        this.baseUrl = StringUtils.stripEnd(baseUrl, "/");
        this.username = username;
        this.password = password;
        this.urlValid = validUrl(this.baseUrl);
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /** How many times this client has logged in (tests). */
    synchronized int loginCount() {
        return logins;
    }

    Reply get(String path) throws AnalyticsException {
        return send("GET", path, null);
    }

    Reply post(String path, Object body) throws AnalyticsException {
        return send("POST", path, JSON.writeValueAsString(body));
    }

    /** {@code POST /api/auth/verify}: whether the token (logging in if needed) is good. */
    boolean verify() throws AnalyticsException {
        return send("POST", "/api/auth/verify", null).ok();
    }

    /**
     * Sends with the bearer token. A 401 on a token that was held from before
     * this call triggers one fresh login and one retry; a 401 on a token that
     * was fetched for this very call is returned as is. So every 401 this
     * returns came after a fresh, successful login ({@link #UNAUTHORIZED}).
     */
    private Reply send(String method, String path, String body) throws AnalyticsException {
        boolean freshlyLoggedIn = false;
        if (token == null) {
            login();
            freshlyLoggedIn = true;
        }
        Reply reply = exchange(method, path, body, token);
        if (reply.status() == UNAUTHORIZED && !freshlyLoggedIn) {
            login();
            reply = exchange(method, path, body, token);
        }
        return reply;
    }

    private synchronized void login() throws AnalyticsException {
        logins++;
        Reply reply = exchange("POST", "/api/auth/login",
                JSON.writeValueAsString(Map.of("username", username, "password", password)), null);
        if (reply.status() != 200) {
            token = null;
            throw new AnalyticsException("Umami login failed: HTTP " + reply.status());
        }
        JsonNode node = reply.json().get("token");
        if (node == null || node.asString().isBlank()) {
            token = null;
            throw new AnalyticsException("Umami login returned no token");
        }
        token = node.asString();
    }

    private static boolean validUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Reply exchange(String method, String path, String body, String bearer) throws AnalyticsException {
        if (!urlValid) {
            throw new AnalyticsException("analytics.umami.apiUrl is not a valid http(s) URL");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                log.warn("Umami answered HTTP {} for {} {}", response.statusCode(), method, route(path));
            }
            return new Reply(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AnalyticsException("Umami unreachable for " + method + " " + route(path)
                    + ": " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalyticsException("interrupted calling Umami", e);
        }
    }

    /** The path without its query string, which can carry nothing secret but is noise. */
    private static String route(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    @Override
    public String toString() {
        return "UmamiClient[" + baseUrl + "]";
    }
}
