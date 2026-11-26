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

    /** One token on the site-wide list: the token, whose it is, and who issued it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SiteTokenView(
            @Schema(description = "The token's id; what revoking it takes.", requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(description = "The label its owner gave it.") String label,
            @Schema(description = "The weblog it is limited to; absent when it reaches every weblog its owner can.")
            String scopeWeblog,
            @Schema(description = "READ, POST or ADMIN.") String scopeRole,
            Instant created, Instant lastUsedAt, Instant expiresAt, Instant revokedAt,
            @Schema(description = "The user name of the account the token acts as.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String owner,
            @Schema(description = "PERSON or AGENT: what kind of account the owner is.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String ownerKind,
            @Schema(description = "The user name of whoever issued it (the stored id when that account is gone); "
                    + "absent on a token issued before issuers were recorded.")
            String issuedBy,
            @Schema(description = "Neither revoked nor expired.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean live) { }

    /** Every token on the site, newest first. */
    public record SiteTokenPage(
            @Schema(description = "Newest first.", requiredMode = Schema.RequiredMode.REQUIRED)
            java.util.List<SiteTokenView> items) { }

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
