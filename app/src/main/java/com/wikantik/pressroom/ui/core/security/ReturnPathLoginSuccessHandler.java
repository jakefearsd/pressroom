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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.wikantik.pressroom.ui.core.security.sso.ReturnPaths;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * Where a password sign-in lands. The login form posts {@code return}, the
 * page whose Sign in link the visitor followed; a path under the context
 * (checked by {@link ReturnPaths#sanitize}, the rule the reader sign-in uses)
 * is where they go back to. Otherwise the old order stands: the request that
 * sent them to sign in, else the React admin's home.
 */
public class ReturnPathLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** The landing when there is neither a return path nor a saved request: the React admin's home. */
    static final String DEFAULT_TARGET = "/pressroom-ui/app/";

    /** The login form's field for the page to go back to. */
    static final String RETURN_PARAMETER = "return";

    private final RequestCache requestCache = new HttpSessionRequestCache();

    public ReturnPathLoginSuccessHandler() {
        super();
        setDefaultTargetUrl(DEFAULT_TARGET);
        setRequestCache(requestCache);
    }

    @Override
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "The target is a return path ReturnPaths.sanitize has confined to a path under the "
                    + "context: no scheme, no host, no // or /\\ prefix, no control character.")
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws ServletException, IOException {
        String requested = request.getParameter(RETURN_PARAMETER);
        String target = ReturnPaths.sanitize(requested, request.getContextPath());
        // sanitize answers the context root for anything it refuses, so only an
        // unchanged value is a path the visitor actually came from.
        if (target.equals(requested)) {
            requestCache.removeRequest(request, response);
            clearAuthenticationAttributes(request);
            response.sendRedirect(target);
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
