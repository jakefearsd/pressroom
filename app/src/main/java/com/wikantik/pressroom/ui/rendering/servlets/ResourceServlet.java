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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.util.WeblogResourceRequest;

/**
 * Serves fixed-path files such as old-style uploads and theme resources, which
 * must exist at a fixed-path even if moved in media file folders.
 */
public class ResourceServlet extends WeblogRequestServlet<WeblogResourceRequest> {

    private static final long serialVersionUID = 1350679411381917714L;

    private static final Logger log = LoggerFactory.getLogger(ResourceServlet.class);

    private transient ServletContext context;

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public ResourceServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {

        super.init(config);

        log.info("Initializing ResourceServlet");

        this.context = config.getServletContext();
    }

    @Override
    WeblogResourceRequest parseRequest(HttpServletRequest request) throws WebloggerException {
        return new WeblogResourceRequest(weblogger, request);
    }

    /**
     * Handles requests for user uploaded resources.
     */
        @Override
    void serve(HttpServletRequest request, HttpServletResponse response,
            WeblogResourceRequest resourceRequest, Weblog weblog) throws ServletException, IOException {

        String path = resourceRequest.getResourcePath();
        log.debug("Resource requested [{}]", path);

        // first see if resource comes from weblog's shared theme, then
        // whether it is in the weblog's upload dir
        ServableResource found;
        try {
            WeblogTheme weblogTheme = weblogger.getThemeManager().getTheme(weblog);
            found = weblogTheme == null ? null : ServableResource.of(weblogTheme.getResource(path));
        } catch (WebloggerException | RuntimeException ex) {
            // hmmm, some kind of error getting theme. that's an error.
            fail(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (found == null) {
            found = ServableResource.uploaded(weblogger, weblog, path);
        }
        if (found == null) {
            // still not found? then we don't have it, 404.
            fail(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        found.serve(request, response, context, path);
    }

    private static void fail(HttpServletResponse response, int status) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
        }
        response.sendError(status);
    }

}
