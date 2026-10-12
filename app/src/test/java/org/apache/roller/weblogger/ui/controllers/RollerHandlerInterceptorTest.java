/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.core.security.sso.RollerOidcUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The gate in front of every authoring and admin route.
 *
 * <p>This interceptor is what decides, for each request, who the caller is,
 * which weblog they are acting on, and whether they may. Everything downstream
 * trusts the two request attributes it sets: {@code BaseController} reads
 * {@code actionWeblog} and {@code authenticatedUser} without re-checking, so a
 * hole here is a hole in every controller at once. In a multi-user install it
 * is the whole of the tenant boundary.
 *
 * <p>It had one test, covering the guard clause that lets static-resource
 * handlers past. The reason given was that the logic "depends heavily on
 * the static locator which is ... impractical to mock in a lightweight
 * unit test" -- true when it was written, and no longer: {@link MockWeblogger}
 * installs a mocked business tier into that same static field. What follows is
 * the decision table the interceptor actually implements.
 *
 * <p>Every denial is asserted on twice: the handler must be stopped
 * ({@code false}) <em>and</em> sent somewhere. A check that returns false
 * without redirecting leaves a blank page; one that redirects but returns true
 * runs the controller anyway, which is the dangerous direction.
 */
class RollerHandlerInterceptorTest {

    private static final String LOGIN = "/roller-ui/login.rol";
    private static final String ACCESS_DENIED = "/roller-ui/access-denied.rol";
    private static final String CONTEXT = "/roller";

    private RollerHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;
    private WebloggerProvider provider;

    private User alice;
    private Weblog aliceBlog;

    @BeforeEach
    void setUp() throws Exception {
        request = new MockHttpServletRequest();
        request.setContextPath(CONTEXT);
        response = new MockHttpServletResponse();
        weblogger = MockWeblogger.attached();
        provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        interceptor = new RollerHandlerInterceptor(provider, weblogger.weblogger());

        alice = new User();
        alice.setUserName("alice");
        alice.setId("user-alice");

        aliceBlog = new Weblog();
        aliceBlog.setHandle("aliceblog");
        aliceBlog.setId("weblog-alice");

        when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(weblogger.weblogManager().getWeblogByHandle("aliceblog")).thenReturn(aliceBlog);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        weblogger.detach();
    }

    // ------------------------------------------------------------ pass-through

    @Test
    void nonHandlerMethodPassesThrough() throws Exception {
        assertTrue(interceptor.preHandle(request, response, new Object()),
                "handlers that are not Spring MVC methods -- static resources and the "
                        + "like -- must not be run through the security checks at all");
    }

    /**
     * Before bootstrap there is no business tier to ask, and the install and
     * setup controllers have to be reachable to create one. Denying here would
     * make a fresh installation impossible to complete.
     */
    @Test
    void nothingIsCheckedBeforeTheApplicationIsBootstrapped() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        assertTrue(interceptor.preHandle(request, response,
                        handlerFor(new SecuredController(true, List.of("admin"), false, List.of()))),
                "a controller demanding admin must still be reachable before bootstrap");
        assertNull(request.getAttribute("authenticatedUser"),
                "and nothing may be resolved, because there is nothing to resolve it with");
    }

    /**
     * The interceptor resolves users and weblogs through the facade it was
     * constructed with. A second, separately-mocked facade is handed in here
     * while a {@link MockWeblogger} is still installed in the static shim: if
     * the interceptor ever regressed to the locator it would find alice there
     * and this assertion would not hold.
     */
    @Test
    void usersAreResolvedThroughTheInjectedFacadeNotTheStaticShim() throws Exception {
        Weblogger other = mock(Weblogger.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(other.getUserManager().getUserByUserName("alice")).thenReturn(null);
        RollerHandlerInterceptor injected = new RollerHandlerInterceptor(provider, other);
        signedInAs("alice");

        assertFalse(injected.preHandle(request, response,
                        handlerFor(new SecuredController(true, List.of(), false, List.of()))),
                "the injected facade knows no alice, so the login requirement must deny");
        verify(other.getUserManager()).getUserByUserName("alice");
        verifyNoInteractions(weblogger.userManager());
    }

    /**
     * A controller that does not declare itself secured is not secured. This is
     * the default for public pages, and worth pinning: making it fail closed
     * would be a better design, but the codebase relies on the current one and
     * a silent change of default is how public pages disappear.
     */
    @Test
    void aControllerThatIsNotSecurityEnforcedIsNotChecked() throws Exception {
        assertTrue(interceptor.preHandle(request, response, handlerFor(new PlainController())),
                "an unsecured controller runs for anonymous callers");
        assertEquals(200, response.getStatus());
    }

    // ------------------------------------------------------- attribute setting

    @Test
    void theResolvedUserAndWeblogArePublishedAsRequestAttributes() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "aliceblog");

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertSame(alice, request.getAttribute("authenticatedUser"),
                "controllers read the caller off this attribute and never re-resolve it");
        assertSame(aliceBlog, request.getAttribute("actionWeblog"),
                "and the weblog they act on off this one");
    }

    @Test
    void anUnknownWeblogHandleLeavesTheActionWeblogUnset() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "nosuchblog");

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertNull(request.getAttribute("actionWeblog"),
                "an unresolvable handle must not leave a stale or guessed weblog behind");
    }

    /**
     * A blank {@code weblog=} is not a handle to look up. Treating it as one
     * would send an empty string to the manager on every request that carries
     * the parameter without a value.
     */
    @Test
    void aBlankWeblogParameterIsNotLookedUp() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "   ");

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertNull(request.getAttribute("actionWeblog"));
        verify(weblogger.weblogManager(), never()).getWeblogByHandle(any());
    }

    /**
     * A lookup that blows up must not take the request with it. The handle
     * comes from the query string, so a malformed one is a caller's mistake,
     * not a server error.
     */
    @Test
    void aWeblogLookupFailureIsSwallowedRatherThanThrown() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "explodes");
        when(weblogger.weblogManager().getWeblogByHandle("explodes"))
                .thenThrow(new WebloggerException("bad handle"));

        assertTrue(interceptor.preHandle(request, response, handlerFor(new PlainController())),
                "a failed weblog lookup must not become a 500");
        assertNull(request.getAttribute("actionWeblog"));
    }

    // ----------------------------------------------------- the decision table

    @Test
    void anAnonymousCallerIsSentToLoginWhenTheControllerRequiresAUser() throws Exception {
        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), false, List.of())));

        assertFalse(proceed, "the handler must not run");
        assertEquals(CONTEXT + LOGIN, response.getRedirectedUrl());
    }

    @Test
    void aUserWithoutTheRequiredGlobalPermissionIsRefused() throws Exception {
        signedInAs("alice");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any()))
                .thenReturn(false);

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of("admin"), false, List.of())));

        assertFalse(proceed, "the handler must not run");
        assertEquals(CONTEXT + ACCESS_DENIED, response.getRedirectedUrl(),
                "and the caller is refused, not bounced to login -- they are signed in");
    }

    @Test
    void aUserWithTheRequiredGlobalPermissionProceeds() throws Exception {
        signedInAs("alice");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any()))
                .thenReturn(true);

        assertTrue(interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of("admin"), false, List.of()))));
        assertEquals(200, response.getStatus());
    }

    /**
     * A controller that needs a weblog and was not given one is refused rather
     * than allowed through with a null. Every authoring controller dereferences
     * {@code actionWeblog}, so passing here would turn a missing form field
     * into a NullPointerException inside the handler.
     */
    @Test
    void aWeblogRequiringControllerIsRefusedWhenNoWeblogWasResolved() throws Exception {
        signedInAs("alice");

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), true, List.of("post"))));

        assertFalse(proceed);
        assertEquals(CONTEXT + ACCESS_DENIED, response.getRedirectedUrl());
    }

    /**
     * The tenant boundary: signed in, weblog resolved, but no permission on
     * <em>that</em> weblog.
     */
    @Test
    void aUserWithoutPermissionOnTheResolvedWeblogIsRefused() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "aliceblog");
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenReturn(false);

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), true, List.of("post"))));

        assertFalse(proceed, "a user with no rights on this weblog must not reach the handler");
        assertEquals(CONTEXT + ACCESS_DENIED, response.getRedirectedUrl());
    }

    @Test
    void aUserWithPermissionOnTheResolvedWeblogProceeds() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "aliceblog");
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenReturn(true);

        assertTrue(interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), true, List.of("post")))));
        assertEquals(200, response.getStatus());
    }

    /**
     * An empty permission list means "no particular permission", not "deny".
     * Several authoring controllers rely on this to require only that a weblog
     * was resolved.
     */
    @Test
    void anEmptyPermissionListDemandsNothingBeyondAResolvedWeblog() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "aliceblog");

        assertTrue(interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), true, List.of()))));
        verify(weblogger.userManager(), never()).checkPermission(any(WeblogPermission.class), any());
    }

    /**
     * Weblog permissions are only consulted when the controller asks for a
     * weblog. Checking them regardless would deny every global admin page that
     * happens to be called with a {@code weblog=} parameter.
     */
    @Test
    void weblogPermissionsAreNotConsultedWhenNoWeblogIsRequired() throws Exception {
        signedInAs("alice");
        request.setParameter("weblog", "aliceblog");

        assertTrue(interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), false, List.of("admin")))));
        verify(weblogger.userManager(), never()).checkPermission(any(WeblogPermission.class), any());
    }

    // ------------------------------------------------------ user resolution

    @Test
    void aStringPrincipalResolvesTheSameAsAUserDetailsPrincipal() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertSame(alice, request.getAttribute("authenticatedUser"),
                "some authentication tokens carry the username as a bare String");
    }

    /**
     * Spring Security's anonymous filter installs an authenticated token whose
     * principal is the literal "anonymousUser". Taking that at face value would
     * send the string to the user manager on every anonymous request, and treat
     * anyone who registered that username as signed in.
     */
    @Test
    void theAnonymousPrincipalIsNotTreatedAsASignedInUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), false, List.of())));

        assertFalse(proceed, "an anonymous token must not satisfy a user requirement");
        assertEquals(CONTEXT + LOGIN, response.getRedirectedUrl());
        verify(weblogger.userManager(), never()).getUserByUserName("anonymousUser");
    }

    @Test
    void anUnauthenticatedTokenResolvesToNobody() throws Exception {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("alice", "n/a");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertNull(request.getAttribute("authenticatedUser"));
    }

    /**
     * A reader signed in through the provider carries a {@link RollerOidcUser},
     * which is not a {@code UserDetails}; its name is the Roller username.
     * Resolving only UserDetails and String principals sent a reader promoted to
     * editor back to log in from every page that requires a user, menu.rol
     * included (found by SsoSignInIT, spec criterion 29).
     */
    @Test
    void aProviderSignedInPrincipalResolvesToItsRollerAccount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(providerToken(
                new RollerOidcUser(oidcUser("provider-subject-1"), "alice", EDITOR, false)));

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), false, List.of())));

        assertTrue(proceed, "a provider-signed-in account was sent to " + response.getRedirectedUrl());
        assertSame(alice, request.getAttribute("authenticatedUser"));
        verify(weblogger.userManager(), never()).getUserByUserName("provider-subject-1");
    }

    /**
     * Characterisation, passing before the fix above and pinned so it keeps
     * passing: only Roller's own principal is trusted for a username. A bare
     * OIDC user's name is the provider's subject, which is not a Roller
     * username and must never be looked up as one.
     */
    @Test
    void aBareOidcPrincipalResolvesToNobody() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(providerToken(oidcUser("alice")));

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertNull(request.getAttribute("authenticatedUser"));
        verify(weblogger.userManager(), never()).getUserByUserName("alice");
    }

    private static final List<SimpleGrantedAuthority> EDITOR = List.of(new SimpleGrantedAuthority("editor"));

    private static DefaultOidcUser oidcUser(String subject) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new DefaultOidcUser(EDITOR, idToken);
    }

    private static OAuth2AuthenticationToken providerToken(OidcUser principal) {
        return new OAuth2AuthenticationToken(principal, EDITOR, "oidc");
    }

    /**
     * A principal type nobody anticipated must resolve to nobody rather than to
     * whatever {@code toString()} happens to produce.
     */
    @Test
    void anUnrecognisedPrincipalTypeResolvesToNobody() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new Object(), "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));

        interceptor.preHandle(request, response, handlerFor(new PlainController()));

        assertNull(request.getAttribute("authenticatedUser"));
    }

    /**
     * A session naming a user the database no longer has -- deleted mid-session
     * -- must resolve to nobody, so the next secured request sends them to log
     * in again.
     */
    @Test
    void aUserLookupThatFailsResolvesToNobodyRatherThanPropagating() throws Exception {
        signedInAs("ghost");
        when(weblogger.userManager().getUserByUserName("ghost"))
                .thenThrow(new WebloggerException("gone"));

        boolean proceed = interceptor.preHandle(request, response,
                handlerFor(new SecuredController(true, List.of(), false, List.of())));

        assertFalse(proceed);
        assertEquals(CONTEXT + LOGIN, response.getRedirectedUrl());
    }

    // ------------------------------------------------------------- preparation

    @Test
    void aPreparableControllerIsPreparedOnceTheChecksHavePassed() throws Exception {
        signedInAs("alice");
        PreparableController controller = new PreparableController();

        assertTrue(interceptor.preHandle(request, response, handlerFor(controller)));
        assertEquals(1, controller.prepared, "myPrepare must run for a request that proceeds");
    }

    /**
     * Preparation happens after the gate, not before it. A refused request must
     * not run controller code, which is where lookups and defaults live.
     */
    @Test
    void aRefusedRequestIsNeverPrepared() throws Exception {
        RefusedPreparableController controller = new RefusedPreparableController();

        assertFalse(interceptor.preHandle(request, response, handlerFor(controller)));
        assertEquals(0, controller.prepared,
                "the controller must not be prepared for a caller who was turned away");
    }

    // ------------------------------------------------------------------ helpers

    private void signedInAs(String username) {
        UserDetails details = mock(UserDetails.class);
        when(details.getUsername()).thenReturn(username);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    /**
     * Wraps a controller as the {@code HandlerMethod} Spring MVC would hand the
     * interceptor. The method itself is never invoked -- only
     * {@code getBean()} is read -- so any public method will do.
     */
    private static HandlerMethod handlerFor(Object controller) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod("handle"));
    }

    /** A controller with no security declarations at all. */
    public static class PlainController {
        public String handle() {
            return "ok";
        }
    }

    /** A controller declaring exactly the requirements a test wants to exercise. */
    public static class SecuredController implements UISecurityEnforced {
        private final boolean userRequired;
        private final List<String> globalActions;
        private final boolean weblogRequired;
        private final List<String> weblogActions;

        SecuredController(boolean userRequired, List<String> globalActions,
                boolean weblogRequired, List<String> weblogActions) {
            this.userRequired = userRequired;
            this.globalActions = globalActions;
            this.weblogRequired = weblogRequired;
            this.weblogActions = weblogActions;
        }

        public String handle() {
            return "ok";
        }

        @Override
        public boolean isUserRequired() {
            return userRequired;
        }

        @Override
        public boolean isWeblogRequired() {
            return weblogRequired;
        }

        @Override
        public List<String> requiredGlobalPermissionActions() {
            return globalActions;
        }

        @Override
        public List<String> requiredWeblogPermissionActions() {
            return weblogActions;
        }
    }

    /** Passes the gate and records that it was prepared. */
    public static class PreparableController implements UISecurityEnforced, UIActionPreparable {
        int prepared;

        public String handle() {
            return "ok";
        }

        @Override
        public boolean isUserRequired() {
            return true;
        }

        @Override
        public boolean isWeblogRequired() {
            return false;
        }

        @Override
        public List<String> requiredGlobalPermissionActions() {
            return Collections.emptyList();
        }

        @Override
        public List<String> requiredWeblogPermissionActions() {
            return Collections.emptyList();
        }

        @Override
        public void myPrepare() {
            prepared++;
        }
    }

    /** Requires a user, so an anonymous caller never reaches preparation. */
    public static class RefusedPreparableController extends PreparableController {
    }
}
