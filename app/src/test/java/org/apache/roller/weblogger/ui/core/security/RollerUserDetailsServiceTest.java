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
package org.apache.roller.weblogger.ui.core.security;

import org.apache.roller.weblogger.business.MockWeblogger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.business.Weblogger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RollerUserDetailsService}.
 *
 * <p>Before the business tier is bootstrapped, {@code
 * TestUtils.weblogger()} throws {@code IllegalStateException} --
 * Spring Security still calls into this service that early (first-time
 * setup), so the failure is converted to a "soft" {@code
 * UsernameNotFoundException} that lets the setup flow proceed, rather than
 * a hard 500. The cause must still be attached, so it stays diagnosable.
 */
class RollerUserDetailsServiceTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final RollerUserDetailsService service = new RollerUserDetailsService(provider);

    @Test
    void anUnbootstrappedProviderProducesASoftFailureCarryingItsCause() {
        doThrow(new IllegalStateException("not bootstrapped")).when(provider).getWeblogger();

        UsernameNotFoundException thrown = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("anyone"));

        assertNotNull(thrown.getCause(),
                "the IllegalStateException from the provider must survive as the cause");
    }

    /**
     * Once the tier is up the lookup goes through the facade the provider
     * hands out -- not through any static. The provider here returns a
     * {@link MockWeblogger} facade and nothing else is installed, so a
     * regression to the static locator would throw, not pass.
     */
    @Test
    void aBootstrappedProviderIsAskedForTheUserManager() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            Weblogger facade = weblogger.weblogger();
            when(provider.getWeblogger()).thenReturn(facade);
            org.apache.roller.weblogger.pojos.User alice = new org.apache.roller.weblogger.pojos.User();
            alice.setUserName("alice");
            alice.setPassword("{bcrypt}x");
            when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
            when(weblogger.userManager().getRoles(alice)).thenReturn(java.util.List.of("editor"));

            org.springframework.security.core.userdetails.UserDetails details =
                    service.loadUserByUsername("alice");

            assertEquals("alice", details.getUsername());
            verify(weblogger.userManager()).getUserByUserName("alice");
        } finally {
            weblogger.detach();
        }
    }

    @Test
    void aBoundAccountIsInvisibleToPasswordSignIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            org.apache.roller.weblogger.pojos.User reader = new org.apache.roller.weblogger.pojos.User();
            reader.setUserName("reader-x");
            reader.setPassword(org.apache.roller.weblogger.pojos.User.SSO_PASSWORD_MARKER);
            reader.setSsoIssuer("https://accounts.example");
            reader.setSsoSubject("sub-1");
            when(weblogger.userManager().getUserByUserName("reader-x")).thenReturn(reader);

            UsernameNotFoundException thrown = assertThrows(UsernameNotFoundException.class,
                    () -> service.loadUserByUsername("reader-x"));

            assertEquals("ERROR no password user: reader-x", thrown.getMessage());
        } finally {
            weblogger.detach();
        }
    }
}
