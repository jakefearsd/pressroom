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

package com.wikantik.pressroom.ui.controllers.ajax;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;
import java.util.Collections;
import com.wikantik.pressroom.pojos.GlobalPermission;

/**
 * Return list of users matching a startsWith strings. <br />
 * Accepts request params (none required):<br />
 * startsWith: string to be matched against username and email address<br />
 * enabled: true include only enabled users (default: no restriction<br />
 * offset: offset into results (for paging)<br />
 * length: number of users to return (max is 50)<br />
 * <br />
 * List format:<br />
 * username0, emailaddress0 <br/>
 * username1, emailaddress1 <br/>
 * username2, emailaddress2 <br/>
 * usernameN, emailaddressN <br/>
 *
 * Registered via {@code ServletRegistrationConfig} at
 * {@code /pressroom-ui/authoring/userdata/*}; {@code SecurityConfig}'s
 * authorization rule for {@code /pressroom-ui/authoring/**} requires
 * {@code hasAnyAuthority("admin", "editor")}.
 */
public class UserDataServlet extends HttpServlet {

    private static final long serialVersionUID = -7596671919118637768L;
    private static final int MAX_LENGTH = 50;

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public UserDataServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    @SuppressFBWarnings(
            value = {"DE_MIGHT_IGNORE", "XSS_SERVLET"},
            justification = "A malformed \"offset\"/\"length\" query parameter is routine, not "
                    + "an error -- parsing simply keeps the declared default and there is "
                    + "nothing to act on. The names are written as text/plain, which a browser does "
                    + "not render as markup (Spring Security also sends nosniff), and the one "
                    + "consumer is a scripted client that builds Option elements from them as text.")
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean admin = false;

        // This user check can be removed as we protected by spring (see above).
        WeblogRequest weblogRequest;
        try {
            weblogRequest = new WeblogRequest(weblogger, request);

            // Make sure we have the correct authority
            User user = weblogRequest.getUser();
            if (user == null) {
                // user not found
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            } else if (isGlobalAdmin(user)) {
                // admin
                admin = true;
            }

        } catch (Exception e) {
            // some kind of error just return
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String startsWith = request.getParameter("startsWith");
        Boolean enabledOnly = null;
        int offset = 0;
        int length = MAX_LENGTH;
        if ("true".equals(request.getParameter("enabled"))) {
            enabledOnly = Boolean.TRUE;
        }
        if ("false".equals(request.getParameter("enabled"))) {
            enabledOnly = Boolean.FALSE;
        }
        try {
            offset = Integer.parseInt(request.getParameter("offset"));
        } catch (Exception ignored) {
            // A malformed or absent "offset" is routine, not an error --
            // this is a plain GET parameter and the autocomplete lookup
            // simply starts from the top instead.
        }
        try {
            length = Integer.parseInt(request.getParameter("length"));
        } catch (Exception ignored) {
            // Same as "offset" above: a bad "length" just keeps the
            // MAX_LENGTH default.
        }

        try {
            UserManager umgr = weblogger.getUserManager();
            List<User> users = umgr.getUsersStartingWith(startsWith,
                    enabledOnly, offset, length);
            // Owner-typed names, written as text: never left for a browser to
            // sniff as markup, and in UTF-8 rather than the container's Latin-1.
            response.setContentType("text/plain; charset=UTF-8");
            for (User user : users) {
                response.getWriter().print(user.getUserName());
                if (admin) {
                    response.getWriter().print(",");
                    response.getWriter().println(user.getEmailAddress());
                } else{
                    response.getWriter().print(",");
                    response.getWriter().println(user.getScreenName());
                }
            }
            response.flushBuffer();
        } catch (WebloggerException e) {
            throw new ServletException(e.getMessage(), e);
        }
    }


    /**
     * Whether the user holds the global {@code admin} action; a check that
     * cannot be answered is a denial (was {@code User.hasGlobalPermission}).
     */
    private boolean isGlobalAdmin(User user) {
        try {
            return weblogger.getUserManager().checkPermission(
                    new GlobalPermission(Collections.singletonList("admin")), user);
        } catch (WebloggerException e) {
            return false;
        }
    }
}
