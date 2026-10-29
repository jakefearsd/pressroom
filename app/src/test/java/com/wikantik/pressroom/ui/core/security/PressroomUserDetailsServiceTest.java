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
package com.wikantik.pressroom.ui.core.security;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
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
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.Weblogger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link PressroomUserDetailsService}.
 *
 * <p>Before the business tier is bootstrapped, {@code
 * TestUtils.weblogger()} throws {@code IllegalStateException} --
 * Spring Security still calls into this service that early (first-time
 * setup), so the failure is converted to a "soft" {@code
 * UsernameNotFoundException} that lets the setup flow proceed, rather than
 * a hard 500. The cause must still be attached, so it stays diagnosable.
 */
class PressroomUserDetailsServiceTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final PressroomUserDetailsService service = new PressroomUserDetailsService(provider);

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
            com.wikantik.pressroom.pojos.User alice = new com.wikantik.pressroom.pojos.User();
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
            com.wikantik.pressroom.pojos.User reader = new com.wikantik.pressroom.pojos.User();
            reader.setUserName("reader-x");
            reader.setPassword(com.wikantik.pressroom.pojos.User.SSO_PASSWORD_MARKER);
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

    /** An agent row that somehow holds a password hash (corrupt row, or a future path that sets one). */
    private static User agentWithPassword(String userName) {
        User agent = agentNamed(userName);
        agent.setPassword(TestUtils.TEST_PASSWORD_HASH);
        return agent;
    }

    private static DaoAuthenticationProvider passwordProvider(PressroomUserDetailsService service) {
        DaoAuthenticationProvider dao = new DaoAuthenticationProvider(service);
        dao.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder())));
        return dao;
    }

    /**
     * Form login and HTTP Basic on /api/** share this provider over this
     * service. An agent whose row holds a real password hash must not
     * authenticate even with that correct password: the kind guard in
     * loadUserByUsername is the only thing standing in the way.
     */
    @Test
    void anAgentHoldingAPasswordCannotSignInWithIt() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentWithPassword("bot"));
            DaoAuthenticationProvider dao = passwordProvider(service);

            assertThrows(BadCredentialsException.class,
                    () -> dao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                            "bot", TestUtils.TEST_PASSWORD)));
        } finally {
            weblogger.detach();
        }
    }

    /**
     * The signature Spring Security's remember-me verifies: SHA-256 over
     * {@code username:expiry:password:key}, hex-encoded, as the cookie's
     * {@code SHA256} scheme carries it.
     */
    private static String springSha256Signature(String userName, long expiry, String password, String key)
            throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((userName + ":" + expiry + ":" + password + ":" + key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /**
     * A remember-me cookie names its account and is resolved through this
     * same service. The cookie carries a correctly signed token for the
     * agent's stored password, so only the kind guard stands between it and
     * a signed-in session.
     */
    @Test
    void aRememberMeCookieNamingAnAgentSignsNoOneIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentWithPassword("bot"));
            PressroomRememberMeServices rememberMe;
            try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
                config.when(() -> WebloggerConfig.getProperty("rememberme.key", "springRocks"))
                        .thenReturn("a-real-secret");
                config.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(true);
                rememberMe = new PressroomRememberMeServices(service);
            }
            long expiry = System.currentTimeMillis() + 3_600_000L;
            String value = Base64.getEncoder().encodeToString(
                    ("bot:" + expiry + ":SHA256:" + springSha256Signature("bot", expiry,
                            TestUtils.TEST_PASSWORD_HASH, "a-real-secret")).getBytes(StandardCharsets.UTF_8));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("remember-me", value));

            assertNull(rememberMe.autoLogin(request, new MockHttpServletResponse()));
        } finally {
            weblogger.detach();
        }
    }
}
