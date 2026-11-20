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

package com.wikantik.pressroom.ui.rendering.servlets;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;

/**
 * The opening every weblog-addressed GET shares (Template Method): parse the
 * request, answer 404 when it does not parse or names no weblog, and keep the
 * site space reachable only through a container forward. What differs is the
 * request type ({@link #parseRequest}, a Factory Method) and everything after
 * the opening ({@link #serve}).
 *
 * @param <R> the parsed request this servlet works from
 */
abstract class WeblogRequestServlet<R extends WeblogRequest> extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(WeblogRequestServlet.class);

    /** Parses the incoming request; any exception answers 404. */
    abstract R parseRequest(HttpServletRequest request) throws WebloggerException;

    /** Everything after the opening, for a request that named a servable weblog. */
    abstract void serve(HttpServletRequest request, HttpServletResponse response,
            R parsed, Weblog weblog) throws ServletException, IOException;

    /**
     * Whether the site space may be reached only through a container forward
     * (WeblogRequestMapper's /<slug>, index.jsp's /), never by its internal
     * handle at this servlet's own path. A preview is gated by its editors
     * check instead.
     */
    boolean siteSpaceOnlyByForward() {
        return true;
    }

    @Override
    protected final void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        R parsed;
        Weblog weblog;
        try {
            parsed = parseRequest(request);
            weblog = parsed.getWeblog();
        } catch (WebloggerException | RuntimeException e) {
            log.debug("error creating weblog request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        if (weblog == null) {
            log.debug("unable to lookup weblog: {}", parsed.getWeblogHandle());
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        if (siteSpaceOnlyByForward() && weblog.isSiteSpace()
                && request.getDispatcherType() != DispatcherType.FORWARD) {
            log.debug("site space reached directly: {}", parsed.getWeblogHandle());
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        serve(request, response, parsed, weblog);
    }
}
