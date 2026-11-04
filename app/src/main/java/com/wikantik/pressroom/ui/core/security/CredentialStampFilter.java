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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a signed-in session once its account's password has changed (code
 * review, 2026-11-03).
 *
 * <p>A session holds a stamp, a SHA-256 digest of the stored password hash it
 * first saw. Each signed-in request compares it with the account's current
 * hash; on a mismatch the session is invalidated and the security context
 * cleared, so the rest of the request runs as anonymous and authorization
 * decides what that gets (a protected page redirects to sign-in). The
 * remember-me cookie is already safe: its signature covers the password hash,
 * so a change voids it too.
 *
 * <p>Comparing against the stored hash, rather than tracking sessions, covers
 * every way a password changes: the profile page, the admin user editor, the
 * reset link, the API and the admin account tool, which writes the database
 * directly. The session that made the change calls {@link #restamp} so it
 * stays signed in.
 *
 * <p>This replaces what {@code PressroomLoginSessionManager.invalidate} was
 * thought to do: that only drops a cached {@code PressroomSession}, which the
 * next request rebuilds from the still-valid login.
 *
 * <p>It decides about the password only. A disabled or deleted account keeps
 * its session here, because the checks downstream already refuse it, and
 * some answer it specifically (the comments endpoints report "disabled").
 * A decision path, so it fails closed: a lookup that throws ends the session
 * rather than vouching for it.
 */
public class CredentialStampFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CredentialStampFilter.class);

    /** The session attribute holding the stamp. */
    static final String SESSION_ATTRIBUTE = CredentialStampFilter.class.getName() + ".stamp";

    /**
     * Whose stamp it is. Signing in changes the session's id but keeps its
     * attributes, so a sign-in as someone else without signing out first
     * finds the previous account's stamp: a new sign-in, not a stale one.
     */
    static final String OWNER_ATTRIBUTE = CredentialStampFilter.class.getName() + ".owner";

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider asked per request: a request can arrive before the tier is up */
    public CredentialStampFilter(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    /**
     * Stamps the request's session with the user's current password, so the
     * session that just changed it stays signed in. Creates no session.
     */
    public static void restamp(HttpServletRequest request, User user) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            stamp(session, user);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (auth != null && session != null && webloggerProvider.isBootstrapped()
                && !stillValid(auth.getName(), session)) {
            log.info("Ended a session of {}: its password changed", auth.getName());
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    /** Whether the session may stay signed in; stamps one that has no stamp yet. */
    private boolean stillValid(String userName, HttpSession session) {
        Weblogger weblogger = webloggerProvider.getWeblogger();
        try {
            // Any enabled state: a disabled account's password can change too.
            User user = weblogger.getUserManager().getUserByUserName(userName, null);
            if (user == null) {
                // Nothing to compare; whatever resolves the user next finds none.
                return true;
            }
            Object held = session.getAttribute(SESSION_ATTRIBUTE);
            if (held == null || !userName.equals(session.getAttribute(OWNER_ATTRIBUTE))) {
                stamp(session, user);
                return true;
            }
            return stampOf(user).equals(held);
        } catch (WebloggerException e) {
            log.warn("Could not check the session of {}; ending it", userName, e);
            return false;
        } finally {
            // Inside the security chain: a request answered here never reaches
            // PersistenceSessionFilter (see LastLoginRecorder).
            weblogger.release();
        }
    }

    private static void stamp(HttpSession session, User user) {
        session.setAttribute(SESSION_ATTRIBUTE, stampOf(user));
        session.setAttribute(OWNER_ATTRIBUTE, user.getUserName());
    }

    private static String stampOf(User user) {
        String hash = user.getPassword() == null ? "" : user.getPassword();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(hash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
