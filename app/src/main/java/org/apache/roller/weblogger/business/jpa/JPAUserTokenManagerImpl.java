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
import java.util.Date;

import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserTokenManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.util.TokenGenerator;

/**
 * JPA implementation of {@link UserTokenManager}.
 */
public class JPAUserTokenManagerImpl implements UserTokenManager {

    private static final Logger log = LoggerFactory.getLogger(JPAUserTokenManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPAUserTokenManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA User Token Manager");
        this.strategy = strategy;
    }

    @Override
    public String issueToken(User user, UserToken.Purpose purpose) throws WebloggerException {
        if (user.isAgent()) {
            // Nothing is ever mailed to an agent (spec 2026-10-13, section 1).
            throw new WebloggerException("An agent account has no password.");
        }
        String raw = TokenGenerator.newToken();
        UserToken token = new UserToken();
        token.setUser(user);
        token.setTokenSha256(TokenGenerator.sha256Hex(raw));
        token.setPurpose(purpose);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        token.setCreated(now);
        token.setExpires(new Timestamp(now.getTime() + TOKEN_TTL_MS));
        strategy.store(token);
        return raw;
    }

    @Override
    public UserToken validate(String rawToken) throws WebloggerException {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        TypedQuery<UserToken> query = strategy.getNamedQuery("UserToken.getByDigest", UserToken.class);
        query.setParameter(1, TokenGenerator.sha256Hex(rawToken));
        UserToken token;
        try {
            token = query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        if (token.getUsedAt() != null) {
            // already redeemed -- single-use
            return null;
        }
        if (token.getExpires() == null || token.getExpires().before(new Date())) {
            return null;
        }
        if (token.getUser() != null && token.getUser().isAgent()) {
            // A link presented for an agent account is refused.
            return null;
        }
        return token;
    }

    @Override
    public User consume(String rawToken) throws WebloggerException {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        // Atomic redeem: a read-then-write (validate(), then a separate
        // store()) lets two racing requests both observe usedAt == null and
        // both redeem the same token. This bulk UPDATE's WHERE clause is the
        // only thing that decides "still usable", and the database guarantees
        // at most one concurrent statement wins it -- the affected-row count
        // is the single source of truth, not a second SELECT.
        String digest = TokenGenerator.sha256Hex(rawToken);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Query consumeUpdate = strategy.getNamedUpdate("UserToken.consumeIfUsable");
        consumeUpdate.setParameter(1, now);
        consumeUpdate.setParameter(2, digest);
        consumeUpdate.setParameter(3, now);
        if (consumeUpdate.executeUpdate() != 1) {
            return null;
        }
        TypedQuery<UserToken> query = strategy.getNamedQuery("UserToken.getByDigest", UserToken.class);
        query.setParameter(1, digest);
        try {
            return query.getSingleResult().getUser();
        } catch (NoResultException e) {
            // Cannot happen -- the update above just touched this exact row --
            // but a manager must not throw on a lookup miss.
            return null;
        }
    }

    @Override
    public void removeTokens(User user) throws WebloggerException {
        Query removeTokens = strategy.getNamedUpdate("UserToken.removeByUser");
        removeTokens.setParameter(1, user);
        removeTokens.executeUpdate();
    }
}
