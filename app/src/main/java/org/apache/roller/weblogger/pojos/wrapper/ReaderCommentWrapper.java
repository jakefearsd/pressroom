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
package org.apache.roller.weblogger.pojos.wrapper;

import java.util.Date;

import org.apache.roller.weblogger.business.comments.CommentBodyRenderer;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;

/**
 * What a theme sees of a reader's comment on the entry page: read-only, and
 * nothing a template could use to reach the entity or its author. It
exposes no username: the page is public and cached, and an owner's
username is their login name.
 *
 * <p>Two values are deliberately left for the template: the display name is
 * returned RAW and the template escapes it ({@code $utils.escapeHTML}), the
 * same rule as every other user-typed string in a theme; the body comes back
 * as finished, safe HTML from {@link CommentBodyRenderer} and is emitted bare.
 */
public final class ReaderCommentWrapper {

    private final ReaderComment pojo;

    private ReaderCommentWrapper(ReaderComment pojo) {
        this.pojo = pojo;
    }

    /** The wrapper, or null for a null comment. */
    public static ReaderCommentWrapper wrap(ReaderComment comment) {
        return comment == null ? null : new ReaderCommentWrapper(comment);
    }

    public String getId() {
        return pojo.getId();
    }

    /** The writer's screen name, unescaped: the template escapes it. Blank when the author is gone. */
    public String getDisplayName() {
        User author = pojo.getAuthor();
        return author == null || author.getScreenName() == null ? "" : author.getScreenName();
    }

    /** Whether the writer held a permission on the weblog when posting: the "Author" badge. */
    public boolean isAuthorIsOwner() {
        return pojo.isAuthorIsOwner();
    }

    public Date getCreated() {
        Date created = pojo.getCreated();
        return created == null ? null : new Date(created.getTime());
    }

    /** The body as safe HTML; see {@link CommentBodyRenderer}. */
    public String getBodyHtml() {
        return CommentBodyRenderer.toHtml(pojo.getBody());
    }

    /**
     * The owner's reply, only when it is PUBLISHED. {@link ReaderComment#getReply()}
     * is the true inverse of {@code replyTo} and so holds the reply in any
     * status; a pending or hidden reply must not reach the page.
     */
    public ReaderCommentWrapper getReply() {
        ReaderComment reply = pojo.getReply();
        return reply != null && reply.getStatus() == ReaderComment.Status.PUBLISHED ? wrap(reply) : null;
    }
}
