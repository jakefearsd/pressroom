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

package com.wikantik.pressroom.ui.rendering.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;


/**
 * An abstract class representing any request made to Roller that has been
 * parsed in order to extract relevant pieces of information from the url.
 *
 * NOTE: It is extremely important to mention that this class and all of its
 * subclasses are meant to be extremely light weight.  Meaning they should
 * avoid any time consuming operations at all costs, especially operations
 * which require a trip to the db.  Those operations should be used very, very
 * sparingly and should only be triggered when it's guaranteed that they are
 * needed.
 *
 * <p>Those sparing lookups go through the {@link Weblogger} the request object
 * was constructed with, never through a static locator. The no-arg
 * constructors build a <em>carrier</em> with no facade at all: every heavy
 * value on such an object has to arrive through its setters, and a lazy
 * lookup on one throws {@link IllegalStateException} rather than reaching for
 * a locator. Production code constructs every request object with the
 * facade; the carriers exist for hand-built fixtures.
 */
public abstract class ParsedRequest {

    private static final Logger log = LoggerFactory.getLogger(ParsedRequest.class);

    /** The business tier, or null for a hand-built carrier (see class javadoc). */
    private final Weblogger weblogger;

    // lightweight attributes
    private String authenticUser = null;

    // heavyweight attributes
    private User user = null;


    /** A carrier with no facade; see the class javadoc. */
    ParsedRequest() {
        this.weblogger = null;
    }


    /** A request object that can look things up but parsed nothing. */
    ParsedRequest(Weblogger weblogger) {
        this.weblogger = weblogger;
    }


    /**
     * Parse the given http request and extract any information we can.
     *
     * This abstract version of the constructor gathers info likely to be
     * relevant to all requests to Roller.
     */
    public ParsedRequest(Weblogger weblogger, HttpServletRequest request) throws InvalidRequestException {

        this.weblogger = weblogger;

        // login status
        java.security.Principal prince = request.getUserPrincipal();
        if(prince != null) {
            this.authenticUser = prince.getName();
        }
    }
    
    
    public String getAuthenticUser() {
        return this.authenticUser;
    }
    
    
    public void setAuthenticUser(String authenticUser) {
        this.authenticUser = authenticUser;
    }
    
    
    /**
     * The business tier this request object resolves its heavyweight values
     * through.
     *
     * @throws IllegalStateException on a carrier built without one
     */
    protected final Weblogger weblogger() {
        if (weblogger == null) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + " was built without a Weblogger; a hand-built carrier must have its "
                    + "heavyweight values injected through the setters, not looked up");
        }
        return weblogger;
    }


    public User getUser() {

        if(user == null && authenticUser != null) {
            try {
                UserManager umgr = weblogger().getUserManager();
                user = umgr.getUserByUserName(authenticUser);
            } catch (WebloggerException ex) {
                log.error("Error looking up user {}", authenticUser, ex);
            }
        }
        
        return user;
    }
    
    
    public void setUser(User u) {
        this.user = u;
    }
    
    
    public boolean isLoggedIn() {
        return this.authenticUser != null;
    }

}
