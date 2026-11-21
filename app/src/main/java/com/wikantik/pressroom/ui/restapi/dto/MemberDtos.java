package com.wikantik.pressroom.ui.restapi.dto;

import java.util.List;

import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.Paging;
import io.swagger.v3.oas.annotations.media.Schema;

/** Views of a weblog's members for the automation API (read only; changes are made in the admin UI). */
public final class MemberDtos {

    private MemberDtos() {
    }

    /** One member: the account, and the level its membership grants. */
    public static MemberView toView(User account, String level) {
        return new MemberView(account.getUserName(), account.getScreenName(), account.getKind().name(), level);
    }

    /** One page of a weblog's members. */
    public record MemberPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<MemberView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore,
            @Schema(description = "How many members the weblog has, across every page.",
                    requiredMode = Schema.RequiredMode.REQUIRED) int total) {
    }

    /** Who holds a role on the weblog. No email address: the Members page shows none. */
    public record MemberView(
            @Schema(description = "The account's user name.", requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,
            @Schema(description = "The name the account is shown under.", requiredMode = Schema.RequiredMode.REQUIRED)
            String screenName,
            @Schema(description = "PERSON, or AGENT for an agent account.",
                    allowableValues = {"PERSON", "AGENT"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String kind,
            @Schema(description = "The strongest action the membership grants: ADMIN (manage the weblog and its "
                    + "members), POST (publish) or EDIT_DRAFT (write drafts, submitted for review).",
                    allowableValues = {"ADMIN", "POST", "EDIT_DRAFT"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String permission) {
    }
}
