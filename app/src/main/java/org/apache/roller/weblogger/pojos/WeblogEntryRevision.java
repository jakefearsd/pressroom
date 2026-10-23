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
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * One entry's title, text and summary as they stood before a save.
 *
 * <p>Written by {@code saveWeblogEntry} whenever one of those three actually
 * changes, so the newest revision is the state an author would get back by
 * undoing their last save -- not a copy of what they just wrote.
 *
 * <p>The snapshot is the whole content rather than a diff. Entries are small,
 * and storing diffs would make reading any revision depend on every earlier
 * row surviving, which is a bad property for the one feature people reach for
 * after something has already gone wrong.
 */
public class WeblogEntryRevision implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id = EntityIds.newId();
    private WeblogEntry weblogEntry;

    /**
     * From {@link Instant#now()} rather than {@code currentTimeMillis()}: the
     * column keeps microseconds, and two saves of one entry really can land in
     * the same millisecond. Ordering revisions is not cosmetic -- it decides
     * which one the pruner drops.
     *
     * <p>An entry's snapshot is built when it is loaded, so this initial
     * value is the load time; {@code WeblogEntry.contentBeingReplaced}
     * restamps it when the displacing save actually runs.
     */
    private Timestamp created = Timestamp.from(Instant.now());
    private String creator;
    private String title;
    private String text;
    private String summary;

    public WeblogEntryRevision() {
    }

    /**
     * A snapshot of this entry's current content, stamped with the current
     * time until the save that stores it restamps it.
     *
     * @param entry   the entry being snapshotted, whose CURRENT (pre-save)
     *                content is copied
     * @param creator username of whoever is making the save that displaces
     *                this content; null when it cannot be determined
     */
    public static WeblogEntryRevision of(WeblogEntry entry, String creator) {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setWeblogEntry(entry);
        revision.setCreator(creator);
        revision.setTitle(entry.getTitle());
        revision.setText(entry.getText());
        revision.setSummary(entry.getSummary());
        return revision;
    }

    /**
     * Whether this snapshot's content differs from the entry's current content.
     *
     * <p>The test that decides whether a save is worth recording: re-saving an
     * entry to change only its publication date or category must not deposit a
     * row identical to the one before it, which matters more now that nothing
     * is pruned by default.
     */
    public boolean differsFrom(WeblogEntry entry) {
        return entry == null
                || !equal(title, entry.getTitle())
                || !equal(text, entry.getText())
                || !equal(summary, entry.getSummary());
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Database surrogate key. */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** The entry this is a revision of. */
    public WeblogEntry getWeblogEntry() {
        return weblogEntry;
    }

    public void setWeblogEntry(WeblogEntry weblogEntry) {
        this.weblogEntry = weblogEntry;
    }

    /** When the save that displaced this content happened. */
    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    /** Username of whoever made that save, or null if it was not known. */
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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    @Override
    public String toString() {
        return "{" + id + ", " + created + ", " + title + "}";
    }

    @Override
    public boolean equals(Object other) {
        return other == this
                || (other instanceof WeblogEntryRevision o
                        && new EqualsBuilder().append(getId(), o.getId()).isEquals());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }
}
