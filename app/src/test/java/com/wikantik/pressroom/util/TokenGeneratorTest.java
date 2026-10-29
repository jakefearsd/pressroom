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
package com.wikantik.pressroom.util;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenGenerator} produces durable secrets such as password-reset
 * tokens, so these tests pin every observable property of the format: exact
 * length (kills mutations of the byte count and of
 * {@code withoutPadding()}), exact alphabet (kills a swap to the standard,
 * non-URL-safe encoder), decoded size, and uniqueness (kills a defanged
 * random source).
 */
class TokenGeneratorTest {

    @Test
    void tokenIsExactly43Characters() {
        // 32 bytes -> ceil(32 * 4 / 3) = 43 unpadded Base64 characters
        assertEquals(43, TokenGenerator.newToken().length());
    }

    @Test
    void tokenUsesOnlyTheUrlSafeAlphabet() {
        for (int i = 0; i < 50; i++) {
            String token = TokenGenerator.newToken();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"),
                    "Token must need no URL escaping: " + token);
        }
    }

    @Test
    void tokenCarriesNoPadding() {
        for (int i = 0; i < 50; i++) {
            String token = TokenGenerator.newToken();
            assertTrue(token.indexOf('=') < 0, "Unpadded encoding must emit no '=': " + token);
        }
    }

    @Test
    void tokenDecodesToExactly32RandomLookingBytes() {
        byte[] decoded = Base64.getUrlDecoder().decode(TokenGenerator.newToken());
        assertEquals(32, decoded.length, "Token must carry 256 bits of entropy");
    }

    @Test
    void consecutiveTokensDiffer() {
        assertNotEquals(TokenGenerator.newToken(), TokenGenerator.newToken());
    }

    @Test
    void thousandTokensAreAllDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(TokenGenerator.newToken()),
                    "Duplicate token after " + i + " draws -- the random source is broken");
        }
    }

    @Test
    void tokensExerciseTheWholeAlphabet() {
        // 200 tokens = 8600 character draws; the chance of any one of the 64
        // Base64 symbols never appearing is about e^-134. A "random" source
        // that only ever emits a constant (the classic mutated
        // SecureRandom.nextBytes no-op leaves all-zero bytes -> "AAAA...")
        // cannot pass this.
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            for (char c : TokenGenerator.newToken().toCharArray()) {
                seen.add(c);
            }
        }
        assertEquals(64, seen.size(),
                "All 64 URL-safe Base64 symbols should appear across 8600 draws");
    }
}
