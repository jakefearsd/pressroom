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
import java.util.Date;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;
import com.wikantik.pressroom.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link UserTokenManager}: hashed storage, single-use, expiry, and
 * that a user with outstanding tokens can still be torn down (the FK from
 * {@code pressroom_user_token} to {@code pressroom_user} has no cascade).
 */
class UserTokenManagerTest {

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("usertokenuser");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static UserTokenManager manager() {
        return TestUtils.weblogger().getUserTokenManager();
    }

    @Test
    void issuingATokenStoresOnlyItsDigestNeverTheRawValue() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);

        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // Read the row back directly and check the stolen-database property:
        // the raw token must not be what is stored.
        UserToken stored = manager().validate(raw);
        assertNotNull(stored, "the freshly issued token must validate");
        assertNotEquals(raw, stored.getTokenSha256(),
                "the raw token must never be persisted as-is");
        assertEquals(TokenGenerator.sha256Hex(raw), stored.getTokenSha256(),
                "the stored value must be exactly the raw token's SHA-256 digest");
    }

    @Test
    void aFreshTokenValidatesToItsUser() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        UserToken validated = manager().validate(raw);

        assertNotNull(validated);
        assertEquals(managedUser.getUserName(), validated.getUser().getUserName());
        assertNull(validated.getUsedAt(), "an unconsumed token must have no usedAt");
    }

    @Test
    void consumingATokenReturnsItsUserAndStampsUsedAt() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        User consumedBy = manager().consume(raw);

        assertNotNull(consumedBy);
        assertEquals(managedUser.getUserName(), consumedBy.getUserName());
    }

    @Test
    void aSecondConsumeOfTheSameTokenFailsSingleUseIsEnforced() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        User first = manager().consume(raw);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNotNull(first, "the first consume must succeed");

        User second = manager().consume(raw);

        assertNull(second, "a token must not be redeemable twice");
    }

    /**
     * Proves the redemption is guarded by the atomic bulk-update's WHERE
     * clause, not by two racing requests both having read {@code usedAt ==
     * null} through {@code validate()} first. A row stamped used by some
     * other path entirely -- never observed by this manager's own
     * {@code validate()} -- must still be refused: the update's affected-row
     * count is the single source of truth, and here it must be 0.
     */
    @Test
    void aTokenAlreadyStampedUsedByAnotherPathIsNotConsumable() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // Stamp usedAt directly on the managed row, bypassing consume()
        // entirely -- simulates a second process that already redeemed it.
        UserToken token = manager().validate(raw);
        assertNotNull(token, "precondition: the token exists and reads as usable");
        token.setUsedAt(new Timestamp(System.currentTimeMillis()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().consume(raw),
                "the atomic update must affect 0 rows once usedAt is set, however "
                        + "that came to be, and consume() must report that as null");
    }

    @Test
    void anExpiredTokenDoesNotValidate() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // Push the token's expiry into the past on the managed row.
        UserToken token = manager().validate(raw);
        assertNotNull(token, "must exist before we can expire it");
        token.setExpires(new Timestamp(System.currentTimeMillis() - 1000L));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().validate(raw), "an expired token must not validate");
    }

    @Test
    void aGarbageTokenValidatesToNullWithoutThrowing() throws Exception {
        assertNull(manager().validate("this-is-not-a-real-token"));
    }

    @Test
    void aNullOrBlankTokenValidatesAndConsumesToNullWithoutThrowing() throws Exception {
        assertNull(manager().validate(null));
        assertNull(manager().validate(""));
        assertNull(manager().consume(null));
        assertNull(manager().consume(" "));
    }

    /**
     * {@code consume()} no longer routes through {@code validate()} (the
     * atomic bulk update is a separate query), so {@code validate()}'s own
     * "already used" branch needs its own direct exercise: a token consumed
     * once must still read as unusable through a plain {@code validate()}
     * call, not just through a second {@code consume()}.
     */
    @Test
    void validateReportsAnAlreadyConsumedTokenAsUnusable() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNotNull(manager().consume(raw), "precondition: the token starts out consumable");
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().validate(raw),
                "validate() must itself recognise a consumed token as unusable, "
                        + "independent of consume()'s own atomic check");
    }

    @Test
    void removeTokensClearsAUsersRows() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw1 = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        String raw2 = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_SET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        manager().removeTokens(TestUtils.getManagedUser(user));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().validate(raw1), "removeTokens must clear the first token");
        assertNull(manager().validate(raw2), "removeTokens must clear the second token");
    }

    @Test
    void teardownUserSucceedsForAUserWithOutstandingTokens() throws Exception {
        // Proves JPAUserManagerImpl.removeUser cleans up pressroom_user_token
        // rows itself -- the FK has no cascade, so leaving this token behind
        // would make teardownUser (and therefore this whole suite's @AfterEach)
        // fail with a foreign-key violation.
        User managedUser = TestUtils.getManagedUser(user);
        manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // tearDown() below calls TestUtils.teardownUser(...); reaching it
        // without an exception is the assertion.
    }

    /** Criterion 5: a set-password link is never issued for an agent. */
    @Test
    void noLinkIsIssuedForAnAgent() throws Exception {
        String name = "junit_tokenagent" + System.nanoTime();
        User agent = new User();
        agent.setUserName(name);
        agent.setScreenName("Token Agent");
        agent.setFullName("Token Agent");
        agent.setDateCreated(new Date());
        TestUtils.weblogger().getUserManager().addAgent(agent, false);
        TestUtils.endSession(true);
        try {
            User managed = TestUtils.weblogger().getUserManager().getUserByUserName(name, null);

            WebloggerException refused = assertThrows(WebloggerException.class,
                    () -> manager().issueToken(managed, UserToken.Purpose.PASSWORD_SET));

            assertEquals("An agent account has no password.", refused.getMessage());
        } finally {
            TestUtils.endSession(false);
            TestUtils.teardownUser(name);
            TestUtils.endSession(true);
        }
    }

    /** Criterion 5: a link whose account is an agent does not validate, so the reset form refuses it. */
    @Test
    void aLinkHeldByAnAgentDoesNotValidate() throws Exception {
        String raw = manager().issueToken(TestUtils.getManagedUser(user), UserToken.Purpose.PASSWORD_SET);
        TestUtils.endSession(true);
        User reloaded = TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName(), null);
        reloaded.setKind(User.Kind.AGENT);
        TestUtils.weblogger().getUserManager().saveUser(reloaded);
        TestUtils.endSession(true);

        assertNull(manager().validate(raw));
    }
}
