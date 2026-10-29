/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wikantik.pressroom.ui.core.security;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-13, section 2; criterion 20: every interactive sign-in stamps last_login. */
class LastLoginRecorderTest {

    private static InteractiveAuthenticationSuccessEvent signInOf(String userName) {
        return new InteractiveAuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(userName, null, List.of()),
                LastLoginRecorderTest.class);
    }

    private static WebloggerProvider bootstrapped(MockWeblogger tier) {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        return provider;
    }

    @Test
    void aSignInStampsTheAccountCommitsAndReleases() throws Exception {
        MockWeblogger tier = MockWeblogger.create();

        new LastLoginRecorder(bootstrapped(tier)).onApplicationEvent(signInOf("alice"));

        verify(tier.userManager()).recordLogin("alice");
        assertEquals(1, tier.flushCount());
        verify(tier.weblogger()).release();
    }

    @Test
    void beforeBootstrapNothingIsTouched() {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(false);

        new LastLoginRecorder(provider).onApplicationEvent(signInOf("alice"));

        verify(provider, never()).getWeblogger();
    }

    /** A display value: a store that cannot record it never fails the sign-in. */
    @Test
    void aStoreFailureIsLoggedAndTheSignInProceeds() throws Exception {
        MockWeblogger tier = MockWeblogger.create();
        doThrow(new WebloggerException("db down")).when(tier.userManager()).recordLogin("alice");

        assertDoesNotThrow(() -> new LastLoginRecorder(bootstrapped(tier)).onApplicationEvent(signInOf("alice")));

        assertEquals(0, tier.flushCount());
        verify(tier.weblogger()).release();
    }
}
