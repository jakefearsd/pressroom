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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.testing.UmamiContainer;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UmamiAnalyticsService} against the real digest-pinned Umami image:
 * registration, idempotent re-registration, rename, and a read of one seen
 * pageview. Shares {@link UmamiContainer}'s one instance per JVM.
 */
class UmamiAnalyticsServiceContractTest {

    private static final String BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    @Test
    void ensureSiteAndReadAgainstRealUmami() throws Exception {
        UmamiContainer umami = UmamiContainer.get();
        String username = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID();
        umami.psql(Paths.get("../deploy/analytics/umami-pressroom-account.sql"),
                Map.of("username", username, "pw", password));
        AnalyticsService service = UmamiAnalyticsService.of(
                new UmamiClient(umami.baseUrl(), username, password), java.time.Clock.systemUTC());
        String id = UUID.randomUUID().toString();

        assertTrue(service.ping());
        assertEquals(id, service.ensureSite(new SiteSpec(id, "svc-contract", "Contract.Example.com")));
        assertEquals(id, service.ensureSite(new SiteSpec(id, "svc-contract", "contract.example.com")),
                "registering again is a no-op");
        assertEquals(id, service.ensureSite(new SiteSpec(id, "svc-renamed", "contract.example.com")));

        ZoneId zone = ZoneId.of("America/Los_Angeles");
        AnalyticsReport empty = service.read(id, Period.of("7d"), zone);
        assertEquals(0, empty.current().views());
        assertTrue(empty.series().size() >= 7, "empty days are zero-filled");
        assertEquals(java.util.List.of("sources", "places", "devices", "events", "campaigns", "speed"),
                new java.util.ArrayList<>(empty.breakdowns().keySet()));
        assertTrue(empty.breakdowns().get("speed").isEmpty());

        beacon(umami, id);

        AnalyticsReport report = service.read(id, Period.of("24h"), zone);
        assertEquals(1, report.current().views());
        assertEquals(1, report.current().visitors());
        assertEquals("/blog/entry/hello", report.paths().get(0).path());
        assertEquals(1, report.paths().get(0).visitors());
        assertTrue(report.breakdowns().get("devices").stream().anyMatch(k -> k.key().startsWith("browser:")));
        assertEquals(1, report.series().stream().mapToLong(AnalyticsReport.SeriesPoint::views).sum());
    }

    /**
     * A site space in UTC (or a JVM defaulting to it, which the container's
     * does) is asked for timezone=UTC, and for that zone alone Umami writes
     * every bucket as an ISO instant, "2026-11-03T12:00:00Z", where every
     * other zone gets "2026-11-03 12:00:00" (UmamiApiContractTest). The read
     * threw on the Z: on docker1 each blog with a visit lost its Traffic
     * reports (2026-11-03).
     */
    @Test
    void readsInUtcWhereUmamiWritesInstants() throws Exception {
        UmamiContainer umami = UmamiContainer.get();
        String username = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID();
        umami.psql(Paths.get("../deploy/analytics/umami-pressroom-account.sql"),
                Map.of("username", username, "pw", password));
        AnalyticsService service = UmamiAnalyticsService.of(
                new UmamiClient(umami.baseUrl(), username, password), java.time.Clock.systemUTC());
        String id = UUID.randomUUID().toString();
        service.ensureSite(new SiteSpec(id, "svc-utc", "utc.example.com"));
        beacon(umami, id);
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(ZoneId.of("UTC"));

        AnalyticsReport hourly = service.read(id, Period.of("24h"), ZoneId.of("UTC"));
        AnalyticsReport daily = service.read(id, Period.of("7d"), ZoneId.of("UTC"));

        AnalyticsReport.SeriesPoint hour = hourly.series().stream().filter(p -> p.views() > 0)
                .findFirst().orElseThrow();
        assertEquals(1, hourly.series().stream().mapToLong(AnalyticsReport.SeriesPoint::views).sum());
        assertEquals(now.toLocalDate(), hour.day(), "in the hour it was seen: " + hourly.series());
        assertEquals(now.getHour(), hour.hour(), "in the hour it was seen: " + hourly.series());
        AnalyticsReport.SeriesPoint day = daily.series().stream().filter(p -> p.views() > 0)
                .findFirst().orElseThrow();
        assertEquals(1, daily.series().stream().mapToLong(AnalyticsReport.SeriesPoint::views).sum());
        assertEquals(now.toLocalDate(), day.day(), daily.series().toString());
    }

    private static void beacon(UmamiContainer umami, String websiteId) throws Exception {
        String body = JsonMapper.builder().build().writeValueAsString(Map.of("type", "event", "payload", Map.of(
                "website", websiteId, "hostname", "contract.example.com", "screen", "1920x1080",
                "language", "en-US", "title", "Hello", "url", "/blog/entry/hello", "referrer", "")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(umami.baseUrl() + "/api/send"))
                .header("Content-Type", "application/json")
                .header("User-Agent", BROWSER_UA)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
