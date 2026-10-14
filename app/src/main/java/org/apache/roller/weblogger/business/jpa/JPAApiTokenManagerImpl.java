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
package org.apache.roller.weblogger.business.jpa;

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.util.TokenGenerator;

/**
 * JPA implementation of {@link ApiTokenManager}.
 */
public class JPAApiTokenManagerImpl implements ApiTokenManager {

    private static final Logger log = LoggerFactory.getLogger(JPAApiTokenManagerImpl.class);

    private static final long LAST_USED_RESOLUTION_MS = 60L * 60L * 1000L;

    private final JPAPersistenceStrategy strategy;

    protected JPAApiTokenManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA API Token Manager");
        this.strategy = strategy;
    }

    @Override
    public Issued issueToken(User user, String label, String scopeWeblog,
                             ApiToken.Role role, Timestamp expiresAt, User issuedBy) throws WebloggerException {
        String raw = TOKEN_PREFIX + TokenGenerator.newToken();
        ApiToken token = new ApiToken();
        token.setUser(user);
        token.setLabel(label);
        token.setTokenSha256(TokenGenerator.sha256Hex(raw));
        token.setScopeWeblog(scopeWeblog);
        token.setScopeRole(role);
        token.setCreated(new Timestamp(System.currentTimeMillis()));
        token.setExpiresAt(expiresAt);
        token.setIssuedBy(issuedBy == null ? null : issuedBy.getId());
        strategy.store(token);
        return new Issued(raw, token);
    }

    @Override
    public ApiToken authenticate(String rawToken) throws WebloggerException {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getByDigest", ApiToken.class);
        query.setParameter(1, TokenGenerator.sha256Hex(rawToken));
        ApiToken token;
        try {
            token = query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        if (!token.isUsable()) {
            return null;
        }
        touchLastUsed(token);
        return token;
    }

    /**
     * Coarse on purpose: writing last_used_at on every call would make each
     * API read a write too. An hour's resolution is plenty for deciding
     * whether a token is still in use before revoking it.
     *
     * <p>{@code strategy.flush()} after the store is not optional. This
     * write only begins a transaction; nothing commits it automatically
     * (see {@code TokensApi}'s class javadoc for the same trap one layer
     * up). {@code authenticate()} runs from {@code ApiTokenAuthFilter} on
     * every Bearer-authenticated request, including plain reads (e.g.
     * {@code GET /api/v1/me}) that never call {@code weblogger.flush()}
     * anywhere in their own path -- without this flush, {@code lastUsedAt}
     * committed only when the request happened to also reach a write
     * endpoint, and stayed null forever for a token used exclusively for
     * reads. That field is not decorative: an operator reads it to decide
     * whether a token is still in daily use before revoking it.
     */
    private void touchLastUsed(ApiToken token) throws WebloggerException {
        long now = System.currentTimeMillis();
        Timestamp last = token.getLastUsedAt();
        if (last == null || now - last.getTime() > LAST_USED_RESOLUTION_MS) {
            token.setLastUsedAt(new Timestamp(now));
            strategy.store(token);
            strategy.flush();
        }
    }

    @Override
    public List<ApiToken> getTokens(User user) throws WebloggerException {
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getByUser", ApiToken.class);
        query.setParameter(1, user);
        return query.getResultList();
    }

    @Override
    public boolean revoke(User user, String tokenId) throws WebloggerException {
        ApiToken token = getToken(tokenId);
        // Ownership check, not a convenience: tokenId is client input and this
        // is a global by-id load, so without it any user could revoke any
        // other user's tokens.
        if (token == null || !token.getUser().getId().equals(user.getId())) {
            return false;
        }
        revoke(token);
        return true;
    }

    @Override
    public ApiToken getToken(String tokenId) throws WebloggerException {
        if (tokenId == null || tokenId.isBlank()) {
            return null;
        }
        return (ApiToken) strategy.load(ApiToken.class, tokenId);
    }

    @Override
    public void revoke(ApiToken token) throws WebloggerException {
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(new Timestamp(System.currentTimeMillis()));
            strategy.store(token);
        }
    }

    @Override
    public List<ApiToken> getLiveTokens(User user) throws WebloggerException {
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getLiveByUser", ApiToken.class);
        query.setParameter(1, user);
        query.setParameter(2, new Timestamp(System.currentTimeMillis()));
        return query.getResultList();
    }

    @Override
    public List<ApiToken> getAllTokens(boolean includeInactive) throws WebloggerException {
        if (includeInactive) {
            return strategy.getNamedQuery("ApiToken.getAll", ApiToken.class).getResultList();
        }
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getAllLive", ApiToken.class);
        query.setParameter(1, new Timestamp(System.currentTimeMillis()));
        return query.getResultList();
    }
}
