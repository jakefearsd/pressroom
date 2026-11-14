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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogPreviewResourceRequest;

/**
 * Special previewing servlet which serves files uploaded by users as well as
 * static resources in shared themes. This servlet differs from the normal
 * ResourceServlet because it can accept urls parameters which affect how it
 * behaves which are used for previewing.
 */
public class PreviewResourceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(PreviewResourceServlet.class);

    private transient ServletContext context;

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public PreviewResourceServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {

        super.init(config);

        log.info("Initializing PreviewResourceServlet");

        this.context = config.getServletContext();
    }

    /**
     * Handles requests for user uploaded resources.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Weblog weblog;

        WeblogPreviewResourceRequest resourceRequest;
        try {
            // parse the incoming request and extract the relevant data
            resourceRequest = new WeblogPreviewResourceRequest(weblogger, request);

            weblog = resourceRequest.getWeblog();

        } catch (WebloggerException | RuntimeException e) {
            // invalid resource request
            log.debug("error creating weblog resource request", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (weblog == null) {
            log.debug("unable to lookup weblog: {}", resourceRequest.getWeblogHandle());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String path = resourceRequest.getResourcePath();
        log.debug("Resource requested [{}]", path);

        // first, see if we have a preview theme to operate from
        ServableResource found = null;
        if (!StringUtils.isEmpty(resourceRequest.getThemeName())) {
            found = ServableResource.of(resourceRequest.getTheme().getResource(path));
        }

        // second, see if resource comes from weblog's configured shared theme
        if (found == null) {
            try {
                WeblogTheme weblogTheme = weblogger.getThemeManager().getTheme(weblog);
                found = weblogTheme == null ? null : ServableResource.of(weblogTheme.getResource(path));
            } catch (WebloggerException | RuntimeException ex) {
                // hmmm, some kind of error getting theme. that's an error.
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }

        // if not from theme then see if resource is in weblog's upload dir
        if (found == null) {
            found = ServableResource.uploaded(weblogger, weblog, path);
        }
        if (found == null) {
            // still not found? then we don't have it, 404.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        found.serve(request, response, context, path);
    }

}
