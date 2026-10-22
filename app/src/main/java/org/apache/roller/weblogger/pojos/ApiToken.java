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

import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * A long-lived, revocable credential for the automation API.
 *
 * <p>Only a SHA-256 digest of the token is stored ({@code tokenSha256}) --
 * see the header comment on {@code V026__api_tokens.sql} for why: a database
 * read must not yield a working credential. The raw token exists only in
 * memory for the moment it is minted, and is returned to the caller once.
 */
public class ApiToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What a token may do, at most. Never a grant -- only a ceiling. */
    public enum Role { READ, POST, ADMIN }

    private String id = EntityIds.newId();
    private User user;
    private String label;
    private String tokenSha256;
    private String scopeWeblog;
    private Role scopeRole;
    private Timestamp created;
    private Timestamp lastUsedAt;
    private Timestamp expiresAt;
    private Timestamp revokedAt;
    private String issuedBy;

    public ApiToken() {
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
     * The author-supplied label identifying this token, e.g. "seo-agent".
     */
    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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
     * The single weblog this token may act on, or null for every weblog the
     * owning user can reach.
     */
    public String getScopeWeblog() {
        return scopeWeblog;
    }

    public void setScopeWeblog(String scopeWeblog) {
        this.scopeWeblog = scopeWeblog;
    }

    /**
     * What this token may do, at most.
     */
    public Role getScopeRole() {
        return scopeRole;
    }

    public void setScopeRole(Role scopeRole) {
        this.scopeRole = scopeRole;
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
     * When this token was last used to authenticate, updated coarsely (see
     * {@code JPAApiTokenManagerImpl}).
     */
    public Timestamp getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Timestamp lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    /**
     * When this token stops being valid, or null for a token that never
     * expires on its own.
     */
    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    /**
     * When this token was revoked, or null while it is still live.
     */
    public Timestamp getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Timestamp revokedAt) {
        this.revokedAt = revokedAt;
    }

    /**
     * The id of the user who minted this token -- the owner for a self-mint,
     * a site admin for a token minted on an agent's page -- or null for a
     * token minted before V033.
     */
    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }

    /** True when this token is neither revoked nor past its expiry. */
    public boolean isUsable() {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.after(new Timestamp(System.currentTimeMillis()));
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "ApiToken [id=" + getId() + ", user="
                + (getUser() == null ? null : getUser().getUserName())
                + ", label=" + getLabel()
                + ", scopeWeblog=" + getScopeWeblog()
                + ", scopeRole=" + getScopeRole() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof ApiToken)) {
            return false;
        }
        final ApiToken that = (ApiToken) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
