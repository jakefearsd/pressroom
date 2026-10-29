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
 * A single-use, expiring account token serving both forgot-password and the
 * admin "send set-password link" flow.
 *
 * <p>Only a SHA-256 digest of the token is stored ({@code tokenSha256}) --
 * see the header comment on {@code V015__form_submissions_and_tokens.sql} for
 * why: a database read must not yield a working reset link. The raw token
 * exists only in memory for the moment it is minted and in the emailed URL.
 */
public class UserToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What a token may be redeemed for. */
    public enum Purpose {
        PASSWORD_RESET, PASSWORD_SET
    }

    private String id = EntityIds.newId();
    private User user;
    private String tokenSha256;
    private Purpose purpose;
    private Timestamp created;
    private Timestamp expires;
    private Timestamp usedAt;

    public UserToken() {
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
     * The user this token was issued to.
     */
    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    /**
     * The SHA-256 digest of the raw token. The raw value itself is never
     * persisted.
     */
    public String getTokenSha256() {
        return tokenSha256;
    }

    public void setTokenSha256(String tokenSha256) {
        this.tokenSha256 = tokenSha256;
    }

    /**
     * What this token may be redeemed for.
     */
    public Purpose getPurpose() {
        return purpose;
    }

    public void setPurpose(Purpose purpose) {
        this.purpose = purpose;
    }

    /**
     * When this token was issued.
     */
    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    /**
     * When this token stops being valid.
     */
    public Timestamp getExpires() {
        return expires;
    }

    public void setExpires(Timestamp expires) {
        this.expires = expires;
    }

    /**
     * When this token was redeemed, or null while it is still usable.
     * Single-use is enforced by checking this field, not by deleting the row.
     */
    public Timestamp getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Timestamp usedAt) {
        this.usedAt = usedAt;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "UserToken [id=" + getId() + ", user="
                + (getUser() == null ? null : getUser().getUserName())
                + ", purpose=" + getPurpose() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof UserToken)) {
            return false;
        }
        final UserToken that = (UserToken) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
