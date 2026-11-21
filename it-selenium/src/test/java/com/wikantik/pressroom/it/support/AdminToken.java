package com.wikantik.pressroom.it.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An API token for the seeded admin, minted with the admin's password on
 * {@code POST /api/v1/tokens} and revoked by its own id on {@link #close()}.
 *
 * <p>Since 0.16.0 HTTP Basic authenticates only the token resource,
 * {@code /api/v1/tokens} and {@code /api/v1/tokens/{id}} (spec 2026-11-20,
 * Task 2); every other {@code /api/**} route answers a password with a 401.
 * A class that calls the API as the admin therefore mints one of these and
 * sends {@link #bearer()}, as a real client does. Use it in a
 * try-with-resources, or revoke it in a {@code finally}/{@code @AfterAll},
 * so a run leaves no live token behind.
 */
public final class AdminToken implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String id;
    private final String raw;
    private final HttpResponse<String> mintResponse;

    private AdminToken(String id, String raw, HttpResponse<String> mintResponse) {
        this.id = id;
        this.raw = raw;
        this.mintResponse = mintResponse;
    }

    /** Mints a token with {@code role} ({@code READ}, {@code POST} or {@code ADMIN}) on every weblog. */
    public static AdminToken mint(String label, String role) throws IOException, InterruptedException {
        return mint(label, role, null);
    }

    /** Mints a token with {@code role}, pinned to {@code weblog} unless it is null. */
    public static AdminToken mint(String label, String role, String weblog)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("role", role);
        if (weblog != null) {
            body.put("weblog", weblog);
        }
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(PressroomIT.baseUrl() + "/api/v1/tokens"))
                        .timeout(TIMEOUT)
                        .header("Authorization", adminBasic())
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        return new AdminToken(json.get("info").get("id").asString(), json.get("token").asString(), response);
    }

    /** The token's id, which is how it is revoked. */
    public String id() {
        return id;
    }

    /** The raw secret. */
    public String raw() {
        return raw;
    }

    /** The {@code Authorization} header value that sends it. */
    public String bearer() {
        return "Bearer " + raw;
    }

    /** The mint call's own response. */
    public HttpResponse<String> mintResponse() {
        return mintResponse;
    }

    /** Revokes this token, with the admin's password, and returns the answer (204 when it was live). */
    public HttpResponse<String> revoke() throws IOException, InterruptedException {
        return revoke(id);
    }

    /** Revokes the admin's token {@code tokenId}, with the admin's password. */
    public static HttpResponse<String> revoke(String tokenId) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(PressroomIT.baseUrl() + "/api/v1/tokens/" + tokenId))
                        .timeout(TIMEOUT)
                        .header("Authorization", adminBasic())
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Revokes the token, unasserted: a tidy-up failing must not replace a
     * test's own failure. An interrupt is passed on, not thrown, so the
     * resource fits a try-with-resources without an InterruptedException.
     */
    @Override
    public void close() throws IOException {
        try {
            revoke();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The seeded admin's password as an {@code Authorization} header: for the token resource only. */
    public static String adminBasic() {
        return basic(PressroomIT.ADMIN_USERNAME, PressroomIT.ADMIN_PASSWORD);
    }

    /** {@code user}'s password as an {@code Authorization} header. */
    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
