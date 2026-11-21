package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import com.wikantik.pressroom.pojos.ApiToken;

/** Views of an API token. The secret appears in exactly one of these. */
public final class TokenDtos {

    private TokenDtos() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenView(String id, String label, String scopeWeblog, String scopeRole,
                            Instant created, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) { }

    public record IssueRequest(
            @Schema(description = "A name for the token, so its owner can tell it from the others.")
            String label,
            @Schema(description = "The handle of the one weblog the token may act on; omitted, every weblog its "
                    + "user can reach.")
            String weblog,
            @Schema(description = "READ, POST or ADMIN: what the token may do.")
            String role,
            @Schema(description = "When the token stops working, an ISO-8601 instant in the future; omitted, it "
                    + "works until revoked.")
            Instant expiresAt) { }

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
