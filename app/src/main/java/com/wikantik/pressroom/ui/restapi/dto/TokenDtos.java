package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Instant;
import com.wikantik.pressroom.pojos.ApiToken;

/** Views of an API token. The secret appears in exactly one of these. */
public final class TokenDtos {

    private TokenDtos() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenView(String id, String label, String scopeWeblog, String scopeRole,
                            Instant created, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) { }

    public record IssueRequest(String label, String weblog, String role, Instant expiresAt) { }

    /** The one response carrying a raw secret. Returned once, never again. */
    public record IssuedToken(String token, TokenView info) { }

    public static TokenView toView(ApiToken token) {
        return new TokenView(
                token.getId(),
                token.getLabel(),
                token.getScopeWeblog(),
                token.getScopeRole() == null ? null : token.getScopeRole().name(),
                instant(token.getCreated()),
                instant(token.getLastUsedAt()),
                instant(token.getExpiresAt()),
                instant(token.getRevokedAt()));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
