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
package com.wikantik.pressroom.business.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.config.GeneratedSecrets;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.Test;

/** {@link UnsubscribeTokens}: the HMAC-signed one-click unsubscribe token. */
class UnsubscribeTokensTest {

    private static final String KEY = "a-test-key-that-is-long-enough-for-hmac-0123456789";

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(() -> KEY);

    @Test
    void aTokenRoundTripsToItsUserId() {
        String token = tokens.issue("user-1", UnsubscribeTokens.REPLIES);

        assertEquals(Optional.of("user-1"), tokens.verify(token, UnsubscribeTokens.REPLIES));
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "URL-safe without escaping: " + token);
        assertEquals(token, tokens.issue("user-1", UnsubscribeTokens.REPLIES),
                "the same in every mail to that user: it does not expire");
    }

    @Test
    void aTamperedTokenIsRefused() {
        String token = tokens.issue("user-1", UnsubscribeTokens.REPLIES);
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String forged = encode(decoded.replace("user-1", "user-2"));

        assertEquals(Optional.empty(), tokens.verify(forged, UnsubscribeTokens.REPLIES),
                "another user's id under the first user's signature");
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertEquals(Optional.empty(), tokens.verify(flipped, UnsubscribeTokens.REPLIES));
        assertEquals(Optional.empty(),
                new UnsubscribeTokens(() -> KEY + "x").verify(token, UnsubscribeTokens.REPLIES),
                "signed with another key");
    }

    @Test
    void aTokenForAnotherPurposeIsRefused() {
        String token = tokens.issue("user-1", "newsletter");

        assertEquals(Optional.empty(), tokens.verify(token, UnsubscribeTokens.REPLIES));
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        assertEquals(Optional.empty(),
                tokens.verify(encode(decoded.replace("newsletter", "replies")), UnsubscribeTokens.REPLIES),
                "the purpose is signed, so relabelling it breaks the signature");
    }

    @Test
    void malformedTokensAreRefusedNotThrown() {
        for (String bad : new String[] {null, "", "   ", "not base64!!", encode("only-one-part"),
                encode("a|b"), encode("a|b|c|d"), encode("|replies|sig"), encode("user|replies|")}) {
            assertEquals(Optional.empty(), tokens.verify(bad, UnsubscribeTokens.REPLIES), String.valueOf(bad));
        }
    }

    @Test
    void anIdThatWouldBreakTheFormatIsNotIssued() {
        assertThrows(IllegalArgumentException.class, () -> tokens.issue("a|b", UnsubscribeTokens.REPLIES));
        assertThrows(IllegalArgumentException.class, () -> tokens.issue("", UnsubscribeTokens.REPLIES));
        assertThrows(IllegalArgumentException.class, () -> tokens.issue("u", "re|plies"));
    }

    /** No key means nothing is signed and nothing verifies: fail closed. */
    @Test
    void withNoKeyNothingIsIssuedOrAccepted() {
        String token = tokens.issue("user-1", UnsubscribeTokens.REPLIES);
        for (String missing : new String[] {null, "", "  "}) {
            UnsubscribeTokens keyless = new UnsubscribeTokens(() -> missing);
            assertThrows(IllegalStateException.class, () -> keyless.issue("user-1", UnsubscribeTokens.REPLIES));
            assertEquals(Optional.empty(), keyless.verify(token, UnsubscribeTokens.REPLIES));
        }
    }

    /** The bean reads the generated secret's row from the tier, at use, not at construction. */
    @Test
    void theBeanReadsTheGeneratedSecretFromTheTier() throws Exception {
        MockWeblogger mocks = MockWeblogger.create();
        when(mocks.weblogger().getPropertiesManager().getProperty(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET))
                .thenReturn(new RuntimeConfigProperty(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET, KEY));
        UnsubscribeTokens bean = new UnsubscribeTokens(mocks.weblogger());

        assertEquals(tokens.issue("user-1", UnsubscribeTokens.REPLIES), bean.issue("user-1", UnsubscribeTokens.REPLIES));

        when(mocks.weblogger().getPropertiesManager().getProperty(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET))
                .thenReturn(null);
        assertThrows(IllegalStateException.class, () -> bean.issue("user-1", UnsubscribeTokens.REPLIES));

        when(mocks.weblogger().getPropertiesManager().getProperty(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET))
                .thenThrow(new WebloggerException("db down"));
        assertEquals(Optional.empty(),
                bean.verify(tokens.issue("user-1", UnsubscribeTokens.REPLIES), UnsubscribeTokens.REPLIES),
                "a key that cannot be read verifies nothing");
    }

    @Test
    void differentUsersGetDifferentTokens() {
        assertNotEquals(tokens.issue("user-1", UnsubscribeTokens.REPLIES),
                tokens.issue("user-2", UnsubscribeTokens.REPLIES));
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
