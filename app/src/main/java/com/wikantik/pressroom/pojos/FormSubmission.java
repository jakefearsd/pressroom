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
 * A contact-form inquiry, persisted before any notification email is
 * attempted -- see the header comment on
 * {@code V015__form_submissions_and_tokens.sql} for why: if SMTP is down the
 * lead survives, which for a business running on leads is the failure that
 * matters.
 */
public class FormSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id = EntityIds.newId();
    private Weblog weblog;
    private String name;
    private String email;
    private String subject;
    private String message;
    private String pageSlug;
    private String entryAnchor;
    private String clientIp;
    private Timestamp created;

    public FormSubmission() {
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
     * The weblog this submission belongs to.
     */
    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * The submitter's name.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The submitter's email address.
     */
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * The submitted subject line, when present.
     */
    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    /**
     * The submitted message body.
     */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * The slug of the page this submission relates to, when applicable.
     */
    public String getPageSlug() {
        return pageSlug;
    }

    public void setPageSlug(String pageSlug) {
        this.pageSlug = pageSlug;
    }

    /**
     * The anchor of the entry this submission relates to, when applicable.
     */
    public String getEntryAnchor() {
        return entryAnchor;
    }

    public void setEntryAnchor(String entryAnchor) {
        this.entryAnchor = entryAnchor;
    }

    /**
     * The submitting client's IP address, when known.
     */
    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    /**
     * When the submission was received.
     */
    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "FormSubmission [id=" + getId() + ", weblog="
                + (getWeblog() == null ? null : getWeblog().getHandle())
                + ", email=" + getEmail() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof FormSubmission)) {
            return false;
        }
        final FormSubmission that = (FormSubmission) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
