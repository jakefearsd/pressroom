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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;

/**
 * Interface to single-use, expiring account token bookkeeping, serving both
 * the forgot-password flow and the admin "send set-password link" flow.
 *
 * <p>Only a SHA-256 digest of a token is ever stored -- see the header
 * comment on {@code V015__form_submissions_and_tokens.sql} and
 * {@link UserToken} for why. The raw token exists only in memory for the
 * moment {@link #issueToken} mints it and in the URL it is emailed in.
 */
public interface UserTokenManager {

    /** How long a freshly issued token remains valid. */
    long TOKEN_TTL_MS = 60L * 60L * 1000L;

    /**
     * Issue a fresh token for a user, storing only its digest.
     *
     * @return the raw token -- the only moment it exists in memory. Callers
     *         must hand it straight to the recipient (typically embedded in
     *         an emailed URL) and never persist it themselves.
     */
    String issueToken(User user, UserToken.Purpose purpose) throws WebloggerException;

    /**
     * The token row a raw token digests to, when it is still usable: found,
     * unused and unexpired. Read-only -- does not consume the token.
     *
     * @return the token, or null when the raw value does not match a usable
     *         token
     */
    UserToken validate(String rawToken) throws WebloggerException;

    /**
     * Validates a raw token and, if usable, marks it used so it cannot be
     * redeemed again.
     *
     * @return the token's user, or null when the raw value does not match a
     *         usable token
     */
    User consume(String rawToken) throws WebloggerException;

    /**
     * Remove every token belonging to a user.
     */
    void removeTokens(User user) throws WebloggerException;
}
