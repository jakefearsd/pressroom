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

package com.wikantik.pressroom.ui.core;

import java.io.Serializable;
import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;


/**
 * Pressroom session handles session startup and shutdown.
 *
 * <p>The per-session object stores only the user <em>name</em>; it holds no
 * reference to the business tier (it is {@link Serializable} and lives in the
 * servlet session). Whoever needs the {@link User} hands in the
 * {@link UserManager} to resolve with, and the static factory is handed the
 * {@link WebloggerProvider} so it can skip that lookup before the tier is up
 * (DI wave, plan Task 6b -- previously both reached the static locator).
 */
public class PressroomSession 
        implements HttpSessionListener, HttpSessionActivationListener, Serializable {
    
    private static final long serialVersionUID = 5890132909166913727L;

    // the id of the user represented by this session
    private String userName = null;
    
    private static final Logger log;
    
    public static final String PRESSROOM_SESSION = "com.wikantik.pressroom.session";

    static{
        WebloggerConfig.init(); // must be called before calls to logging APIs
        log = LoggerFactory.getLogger(PressroomSession.class);
    }
   
    /**
     * Get PressroomSession from request (and add user if not already present).
     *
     * @param provider answers whether the business tier is up; a principal is
     *                 only resolved to a user once it is
     */
    public static PressroomSession getPressroomSession(HttpServletRequest request,
            WebloggerProvider provider) {
        PressroomSession pressroomSession = null;
        HttpSession session = request.getSession(false);
        if (session != null) {
            // Before bootstrap there is no user manager to resolve against, so
            // every lookup below sees "no user" -- exactly the SSO case the
            // comment further down describes.
            UserManager users = provider.isBootstrapped()
                    ? provider.getWeblogger().getUserManager() : null;

            pressroomSession = (PressroomSession)session.getAttribute(PRESSROOM_SESSION);

            if (pressroomSession == null) {
                // Create new session if none exists
                pressroomSession = new PressroomSession();
                session.setAttribute(PRESSROOM_SESSION, pressroomSession);
            } else if (pressroomSession.getAuthenticatedUser(users) != null) {
                // Check if session is still valid in cache
                PressroomLoginSessionManager manager = PressroomLoginSessionManager.getInstance();
                String username = pressroomSession.getAuthenticatedUser(users).getUserName();
                if (manager.get(username) == null) {
                    pressroomSession = new PressroomSession();
                    session.setAttribute(PRESSROOM_SESSION, pressroomSession);
                }
            }
            Principal principal = request.getUserPrincipal();

            // If we've got a principal but no user object, then attempt to get
            // user object from user manager but *only* do this if we have been
            // bootstrapped because under an SSO scenario we may have a
            // principal even before we have been bootstrapped.
            if (pressroomSession.getAuthenticatedUser(users) == null && principal != null && users != null) {
                try {
                    User user = users.getUserByUserName(principal.getName());
                    
                    // only set authenticated user if user is enabled
                    if (user != null && user.getEnabled()) {
                        pressroomSession.setAuthenticatedUser(user);
                    }
                    
                } catch (WebloggerException e) {
                    log.error("ERROR: getting user object",e);
                }
            }
        }
        
        return pressroomSession;
    }

    /** Create session's Pressroom instance */
    @Override
    public void sessionCreated(HttpSessionEvent se) {
        PressroomSession pressroomSession = new PressroomSession();
        se.getSession().setAttribute(PRESSROOM_SESSION, pressroomSession);
    }


    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        clearSession(se);
    }

    /**
     * Purge session before passivation. Because Pressroom currently does not
     * support session recovery, failover, migration, or whatever you want
     * to call it when sessions are saved and then restored at some later
     * point in time.
     */
    @Override
    public void sessionWillPassivate(HttpSessionEvent se) {
        clearSession(se);
    }

    /**
     * Authenticated user associated with this session, resolved through the
     * given manager; {@code null} when there is no user, or no manager to
     * resolve with yet (the tier is not bootstrapped).
     */
    public User getAuthenticatedUser(UserManager mgr) {

        User authenticUser = null;
        if (userName != null && mgr != null) {
            try {
                authenticUser = mgr.getUserByUserName(userName);
            } catch (WebloggerException ex) {
                log.warn("Error looking up authenticated user {}", userName, ex);
            }
        }
        
        return authenticUser;
    }

    /**
     * Authenticated user associated with this session.
     */
    public void setAuthenticatedUser(User authenticatedUser) {
        this.userName = authenticatedUser.getUserName();
        PressroomLoginSessionManager sessionManager = PressroomLoginSessionManager.getInstance();
        sessionManager.register(authenticatedUser.getUserName(), this);
    }

    private void clearSession(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        try {
            session.removeAttribute(PRESSROOM_SESSION);
        } catch (Exception e) {
            // ignore purge exceptions
            log.debug("EXCEPTION PURGING session attributes", e);
        }
    }
}
