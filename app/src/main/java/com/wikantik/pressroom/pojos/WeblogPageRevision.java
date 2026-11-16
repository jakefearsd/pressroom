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

package com.wikantik.pressroom.pojos;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * One page's title and content as they stood before a save.
 *
 * <p>Written by {@code savePage} whenever one of the two actually changes,
 * so the newest revision is what an author gets back by undoing their last
 * save. The page's settings (slug, status, navigation, SEO fields) are not
 * prose and are not held: restoring a revision can never republish a page or
 * move its address.
 *
 * <p>A whole snapshot, not a diff, for the reason {@link WeblogEntryRevision}
 * gives: reading any revision must not depend on every earlier row surviving.
 */
public class WeblogPageRevision implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id = EntityIds.newId();
    private WeblogPage page;

    /**
     * Microseconds, from {@link Instant#now()}: ordering decides which
     * revision a prune drops. A page's snapshot is built when it is loaded,
     * so this initial value is the load time;
     * {@code WeblogPage.contentBeingReplaced} restamps it when the displacing
     * save runs.
     */
    private Timestamp created = Timestamp.from(Instant.now());
    private String creator;
    private String title;
    private String content;
    private String note;
    private boolean kept;

    public WeblogPageRevision() {
    }

    /** The page's present title and content as an unsaved revision. */
    public static WeblogPageRevision of(WeblogPage page, String creator) {
        WeblogPageRevision revision = new WeblogPageRevision();
        revision.setPage(page);
        revision.setCreator(creator);
        revision.setTitle(page.getTitle());
        revision.setContent(page.getContent());
        return revision;
    }

    /** Whether the page's title or content is no longer what this revision holds. */
    public boolean differsFrom(WeblogPage other) {
        return other == null
                || !equal(title, other.getTitle())
                || !equal(content, other.getContent());
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public WeblogPage getPage() {
        return page;
    }

    public void setPage(WeblogPage page) {
        this.page = page;
    }

    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    /** Who made the save that replaced this content; null for a save no person made. */
    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /** Why the save that left this revision was made, as its caller said; null when it said nothing. */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /**
     * Whether an author has protected this revision from pruning. Stored
     * from the first release of page revisions; nothing sets it until the
     * retention work (spec 2026-11-15, Part 3).
     */
    public boolean isKept() {
        return kept;
    }

    public void setKept(boolean kept) {
        this.kept = kept;
    }

    @Override
    public String toString() {
        return "{" + id + ", " + created + ", " + title + "}";
    }

    @Override
    public boolean equals(Object other) {
        return other == this
                || (other instanceof WeblogPageRevision o
                        && new EqualsBuilder().append(getId(), o.getId()).isEquals());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }
}
