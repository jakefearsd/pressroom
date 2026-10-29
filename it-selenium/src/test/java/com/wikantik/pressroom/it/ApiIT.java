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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The automation API against the packaged, running WAR -- no browser.
 *
 * <p>MockMvc never runs a servlet container, so this class proves three
 * things nothing before it could: that the container really strips the
 * {@code /api} servlet-path prefix so a controller mapped at
 * {@code /v1/...} is reachable at {@code /api/v1/...}; that the real Spring
 * Security filter chain (not filters called directly with mocks, as the
 * unit tests do) answers an unauthenticated call with 401, mints no
 * session on either a failure or a success, and carries that statelessness
 * through the full Basic-auth-mint / Bearer-auth-use / revoke bootstrap
 * cycle end to end against a real server; and true multipart handling
 * through the real Tomcat parser and rendition/EXIF/BlurHash pipeline, not
 * {@code MockMultipartFile}. Also confirms (beyond the brief) that
 * springdoc's own auto-registered document mapping survives the same
 * prefix rewrite.
 *
 * <p>This is acceptance testing, not TDD: every behaviour here was already
 * driven out by a failing unit test in Tasks 1-17. A failure in this class
 * means the assembled WAR does not behave the way those unit tests said it
 * would -- that is a defect in the packaging to report, never a reason to
 * weaken the assertion.
 */
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class ApiIT extends PressroomIT {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basicAuthHeader() {
        String credentials = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    private HttpResponse<String> getWithBasicAuth(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The whole point of the prefix-mapping constraint
     * ({@code ServletRegistrationConfig.API_URL_PATTERNS}, a servlet-spec
     * prefix mapping on {@code /api/*}): the container strips {@code /api}
     * before Spring MVC ever sees the lookup path, so every controller under
     * {@code ui.restapi.v1} is mapped relative to {@code /v1}. A controller
     * written with the full {@code /api/v1/...} path 404s here and nowhere
     * else -- this already caused one Critical in this wave.
     */
    @Test
    void thePrefixMappingResolvesToAController() throws Exception {
        HttpResponse<String> response = get("/api/v1/ping", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\""));
        // Statelessness is easiest to get wrong on a call that SUCCEEDS --
        // that is exactly where Spring Security would otherwise persist the
        // SecurityContext into a session. Asserting this only on the 401
        // (below) would miss a misconfiguration that only shows up once
        // authentication/authorization actually happens.
        assertNoSessionCookie(response);
    }

    /**
     * MockMvc calls filters directly with mocks, bypassing
     * {@code ExceptionTranslationFilter} and {@code authorizeHttpRequests}
     * entirely. The API chain is configured
     * {@code SessionCreationPolicy.STATELESS}
     * ({@code SecurityConfig.apiSecurityFilterChain}); only a real servlet
     * container can prove that an unauthenticated call is refused by that
     * chain -- not Boot's own error page, not a container-level 401 outside
     * {@code DispatcherServlet} -- and that no session is minted along the way.
     */
    @Test
    void anUnauthenticatedRequestIsRefusedWithoutASessionCookie() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertEquals(401, response.statusCode());
        assertNoSessionCookie(response);
    }

    @Test
    void aBadBearerTokenIsRefused() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", "prs_definitelynotreal");
        assertEquals(401, response.statusCode());
        // Status alone could pass against a container-level 401 (e.g. an
        // HTML error page) that never reached ApiAuthenticationEntryPoint.
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/problem+json"),
                "a bad bearer token must be refused by the real API error path, "
                        + "not by some other 401 that happens to share its status code");
    }

    @Test
    void anErrorRespondsAsProblemJson() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/problem+json"),
                "every API error carries the problem+json content type");
    }

    /**
     * springdoc (Task 20) publishes the automation API's own machine-readable
     * document at {@code GET /api/v1/openapi.json}, guarded by the same
     * {@code apiSecurityFilterChain} as every other {@code /api/**} route.
     * Nothing before this task has ever fetched it through a real servlet
     * container: only that container proves springdoc's own auto-registered
     * mapping survives the {@code /api} prefix strip the same way the
     * hand-written controllers do.
     */
    @Test
    void theOpenApiDocumentIsServedAndParses() throws Exception {
        HttpResponse<String> response = getWithBasicAuth("/api/v1/openapi.json");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/json"),
                "the OpenAPI document must be served as JSON");
        String body = response.body();
        assertTrue(body.contains("\"openapi\""), "document is missing the openapi version field");
        assertTrue(looksLikeBalancedJson(body), "document is not syntactically valid JSON:\n" + body);
    }

    /**
     * The full bootstrap path, exercised end to end for the first time:
     * Basic-auth a mint request through the real filter chain, then use the
     * raw secret it hands back as a Bearer token on a second real request.
     * {@code TokensApiTest} only ever calls the controller directly against a
     * {@code SecurityContext} it built by hand, so nothing before this test
     * has proved that a token minted through the real HTTP surface is
     * actually usable on the real HTTP surface afterwards.
     *
     * <p>Also revokes the token it minted and re-checks {@code /v1/me} with
     * it: {@code revoke()}'s {@code weblogger.flush()} is otherwise covered
     * only by a mock {@code verify(weblogger).flush()}, which proves the
     * call was made, not that it committed -- precisely the gap that let the
     * {@code issue()} version of this same bug ship undetected. This also
     * stops the run leaving a live, unscoped, ADMIN-role token behind every
     * time it executes.
     */
    @Test
    void aTokenMintedThroughTheApiWorksOnASubsequentCall() throws Exception {
        MintedToken minted = mintToken("ApiIT smoke token", "ADMIN");
        assertNoSessionCookie(minted.mintResponse());

        HttpResponse<String> me = get("/api/v1/me", minted.raw());
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(ADMIN_USERNAME, extractJsonStringField(me.body(), "userName"),
                "the minted token did not authenticate as " + ADMIN_USERNAME + ": " + me.body());

        HttpRequest revoke = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens/" + minted.id()))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .DELETE()
                .build();
        HttpResponse<String> revokeResponse = client.send(revoke, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, revokeResponse.statusCode(), revokeResponse.body());

        HttpResponse<String> meAfterRevoke = get("/api/v1/me", minted.raw());
        assertEquals(401, meAfterRevoke.statusCode(),
                "a revoked token must stop authenticating immediately, not after some later "
                        + "unrelated request happens to flush the database write");
    }

    /**
     * True multipart handling through the real rendition/EXIF/BlurHash
     * pipeline -- the third thing named in this task's brief that only a
     * real servlet container can prove, alongside prefix mapping and the
     * real filter chain. {@code MediaApiTest} drives {@code upload()} with
     * {@code MockMultipartFile}, which never touches Tomcat's multipart
     * parser, {@code spring.servlet.multipart.max-request-size}, or a real
     * image decode. A small but genuine JPEG, POSTed as real
     * {@code multipart/form-data}, must come back 201 with a view carrying
     * real rendition metadata (width/height from the decoded image,
     * blurhash from the placeholder generator) -- not just echo the upload
     * back unprocessed.
     *
     * <p>{@code uploads.enabled} is off in the seed IT fixture (see
     * {@code ThemeMatrixIT}, the other class that needs it on), so this
     * test drives the real Admin Settings page through the browser this
     * class already inherits from {@code PressroomIT} to flip it on and back
     * off -- the same {@code loginAsAdmin()}/{@code setGlobalFlag()}/
     * {@code finally}-restore discipline every other caller of a global
     * runtime flag in this suite uses, since the flag is shared by the
     * whole running instance.
     */
    @Test
    void uploadingARealImageThroughRealMultipartReachesTheRenditionPipeline() throws Exception {
        loginAsAdmin();
        boolean uploadsWere = false;
        MintedToken minted = null;
        try {
            // Both the flag flip and the mint must be inside this try: if
            // either one succeeds and something AFTER it throws (the mint
            // failing right after the flag flip did, say), the flag has to
            // still be restored in the finally below -- it is shared by the
            // whole running IT instance, and a failure here must not leave
            // uploads on for every test that runs after this one.
            uploadsWere = setGlobalFlag("uploads.enabled", true);
            minted = mintToken("ApiIT multipart token", "ADMIN");

            byte[] jpeg = tinyJpeg();
            String boundary = "ApiITBoundary" + System.nanoTime();
            byte[] body = multipartBody(boundary, "file", "apiit-tiny.jpg", "image/jpeg", jpeg);

            HttpRequest upload = HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/v1/weblogs/" + WEBLOG_HANDLE + "/media"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + minted.raw())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(upload, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());

            String uploadBody = response.body();
            assertEquals("created", extractJsonStringField(uploadBody, "status"), uploadBody);
            assertEquals("image/jpeg", extractJsonStringField(uploadBody, "contentType"), uploadBody);
            assertTrue(extractJsonIntField(uploadBody, "width") > 0,
                    "width must come from actually decoding the uploaded image: " + uploadBody);
            assertTrue(extractJsonIntField(uploadBody, "height") > 0,
                    "height must come from actually decoding the uploaded image: " + uploadBody);
            assertFalse(extractJsonStringField(uploadBody, "blurhash").isBlank(),
                    "a real upload always gets a BlurHash placeholder: " + uploadBody);

            String mediaId = extractJsonStringField(uploadBody, "id");
            HttpRequest delete = HttpRequest.newBuilder(URI.create(
                            baseUrl() + "/api/v1/weblogs/" + WEBLOG_HANDLE + "/media/" + mediaId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + minted.raw())
                    .DELETE()
                    .build();
            HttpResponse<String> deleteResponse = client.send(delete, HttpResponse.BodyHandlers.ofString());
            assertEquals(204, deleteResponse.statusCode(), deleteResponse.body());
        } finally {
            if (minted != null) {
                revokeToken(minted.id());
            }
            setGlobalFlag("uploads.enabled", uploadsWere);
            logout();
        }
    }

    /**
     * What a publishing agent does: one call with an HTML body and a
     * location, then a reader opens the permalink. The JSON reaches the
     * entity through the real Jackson configuration and the real
     * transaction, and the page carries the TouristAttraction block with the
     * coordinates and keeps the link's new tab -- the unit tests stop at the
     * entity and at the sanitizer.
     */
    @Test
    void anEntryPublishedWithALocationRendersItsPlaceAndItsNewTabLink() throws Exception {
        MintedToken minted = mintToken("ApiIT location token", "POST");
        String entryId = null;
        try {
            String create = "{\"title\":\"ApiIT Torrey Pines " + System.nanoTime() + "\","
                    + "\"text\":\"<p>See <a href=\\\"https://www.parks.ca.gov/\\\" target=\\\"_blank\\\">"
                    + "State Parks</a>.</p>\","
                    + "\"status\":\"PUBLISHED\",\"jsonLdType\":\"TOURIST_ATTRACTION\","
                    + "\"geoLatitude\":32.9228,\"geoLongitude\":-117.2534}";
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + minted.raw())
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(create))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body());
            entryId = extractJsonStringField(created.body(), "id");
            assertEquals("TOURIST_ATTRACTION", extractJsonStringField(created.body(), "jsonLdType"),
                    created.body());

            String permalink = extractJsonStringField(created.body(), "permalink");
            String page = getAnonymously(permalink);
            assertTrue(page.contains("\"@type\":\"TouristAttraction\""), permalink + "\n" + page);
            assertTrue(page.contains("\"latitude\":32.9228"), permalink + "\n" + page);
            assertTrue(page.contains("\"longitude\":-117.2534"), permalink + "\n" + page);
            assertTrue(page.contains("target=\"_blank\""), permalink + "\n" + page);
            assertTrue(page.contains("noopener"), permalink + "\n" + page);
        } finally {
            if (entryId != null) {
                deleteEntryForever(entryId, minted.raw());
            }
            revokeToken(minted.id());
        }
    }

    // ---------------------------------------------------------------- utils

    /** Trash, then purge: an entry has to be in the trash to be deleted forever. */
    private void deleteEntryForever(String entryId, String bearer) throws Exception {
        String entry = baseUrl() + "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId;
        HttpResponse<String> trashed = client.send(HttpRequest.newBuilder(URI.create(entry))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearer)
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(trashed.statusCode() < 300, trashed.body());
        HttpResponse<String> purged = client.send(HttpRequest.newBuilder(URI.create(entry + "/delete-forever"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearer)
                .POST(BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(purged.statusCode() < 300, purged.body());
    }

    private record MintedToken(String id, String raw, HttpResponse<String> mintResponse) { }

    private MintedToken mintToken(String label, String role) throws Exception {
        HttpRequest mint = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"label\":\"" + label + "\",\"role\":\"" + role + "\"}"))
                .build();
        HttpResponse<String> mintResponse = client.send(mint, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, mintResponse.statusCode(), mintResponse.body());

        String raw = extractJsonStringField(mintResponse.body(), "token");
        assertFalse(raw.isBlank(), "mint response carried no raw token: " + mintResponse.body());
        String id = extractJsonStringField(mintResponse.body(), "id");
        return new MintedToken(id, raw, mintResponse);
    }

    private void revokeToken(String id) throws Exception {
        HttpRequest revoke = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens/" + id))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .DELETE()
                .build();
        client.send(revoke, HttpResponse.BodyHandlers.discarding());
    }

    /** A minimal but genuine, decodable JPEG -- not a stub or a fixture file. */
    private static byte[] tinyJpeg() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var graphics = image.getGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** Hand-built because {@code java.net.http.HttpClient} has no multipart body publisher. */
    private static byte[] multipartBody(String boundary, String fieldName, String filename,
            String contentType, byte[] content) {
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String epilogue = "\r\n--" + boundary + "--\r\n";
        byte[] preambleBytes = preamble.getBytes(StandardCharsets.UTF_8);
        byte[] epilogueBytes = epilogue.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[preambleBytes.length + content.length + epilogueBytes.length];
        System.arraycopy(preambleBytes, 0, body, 0, preambleBytes.length);
        System.arraycopy(content, 0, body, preambleBytes.length, content.length);
        System.arraycopy(epilogueBytes, 0, body, preambleBytes.length + content.length, epilogueBytes.length);
        return body;
    }

    private static void assertNoSessionCookie(HttpResponse<?> response) {
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(c -> c.startsWith("JSESSIONID")),
                "the API chain is stateless -- it must not mint a session");
    }

    private static int extractJsonIntField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            fail("integer field \"" + field + "\" not found in: " + json);
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * Pulls a string field's value out of a JSON object body by regex rather
     * than parsing it -- this module carries no JSON library dependency, and
     * adding one just for two assertions is not worth it.
     */
    private static String extractJsonStringField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            fail("field \"" + field + "\" not found in: " + json);
        }
        return m.group(1);
    }

    /**
     * Not a real parser: a cheap syntactic sanity check (balanced braces and
     * brackets outside of string literals) that costs no new dependency.
     */
    private static boolean looksLikeBalancedJson(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (char c : trimmed.toCharArray()) {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }
}
