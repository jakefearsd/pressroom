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
 * the entry's stored title, which is stored escaped, so it is passed through
 * unchanged. A HIDDEN comment with an empty body is one its author deleted.
 */
public final class CommentDtos {

    private CommentDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommentView(String id, String entryId, String entryTitle, String status, String body,
            Instant created, Instant statusChanged, String authorUserName,
            String authorDisplayName, boolean authorIsOwner, String replyTo, String reply) {
    }

    public record CommentPage(List<CommentView> items, int offset, int limit, boolean hasMore) {
    }

    /** {@code "PUBLISHED"} or {@code "HIDDEN"}; anything else is a 400. */
    public record CommentPatch(String status) {
    }

    public record CommentReply(String body) {
    }

    public static CommentView toView(ReaderComment c) {
        User author = c.getAuthor();
        return new CommentView(
                c.getId(),
                c.getEntry().getId(),
                c.getEntry().getTitle(),
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

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
