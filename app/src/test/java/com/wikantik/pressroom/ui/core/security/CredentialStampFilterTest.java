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

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing a password ends the account's other sessions (code review,
 * 2026-11-03). Before this, a password change called
 * {@code PressroomLoginSessionManager.invalidate}, which only dropped a cached
 * object that the next request rebuilt from the still-valid Spring Security
 * login, so a session opened with the old password stayed signed in.
 *
 * <p>The session holds a stamp of the stored password hash it signed in
 * under; a request whose account now has another hash, or no enabled account
 * at all, ends the session and carries on as anonymous.
 */
class CredentialStampFilterTest {

    private MockWeblogger tier;
    private WebloggerProvider provider;
    private MockHttpServletRequest request;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        tier = MockWeblogger.create();
        provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        session = new MockHttpSession();
        request = new MockHttpServletRequest("GET", "/pressroom-ui/menu.rol");
        request.setSession(session);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static User account(String userName, String passwordHash) {
        User user = new User();
        user.setUserName(userName);
        user.setPassword(passwordHash);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    private static void signedInAs(String userName) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                userName, null, List.of(new SimpleGrantedAuthority("editor"))));
    }

    private MockFilterChain run() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new CredentialStampFilter(provider).doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void aSessionsFirstRequestStampsItWithThePasswordItSignedInUnder() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");

        MockFilterChain chain = run();

        assertNotNull(chain.getRequest(), "the request carries on");
        assertFalse(session.isInvalid());
        assertNotNull(session.getAttribute(CredentialStampFilter.SESSION_ATTRIBUTE));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aSessionWhosePasswordIsUnchangedStaysSignedIn() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");
        run();

        run();

        assertFalse(session.isInvalid());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aSessionOpenedUnderTheOldPasswordIsEndedOnItsNextRequest() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");
        run();
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-2"));

        MockFilterChain chain = run();

        assertTrue(session.isInvalid(), "the session signed in under the old password is ended");
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "the rest of this request runs as anonymous");
        assertNotNull(chain.getRequest(), "and does run: authorization decides what anonymous gets");
    }

    /**
     * Signing in keeps the session's attributes (only its id changes), so a
     * sign-in as someone else in a session still signed in as alice inherits
     * alice's stamp. That is a new sign-in, stamped afresh, not a stale one.
     */
    @Test
    void signingInAsSomeoneElseInTheSameSessionStampsTheNewAccount() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        when(tier.userManager().getUserByUserName("bob", null)).thenReturn(account("bob", "hash-b"));
        signedInAs("alice");
        run();
        signedInAs("bob");

        run();
        run();

        assertFalse(session.isInvalid(), "bob's own sign-in is not ended for carrying alice's stamp");
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void restampingKeepsTheSessionThatChangedThePasswordSignedIn() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");
        run();
        User changed = account("alice", "hash-2");
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(changed);

        CredentialStampFilter.restamp(request, changed);
        run();

        assertFalse(session.isInvalid());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void restampingWithoutASessionCreatesNone() {
        MockHttpServletRequest sessionless = new MockHttpServletRequest();

        CredentialStampFilter.restamp(sessionless, account("alice", "hash-2"));

        assertNull(sessionless.getSession(false));
    }

    /**
     * A disabled account keeps its session here: the checks downstream
     * already refuse it, and some answer it specifically (the comments
     * endpoints report "disabled"). This filter is about the password.
     */
    @Test
    void aDisabledAccountsSessionIsLeftToTheChecksThatRefuseIt() throws Exception {
        User disabled = account("alice", "hash-1");
        disabled.setEnabled(Boolean.FALSE);
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(disabled);
        signedInAs("alice");

        run();
        run();

        assertFalse(session.isInvalid());
    }

    @Test
    void aDisabledAccountWhosePasswordChangedIsStillEnded() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");
        run();
        User changed = account("alice", "hash-2");
        changed.setEnabled(Boolean.FALSE);
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(changed);

        run();

        assertTrue(session.isInvalid());
    }

    /** No account, nothing to compare: whatever resolves the user next finds none. */
    @Test
    void anAccountThatNoLongerExistsIsLeftToTheChecksThatRefuseIt() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(null);
        signedInAs("alice");

        run();

        assertFalse(session.isInvalid());
    }

    /** A check that could not run is not a check that passed. */
    @Test
    void aLookupThatFailsEndsTheSessionRatherThanVouchingForIt() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenThrow(new WebloggerException("db down"));
        signedInAs("alice");

        MockFilterChain chain = run();

        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @Test
    void theLookupsPersistenceSessionIsReleased() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");

        run();

        verify(tier.weblogger()).release();
    }

    @Test
    void anAnonymousRequestIsNotLookedUp() throws Exception {
        MockFilterChain chain = run();

        assertNotNull(chain.getRequest());
        verify(provider, never()).getWeblogger();
        assertFalse(session.isInvalid());
    }

    @Test
    void aRequestWithoutASessionIsNotLookedUp() throws Exception {
        request = new MockHttpServletRequest("GET", "/pressroom-ui/menu.rol");
        signedInAs("alice");

        run();

        verify(provider, never()).getWeblogger();
        assertNull(request.getSession(false), "no session is created to stamp");
    }

    @Test
    void beforeBootstrapNothingIsLookedUp() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        signedInAs("alice");

        run();

        verify(provider, never()).getWeblogger();
        assertFalse(session.isInvalid());
    }

    /** The stamp is a digest of the hash: the session never holds the password hash itself. */
    @Test
    void theStampIsNotThePasswordHash() throws Exception {
        when(tier.userManager().getUserByUserName("alice", null)).thenReturn(account("alice", "hash-1"));
        signedInAs("alice");

        run();

        Object stamp = session.getAttribute(CredentialStampFilter.SESSION_ATTRIBUTE);
        assertFalse(String.valueOf(stamp).contains("hash-1"));
        assertEquals(64, String.valueOf(stamp).length(), "a SHA-256 hex digest");
    }
}
