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
package com.wikantik.pressroom.business;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;

/**
 * Long-lived credentials for the automation API.
 *
 * <p>Distinct from {@link UserTokenManager}, whose tokens are single-use and
 * expire in an hour. These are multi-use, optionally perpetual, and revocable.
 */
public interface ApiTokenManager {

    /** Prefix on every raw token, so one is recognisable in a log or a paste. */
    String TOKEN_PREFIX = "prs_";

    /**
     * The result of minting a token: the one-time raw secret (never stored,
     * never retrievable again) alongside the persisted row it belongs to.
     * Returning both avoids a caller having to read the row back by some
     * other key just to describe what it minted -- a read-back that can
     * miss (see {@code ApiTokenManager.issueToken}'s history: a caller once
     * matched the digest of the raw secret against {@code getTokens(user)}
     * to find it again, which meant a miss null-dereferenced a live,
     * already-committed credential the raw secret for which had already
     * been discarded).
     */
    record Issued(String rawToken, ApiToken token) { }

    /**
     * Mints a token. {@link Issued#rawToken()} is the only time the secret
     * exists outside the caller's hands -- only its digest is stored.
     *
     * @param issuedBy the account minting it: the owner for a self-mint, a
     *                 site admin minting for an agent; its id is recorded
     */
    Issued issueToken(User user, String label, String scopeWeblog,
                      ApiToken.Role role, Timestamp expiresAt, User issuedBy) throws WebloggerException;

    /** A self-mint, as {@code POST /api/v1/tokens} does: the owner is the issuer. */
    default Issued issueToken(User user, String label, String scopeWeblog,
                              ApiToken.Role role, Timestamp expiresAt) throws WebloggerException {
        return issueToken(user, label, scopeWeblog, role, expiresAt, user);
    }

    /** The token behind this secret, or null if unknown, expired or revoked. */
    ApiToken authenticate(String rawToken) throws WebloggerException;

    List<ApiToken> getTokens(User user) throws WebloggerException;

    /** Revokes {@code tokenId} if {@code user} owns it. False otherwise. */
    boolean revoke(User user, String tokenId) throws WebloggerException;

    /** The token with this id, live or not, or null for a blank or unknown id. */
    ApiToken getToken(String tokenId) throws WebloggerException;

    /**
     * Revokes {@code token} if it is not revoked already; revoking it again
     * changes nothing. No ownership check: the caller decides who may revoke
     * what. Like every write here it only begins a transaction; flush it.
     */
    void revoke(ApiToken token) throws WebloggerException;

    /** {@code user}'s tokens that are neither revoked nor expired, newest first. */
    List<ApiToken> getLiveTokens(User user) throws WebloggerException;

    /** Every token on the site, newest first: only live ones unless {@code includeInactive}. */
    List<ApiToken> getAllTokens(boolean includeInactive) throws WebloggerException;
}
