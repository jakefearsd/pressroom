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
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;
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

    private transient ServletContext context = null;

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
    // False positive: "'resourceStream' is reassigned, but the original
    // instance is not closed" -- each reassignment below is reached only
    // inside `if (resourceStream == null)`, so there is never a live stream
    // to lose at that point. The stream this variable ends up holding is
    // closed by RenderingServletUtils.streamResource, which this method hands it to.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Weblog weblog;

        WeblogPreviewResourceRequest resourceRequest;
        try {
            // parse the incoming request and extract the relevant data
            resourceRequest = new WeblogPreviewResourceRequest(weblogger, request);

            weblog = resourceRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + resourceRequest.getWeblogHandle());
            }

        } catch (Exception e) {
            // invalid resource request or weblog doesn't exist
            log.debug("error creating weblog resource request", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        log.debug("Resource requested [{}]", resourceRequest.getResourcePath());

        long resourceLastMod = 0;
        InputStream resourceStream = null;

        // first, see if we have a preview theme to operate from
        if (!StringUtils.isEmpty(resourceRequest.getThemeName())) {
            Theme theme = resourceRequest.getTheme();
            ThemeResource resource = theme.getResource(resourceRequest
                    .getResourcePath());
            if (resource != null) {
                resourceLastMod = resource.getLastModified();
                resourceStream = resource.getInputStream();
            }
        }

        // second, see if resource comes from weblog's configured shared theme
        if (resourceStream == null) {
            try {
                WeblogTheme weblogTheme = weblogger.getThemeManager().getTheme(weblog);
                if (weblogTheme != null) {
                    ThemeResource resource = weblogTheme
                            .getResource(resourceRequest.getResourcePath());
                    if (resource != null) {
                        resourceLastMod = resource.getLastModified();
                        resourceStream = resource.getInputStream();
                    }
                }
            } catch (Exception ex) {
                // hmmm, some kind of error getting theme. that's an error.
                // resourceStream is unconditionally null here: the only
                // assignment to it in this try block is the last statement,
                // so any exception thrown by this block is thrown before
                // that assignment runs and there is never a stream to close.
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }

        // if not from theme then see if resource is in weblog's upload dir
        if (resourceStream == null) {
            try {
                MediaFileManager mmgr = weblogger.getMediaFileManager();
                MediaFile mf = mmgr.getMediaFileByOriginalPath(weblog,
                        resourceRequest.getResourcePath());
                // The authoring path admits any editor, not only this weblog's
                // members, so a private directory's file is refused here as on
                // ResourceServlet: answered as a file that does not exist.
                if (mf.getDirectory() != null && mf.getDirectory().isPrivate()) {
                    throw new WebloggerException("private media file");
                }
                resourceLastMod = mf.getLastModified();
                resourceStream = mf.getInputStream();

            } catch (Exception ex) {
                // still not found? then we don't have it, 404.
                // resourceStream is unconditionally null here for the same
                // reason as the theme-lookup catch above: the enclosing
                // `if (resourceStream == null)` guarantees it going in, and
                // this try block's only assignment to it is its last
                // statement, so any exception here precedes that assignment.
                log.debug("Unable to get resource", ex);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }

        // Respond with 304 Not Modified if it is not modified.
        if (ModDateHeaderUtil.respondIfNotModified(request, response,
                resourceLastMod)) {
            return;
        } else {
            // set last-modified date
            ModDateHeaderUtil.setLastModifiedHeader(response, resourceLastMod);
        }

        // set the content type based on whatever is in our web.xml mime defs
        response.setContentType(this.context.getMimeType(resourceRequest
                .getResourcePath()));

        RenderingServletUtils.streamResource(resourceStream, response,
                resourceRequest.getResourcePath());
    }

}
