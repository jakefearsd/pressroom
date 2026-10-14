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

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.User;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;
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

    private static User agentNamed(String userName) {
        User agent = new User();
        agent.setUserName(userName);
        agent.setKind(User.Kind.AGENT);
        return agent;
    }

    /** Spec 2026-10-13, section 2: an agent is not an account a password can reach. */
    @Test
    void anAgentIsInvisibleToPasswordSignIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));

            UsernameNotFoundException thrown = assertThrows(UsernameNotFoundException.class,
                    () -> service.loadUserByUsername("bot"));

            assertEquals("ERROR agent account: bot", thrown.getMessage());
        } finally {
            weblogger.detach();
        }
    }

    /**
     * Criterion 4 and review focus 3. Form login and HTTP Basic on /api/**
     * share this provider over this service, so this is the one place both
     * refuse an agent -- with the same generic failure as a wrong password.
     */
    @Test
    void signingInAsAnAgentFailsWithTheSameMessageAsAWrongPassword() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));
            User alice = new User();
            alice.setUserName("alice");
            alice.setPassword(TestUtils.TEST_PASSWORD_HASH);
            when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
            when(weblogger.userManager().getRoles(alice)).thenReturn(List.of("editor"));
            DaoAuthenticationProvider dao = new DaoAuthenticationProvider(service);
            dao.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                    Map.of("bcrypt", new BCryptPasswordEncoder())));

            BadCredentialsException agentRefused = assertThrows(BadCredentialsException.class,
                    () -> dao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("bot", "anything")));
            BadCredentialsException wrongPassword = assertThrows(BadCredentialsException.class,
                    () -> dao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wrong")));

            assertEquals(wrongPassword.getMessage(), agentRefused.getMessage());
        } finally {
            weblogger.detach();
        }
    }

    /**
     * Review focus 3: a remember-me cookie names its account and is resolved
     * through this same service before its signature is even checked, so an
     * agent named in a cookie signs no one in.
     */
    @Test
    void aRememberMeCookieNamingAnAgentSignsNoOneIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));
            RollerRememberMeServices rememberMe;
            try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
                config.when(() -> WebloggerConfig.getProperty("rememberme.key", "springRocks"))
                        .thenReturn("a-real-secret");
                config.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(true);
                rememberMe = new RollerRememberMeServices(service);
            }
            long expiry = System.currentTimeMillis() + 3_600_000L;
            String value = Base64.getEncoder().encodeToString(
                    ("bot:" + expiry + ":" + "0".repeat(128)).getBytes(StandardCharsets.UTF_8));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("remember-me", value));

            assertNull(rememberMe.autoLogin(request, new MockHttpServletResponse()));
            verify(weblogger.userManager()).getUserByUserName("bot");
        } finally {
            weblogger.detach();
        }
    }
}
