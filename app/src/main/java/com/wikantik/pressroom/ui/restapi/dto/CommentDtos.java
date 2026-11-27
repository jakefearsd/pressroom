/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.restapi.dto;

import com.wikantik.pressroom.ui.restapi.Paging;
import io.swagger.v3.oas.annotations.media.Schema;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;

/**
 * Views of a signed-in reader's comment for the moderation API.
 *
 * <p>The view carries no email address and no author id, on purpose: the
 * author is named by user name and display name only. {@code entryTitle} is
 * the entry's title as plain text (stored escaped, unescaped here, as an
 * entry view's is). A HIDDEN comment with an empty body is one its author deleted.
 */
public final class CommentDtos {

    private CommentDtos() {
    }

    /** {@link QueueComment} repeats these fields by hand (QueueCommentShapeTest pins them together). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommentView(String id, String entryId, String entryTitle, String status, String body,
            Instant created, Instant statusChanged, String authorUserName,
            String authorDisplayName, boolean authorIsOwner, String replyTo, String reply) {
    }

    public record CommentPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<CommentView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore,
            @Schema(description = "How many comments match the filters in all, whatever the page: the count a "
                    + "screen shows without paging to the end.", requiredMode = Schema.RequiredMode.REQUIRED)
            long total) {
    }

    /** The blog a queued comment is on: what the approval queue names it by and acts on it with. */
    public record QueueWeblog(
            @Schema(description = "The blog's handle: the {handle} of the per-blog comment routes that act on the row.",
                    requiredMode = Schema.RequiredMode.REQUIRED) String handle,
            @Schema(description = "The blog's name, as the main menu shows it.",
                    requiredMode = Schema.RequiredMode.REQUIRED) String name) {
    }

    /**
     * A comment in the cross-blog approval queue: every {@link CommentView} field, plus the blog it is on and the
     * entry's address, so a row can be read and acted on without another call.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueComment(String id, String entryId, String entryTitle, String status, String body,
            Instant created, Instant statusChanged, String authorUserName,
            String authorDisplayName, boolean authorIsOwner, String replyTo, String reply,
            @Schema(description = "The blog the comment is on.", requiredMode = Schema.RequiredMode.REQUIRED)
            QueueWeblog weblog, String entryPermalink) {
    }

    public record QueuePage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<QueueComment> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = "The page size asked for, after the cap of 100.",
                    requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore,
            @Schema(description = "How many comments match across the caller's blogs, whatever the page.",
                    requiredMode = Schema.RequiredMode.REQUIRED) long total) {
    }

    /** {@code "PUBLISHED"} or {@code "HIDDEN"}; anything else is a 400. */
    public record CommentPatch(
            @Schema(description = "On update: PUBLISHED or HIDDEN, the only statuses a comment can be set to.")
            String status) {
    }

    public record CommentReply(
            @Schema(description = "The reply's text, written as the token's user and published at once; at most "
                    + "4000 characters.")
            String body) {
    }

    public static CommentView toView(ReaderComment c) {
        User author = c.getAuthor();
        return new CommentView(
                c.getId(),
                c.getEntry().getId(),
                EntryFieldRules.plainTitle(c.getEntry().getTitle()),
                c.getStatus().name(),
                c.getBody() == null ? "" : c.getBody(),
                instant(c.getCreated()),
                instant(c.getStatusChanged()),
                author == null ? null : author.getUserName(),
                author == null ? null : author.getScreenName(),
                c.isAuthorIsOwner(),
                c.getReplyTo() == null ? null : c.getReplyTo().getId(),
                c.getReply() == null ? null : c.getReply().getId());
    }

    public static QueueComment toQueue(ReaderComment c, String weblogName, String entryPermalink) {
        CommentView v = toView(c);
        return new QueueComment(v.id(), v.entryId(), v.entryTitle(), v.status(), v.body(), v.created(),
                v.statusChanged(), v.authorUserName(), v.authorDisplayName(), v.authorIsOwner(), v.replyTo(),
                v.reply(), new QueueWeblog(c.getEntry().getWebsite().getHandle(), weblogName), entryPermalink);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
