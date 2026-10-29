package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;

/**
 * Views of an entry's revisions for the automation API (spec 2026-10-24,
 * section 5). A revision is the title, summary and text one save replaced,
 * with who made that save, when, and why when it said.
 */
public final class RevisionDtos {

    private RevisionDtos() {
    }

    /** One revision in an entry's list: everything but its content. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionSummary(
            String id,
            @Schema(description = "When the save that replaced this content was made.")
            Instant created,
            @Schema(description = "The user who made that save: the signed-in editor, or the API token's user.")
            String creator,
            @Schema(description = "Why that save was made, when it said: an update's revisionNote.")
            String note,
            @Schema(description = "The entry's title as it was, stored escaped like an entry's title.")
            String title) {
    }

    /** One revision with its content, and a diff from it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionView(
            String id, Instant created, String creator, String note, String title, String summary, String text,
            @Schema(description = "A unified diff (---/+++ lines, @@ hunks, 3 lines of context) from this "
                    + "revision's text to the entry's current text, or to the revision named by against. Lines "
                    + "starting with + are only in the current text (or that revision). Empty when they are the same.")
            String diff) {
    }

    /** The optional body of a revision restore. */
    public record RevisionRestore(
            @Schema(description = EntryDtos.EXPECTED_CONTENT_HASH_DESCRIPTION)
            String expectedContentHash) {
    }

    public static RevisionSummary toSummary(WeblogEntryRevision revision) {
        return new RevisionSummary(revision.getId(), instant(revision.getCreated()), revision.getCreator(),
                revision.getNote(), revision.getTitle());
    }

    public static RevisionView toView(WeblogEntryRevision revision, String diff) {
        return new RevisionView(revision.getId(), instant(revision.getCreated()), revision.getCreator(),
                revision.getNote(), revision.getTitle(), revision.getSummary(), revision.getText(), diff);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
