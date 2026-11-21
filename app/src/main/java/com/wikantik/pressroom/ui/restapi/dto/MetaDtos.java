package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The bodies of {@code GET /v1/ping} and {@code GET /v1/me}. They were
 * {@code Map}s, which openapi.json could only describe as a map of anything;
 * as records they serialise to the same JSON (spec
 * 2026-11-20-api-contract-design, Task 4; {@code UntypedResponsesJsonTest}).
 */
public final class MetaDtos {

    /** The liveness answer, always the same. */
    public static final PingView PING_OK = new PingView("ok");

    /** The scope of a caller that did not authenticate with a token: serialises as {@code {}}. */
    public static final TokenScope NO_TOKEN_SCOPE = new TokenScope(null, null);

    private MetaDtos() {
    }

    /** The liveness answer. */
    public record PingView(
            @Schema(description = "Always ok: the API is answering.", requiredMode = Schema.RequiredMode.REQUIRED)
            String status) {
    }

    /** Who the caller is, what its token may do, and the weblogs it can act on. */
    public record MeView(
            @Schema(description = "The account's user name.", requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,
            @Schema(description = "The account's display name.")
            String screenName,
            @Schema(description = "PERSON or AGENT: whether the account is a person or an agent.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String kind,
            @Schema(description = "True when the account is a site admin.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean globalAdmin,
            @Schema(description = "The token's ceiling: its role, and the weblog it is limited to. An empty "
                    + "object for a caller that did not authenticate with a token.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            TokenScope tokenScope,
            @Schema(description = "The weblogs this token can act on, sorted by handle: the one a pinned token "
                    + "is limited to, or every weblog the account belongs to.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<WeblogMembership> weblogs) {
    }

    /**
     * A token's ceiling. Both members are null, and so the object is empty,
     * for a caller that did not authenticate with a token.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenScope(
            @Schema(description = "The weblog the token is limited to; an empty string when it is not limited "
                    + "to one.")
            String weblog,
            @Schema(description = "READ, POST or ADMIN: the token's role.")
            String role) {
    }

    /** One weblog the caller can act on. */
    public record WeblogMembership(
            @Schema(description = "The weblog's handle.", requiredMode = Schema.RequiredMode.REQUIRED)
            String handle,
            @Schema(description = "The weblog's name; an empty string when it has none.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "ADMIN, POST or EDIT_DRAFT: the highest permission the account holds on it.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String permission) {
    }
}
