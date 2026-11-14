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
import java.io.InputStream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;
import com.wikantik.pressroom.ui.rendering.util.WeblogResourceRequest;

/**
 * Serves fixed-path files such as old-style uploads and theme resources, which
 * must exist at a fixed-path even if moved in media file folders.
 */
public class ResourceServlet extends HttpServlet {

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

    /**
     * Handles requests for user uploaded resources.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Weblog weblog;
        WeblogResourceRequest resourceRequest;
        try {
            // parse the incoming request and extract the relevant data
            resourceRequest = new WeblogResourceRequest(weblogger, request);

            weblog = resourceRequest.getWeblog();

        } catch (WebloggerException | RuntimeException e) {
            // invalid resource request
            log.debug("error creating weblog resource request", e);
            fail(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        // The site space's files are reached only through the mapper's
        // forward of /site-space/<context>/..., never at this servlet's
        // own path.
        if (weblog == null || (weblog.isSiteSpace() && request.getDispatcherType() != DispatcherType.FORWARD)) {
            log.debug("no weblog to serve resources for {}", resourceRequest.getWeblogHandle());
            fail(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String path = resourceRequest.getResourcePath();
        log.debug("Resource requested [{}]", path);

        // first see if resource comes from weblog's shared theme, then
        // whether it is in the weblog's upload dir
        Found found;
        try {
            found = themeResource(weblog, path);
        } catch (WebloggerException | RuntimeException ex) {
            // hmmm, some kind of error getting theme. that's an error.
            fail(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (found == null) {
            found = uploadedResource(weblog, path);
        }
        if (found == null) {
            // still not found? then we don't have it, 404.
            fail(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Respond with 304 Not Modified if it is not modified.
        if (ModDateHeaderUtil.respondIfNotModified(request, response, found.lastModified())) {
            return;
        }
        ModDateHeaderUtil.setLastModifiedHeader(response, found.lastModified());

        // set the content type based on whatever is in our web.xml mime defs
        response.setContentType(this.context.getMimeType(path));

        // streamResource closes the stream
        RenderingServletUtils.streamResource(found.stream(), response, path);
    }

    /** A resource's bytes, still to be closed, and when it last changed. */
    private record Found(long lastModified, InputStream stream) {
    }

    /** The resource from the weblog's theme, or null when the theme has none at that path. */
    private Found themeResource(Weblog weblog, String path) throws WebloggerException {
        WeblogTheme weblogTheme = weblogger.getThemeManager().getTheme(weblog);
        ThemeResource resource = weblogTheme == null ? null : weblogTheme.getResource(path);
        if (resource == null) {
            return null;
        }
        long lastModified = resource.getLastModified();
        InputStream stream = resource.getInputStream();
        return stream == null ? null : new Found(lastModified, stream);
    }

    /**
     * The file uploaded at that path, or null. A private directory's files
     * are not served publicly by any route (see MediaResourceServlet): one
     * answers as a file that does not exist, so nothing here reveals that
     * it does. A lookup that fails is a miss too.
     */
    private Found uploadedResource(Weblog weblog, String path) {
        try {
            MediaFileManager mmgr = weblogger.getMediaFileManager();
            MediaFile mf = mmgr.getMediaFileByOriginalPath(weblog, path);
            if (mf == null || (mf.getDirectory() != null && mf.getDirectory().isPrivate())) {
                return null;
            }
            long lastModified = mf.getLastModified();
            InputStream stream = mf.getInputStream();
            return stream == null ? null : new Found(lastModified, stream);
        } catch (WebloggerException | RuntimeException ex) {
            log.debug("Unable to get resource", ex);
            return null;
        }
    }

    private static void fail(HttpServletResponse response, int status) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
        }
        response.sendError(status);
    }

}
