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

import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * A first-party outcome event: something the analytics tier cannot see from
 * traffic alone (a form submitted, a newsletter subscription, an entry
 * published).
 *
 * <p>Deliberately has no {@code metadata} field, even though the underlying
 * {@code pressroom_event.metadata} column exists -- see the header comment on
 * {@code V015__form_submissions_and_tokens.sql} for why: nothing writes it
 * yet, and the cast layer can wait for a writer.
 */
public class PressroomEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The kinds of outcome this event can record. */
    public enum EventType {
        FORM_SUBMITTED, NEWSLETTER_SUBSCRIBED, ENTRY_PUBLISHED
    }

    private String id = EntityIds.newId();
    private Weblog weblog;
    private EventType eventType;
    private String entryAnchor;
    private String pageSlug;
    private Timestamp occurredAt;

    public PressroomEvent() {
    }

    /**
     * Database surrogate key.
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * The weblog this event belongs to.
     */
    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * What kind of outcome this event records.
     */
    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    /**
     * The anchor of the entry this event relates to, when applicable.
     */
    public String getEntryAnchor() {
        return entryAnchor;
    }

    public void setEntryAnchor(String entryAnchor) {
        this.entryAnchor = entryAnchor;
    }

    /**
     * The slug of the page this event relates to, when applicable.
     */
    public String getPageSlug() {
        return pageSlug;
    }

    public void setPageSlug(String pageSlug) {
        this.pageSlug = pageSlug;
    }

    /**
     * When the event occurred.
     */
    public Timestamp getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Timestamp occurredAt) {
        this.occurredAt = occurredAt;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "PressroomEvent [id=" + getId() + ", weblog="
                + (getWeblog() == null ? null : getWeblog().getHandle())
                + ", eventType=" + getEventType() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof PressroomEvent)) {
            return false;
        }
        final PressroomEvent that = (PressroomEvent) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
