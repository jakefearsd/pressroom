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
package org.apache.roller.weblogger.pojos;

import java.io.Serializable;
import java.util.Date;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.util.UUIDGenerator;

/**
 * A comment by a signed-in reader on an entry, or the owner's single reply to
 * one. Top-level comments have no {@code replyTo}; a reply points at its
 * parent and is deleted with it.
 */
public class ReaderComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Where a comment is in moderation. */
    public enum Status {
        PENDING, PUBLISHED, HIDDEN
    }

    private String id = UUIDGenerator.generateUUID();
    private WeblogEntry entry;
    private User author;
    private ReaderComment replyTo;
    private ReaderComment reply;
    private String body;
    private Status status = Status.PENDING;
    private boolean authorIsOwner;
    private Date created;
    private Date statusChanged;

    public ReaderComment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public WeblogEntry getEntry() {
        return entry;
    }

    public void setEntry(WeblogEntry entry) {
        this.entry = entry;
    }

    public User getAuthor() {
        return author;
    }

    public void setAuthor(User author) {
        this.author = author;
    }

    public ReaderComment getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(ReaderComment replyTo) {
        this.replyTo = replyTo;
    }

    /** The reply to this comment, or null. The inverse of {@link #getReplyTo()}; read-only. */
    public ReaderComment getReply() {
        return reply;
    }

    public void setReply(ReaderComment reply) {
        this.reply = reply;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isAuthorIsOwner() {
        return authorIsOwner;
    }

    public void setAuthorIsOwner(boolean authorIsOwner) {
        this.authorIsOwner = authorIsOwner;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getStatusChanged() {
        return statusChanged;
    }

    public void setStatusChanged(Date statusChanged) {
        this.statusChanged = statusChanged;
    }

    @Override
    public boolean equals(Object other) {
        return other == this
                || (other instanceof ReaderComment && getId().equals(((ReaderComment) other).getId()));
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }
}
