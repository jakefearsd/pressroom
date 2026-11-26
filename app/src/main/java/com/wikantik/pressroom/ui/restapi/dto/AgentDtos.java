package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.Paging;

/**
 * Agent accounts as the admin app manages them (session only, SA-18). An agent is an account of kind AGENT
 * that never signs in; the tokens an admin mints for it are how it acts.
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** One agent. {@code tokens} (its live tokens) is on a single agent's read, not on the list. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentView(
            @Schema(description = "The agent's user name, which never changes.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,
            @Schema(description = "The agent's name: its screen name and full name.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "The agent's email address; the store fills one in when none was given.")
            String emailAddress,
            @Schema(description = "Notes about what the agent is for.")
            String notes,
            @Schema(description = "Whether the agent may act; disabling ends every one of its tokens on its next "
                    + "request.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean enabled,
            @Schema(description = "Whether the agent holds the site administrator role, for the site-wide admin "
                    + "API.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean siteAdmin,
            @Schema(description = "The agent's live tokens, newest first. Only on a single agent's read.")
            List<TokenDtos.TokenView> tokens) {
    }

    /** One page of agents. */
    public record AgentPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<AgentView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore) {
    }

    /** The body of a create-agent request: the New agent form. */
    public record AgentCreate(
            @Schema(description = "The agent's user name; letters and digits, it never changes. Required.")
            String userName,
            @Schema(description = "The agent's name, stored as screen name and full name. Required.")
            String name,
            @Schema(description = "An optional email address; blank leaves the store's placeholder.")
            String emailAddress,
            @Schema(description = "Notes about what the agent is for.")
            String notes,
            @Schema(description = "Give the agent the site administrator role. Default false.")
            Boolean siteAdmin) {
    }

    /** The body of an agent PATCH: the agent page's Details form and its Enable or Disable button. */
    public record AgentPatch(
            @Schema(description = "The agent's name, stored as screen name and full name.")
            String name,
            @Schema(description = "Notes; an empty string clears them.")
            String notes,
            @Schema(description = "Grant or revoke the site administrator role.")
            Boolean siteAdmin,
            @Schema(description = "Enable or disable the agent.")
            Boolean enabled) {
    }

    /** The agent page's row for an account: its fields and what it holds. */
    public static AgentView toView(User agent, boolean siteAdmin, List<TokenDtos.TokenView> tokens) {
        return new AgentView(agent.getUserName(), agent.getScreenName(), agent.getEmailAddress(), agent.getNotes(),
                !Boolean.FALSE.equals(agent.getEnabled()), siteAdmin, tokens);
    }
}
