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
 * limitations under the License.
 */
package com.wikantik.pressroom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Pins that fixture users carry a real password hash.
 *
 * <p>{@code setupUser} used to call the raw {@code setPassword("password")} --
 * not {@code resetPassword} -- so it bypassed the encoder entirely and wrote an
 * unprefixed plaintext string at ~106 call sites. Those users could never
 * authenticate (a {@code DelegatingPasswordEncoder} needs an {@code {id}}
 * prefix), which is why no unit test had ever exercised a real login.
 */
class TestUtilsPasswordTest {

    @Test
    void theFixtureHashIsARealBcryptHashOfTheFixturePassword() {
        assertTrue(TestUtils.TEST_PASSWORD_HASH.startsWith("{bcrypt}$2"),
                "fixture hash must be a bcrypt hash, was: " + TestUtils.TEST_PASSWORD_HASH);
        assertTrue(new BCryptPasswordEncoder().matches(
                        TestUtils.TEST_PASSWORD,
                        TestUtils.TEST_PASSWORD_HASH.substring("{bcrypt}".length())),
                "TEST_PASSWORD_HASH does not verify against TEST_PASSWORD");
    }

    @Test
    void aFixtureUserStoresThatHashRatherThanPlaintext() throws Exception {
        TestUtils.setupWeblogger();
        User user = TestUtils.setupUser("pwfixture");
        try {
            assertTrue(user.getPassword().startsWith("{bcrypt}$2"),
                    "setupUser stored a non-bcrypt password: " + user.getPassword());
        } finally {
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }
}
