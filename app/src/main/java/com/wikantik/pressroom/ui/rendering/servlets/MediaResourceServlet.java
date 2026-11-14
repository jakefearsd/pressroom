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

import com.wikantik.pressroom.business.FilePathException;
import com.wikantik.pressroom.business.FileNotFoundException;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.RenditionSupport;
import com.wikantik.pressroom.pojos.FileContent;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;
import com.wikantik.pressroom.ui.rendering.util.WeblogMediaResourceRequest;

/**
 * Serves media files uploaded by users.
 * 
 * Since we keep resources in a location outside of the webapp context we need a
 * way to serve them up. This servlet assumes that resources are stored on a
 * filesystem in the "uploads.dir" directory.
 */
public class MediaResourceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(MediaResourceServlet.class);

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public MediaResourceServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {

        super.init(config);
        log.info("Initializing ResourceServlet");

    }

    /**
     * Handles requests for user uploaded media file resources.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        WeblogMediaResourceRequest resourceRequest;
        Weblog weblog;
        try {
            // parse the incoming request and extract the relevant data
            resourceRequest = new WeblogMediaResourceRequest(weblogger, request);

            weblog = resourceRequest.getWeblog();

        } catch (WebloggerException | RuntimeException e) {
            // invalid resource request
            log.debug("error creating weblog resource request", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        // The site space's files are reached only through the mapper's
        // forward of /site-space/<context>/..., never at this servlet's
        // own path.
        if (weblog == null || (weblog.isSiteSpace() && request.getDispatcherType() != DispatcherType.FORWARD)) {
            log.debug("no weblog to serve resources for {}", resourceRequest.getWeblogHandle());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        MediaFile mediaFile = servableMediaFile(resourceRequest, weblog, response);
        if (mediaFile == null) {
            return;
        }

        // A ?w= URL serves different bytes and a different Content-Type
        // depending on the request's Accept header (webp sibling vs raster
        // rendition), so any shared cache must key on that header. Set before
        // the 304 branch below so revalidation responses carry it too.
        boolean renditionRequest = RenditionSupport.ladderWidths()
                .contains(resourceRequest.getWidth());
        if (renditionRequest) {
            response.setHeader("Vary", "Accept");
        }

        // Respond with 304 Not Modified if it is not modified.
        long resourceLastMod = mediaFile.getLastModified();
        if (ModDateHeaderUtil.respondIfNotModified(request, response,
                resourceLastMod)) {
            return;
        }
        ModDateHeaderUtil.setLastModifiedHeader(response, resourceLastMod);

        Served served = null;
        if (resourceRequest.isThumbnail()) {
            served = thumbnail(mediaFile);
        } else if (renditionRequest) {
            served = rendition(request, weblog, mediaFile, resourceRequest.getWidth());
        }
        if (served == null) {
            served = new Served(mediaFile.getContentType(), mediaFile.getInputStream());
        }
        // The type is the uploader's claim: never sniffed, and anything that
        // could run script on this origin is sandboxed (MediaResponseHeaders).
        response.setContentType(MediaResponseHeaders.apply(response, served.contentType()));

        serve(served.stream(), mediaFile, response);
    }

    /**
     * The media file the request names, when this weblog may serve it to
     * this requester; otherwise null, with the 404 already sent. The three
     * refusals answer alike: an unknown id, a file of another weblog, and a
     * private file asked for by someone who may not edit the weblog.
     */
    private MediaFile servableMediaFile(WeblogMediaResourceRequest resourceRequest, Weblog weblog,
            HttpServletResponse response) throws IOException {
        MediaFile mediaFile;
        try {
            mediaFile = weblogger.getMediaFileManager().getMediaFile(resourceRequest.getResourceId(),
                    true);
        } catch (Exception ex) {
            // still not found? then we don't have it, 404.
            log.debug("Unable to get resource", ex);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        if (mediaFile == null || mediaFile.getLastUpdated() == null) {
            log.debug("No servable media file {}", resourceRequest.getResourceId());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        // A media file is only addressable through its own weblog's URL
        // space. The id lookup above is global, so without this check any
        // media id would be fetchable through any weblog handle (and the
        // ?t=/?w= variants below with it). Respond exactly as for an
        // unknown id. The directory's weblog is the authority here -- it is
        // also where the manager reads the bytes from.
        Weblog owningWeblog = mediaFile.getDirectory() == null
                ? null : mediaFile.getDirectory().getWeblog();
        if (!weblog.equals(owningWeblog)) {
            log.debug("media file {} does not belong to weblog {}", resourceRequest.getResourceId(),
                    weblog.getHandle());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        // A private directory's files are not served publicly at all. The
        // one exception is the owning weblog's own editors:
        // the media-library screens render their thumbnails through this
        // path, and an author must be able to see the photos they just
        // marked private. Those responses are marked private/no-store so no
        // shared cache can replay an editor's copy to an anonymous reader.
        if (mediaFile.getDirectory().isPrivate()) {
            if (!WeblogEditors.requesterMayEdit(weblog, weblogger.getUserManager())) {
                log.debug("media file {} is in a private directory; not serving it on the base path",
                        resourceRequest.getResourceId());
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return null;
            }
            response.setHeader("Cache-Control", "private, no-store");
        }
        return mediaFile;
    }

    /** The bytes to send and the type they are sent as. */
    private record Served(String contentType, InputStream stream) {
    }

    /** The file's thumbnail, or null (the original is served) when it cannot be read. */
    private static Served thumbnail(MediaFile mediaFile) {
        try {
            return new Served("image/png", mediaFile.getThumbnailInputStream());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ERROR loading thumbnail for {}", mediaFile.getId(), e);
            } else {
                log.warn("ERROR loading thumbnail for {}", mediaFile.getId());
            }
            return null;
        }
    }

    /**
     * A ladder width's rendition: its webp sibling when the reader takes
     * webp and one was generated, else the raster rendition, else null (the
     * original is served). Only a ladder width reaches here; any other ?w=
     * value (including a syntactically valid width Pressroom never
     * generates) falls straight through to the original.
     */
    private Served rendition(HttpServletRequest request, Weblog weblog, MediaFile mediaFile, int width) {
        FileContentManager cmgr = weblogger.getFileContentManager();

        if (acceptsWebp(request)) {
            try {
                FileContent webp = cmgr.getFileContent(weblog, mediaFile.getId() + "_" + width + ".webp");
                return new Served("image/webp", webp.getInputStream());
            } catch (FileNotFoundException | FilePathException | UncheckedIOException e) {
                // no webp sibling (cwebp unavailable at generation time, or
                // this width was never generated) -- fall through
                log.debug("No webp rendition for {} at width {}", mediaFile.getId(), width);
            }
        }

        try {
            FileContent rendition = cmgr.getFileContent(weblog, mediaFile.getId() + "_" + width);
            return new Served(mediaFile.getContentType(), rendition.getInputStream());
        } catch (FileNotFoundException | FilePathException | UncheckedIOException e) {
            // rendition was never generated (narrower than the original,
            // or generation failed) -- 404-safe fallback to the original
            log.debug("No {}w rendition for {}; serving original", width, mediaFile.getId());
            return null;
        }
    }

    /** Copies the stream to the reader and closes it; a reader who leaves is not an error. */
    private static void serve(InputStream resourceStream, MediaFile mediaFile, HttpServletResponse response)
            throws IOException {
        // make sure stream to resource file is closed
        try (InputStream in = resourceStream) {
            // ok, lets serve up the file
            byte[] buf = new byte[PressroomConstants.EIGHT_KB_IN_BYTES];
            int length;
            OutputStream out = response.getOutputStream();
            while ((length = in.read(buf)) > 0) {
                try {
                    out.write(buf, 0, length);
                } catch (IOException gone) {
                    readerWentAway(mediaFile, gone);
                    return;
                }
            }
            try {
                out.close();
            } catch (IOException gone) {
                readerWentAway(mediaFile, gone);
            }

        } catch (IOException | RuntimeException ex) {
            log.error("ERROR serving media file {}", mediaFile.getId(), ex);
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    /**
     * A write to the reader failed: they left mid-file (Tomcat's
     * ClientAbortException), which is routine on a gallery page and not a
     * server error. A failure to READ the file still reaches the ERROR above.
     */
    private static void readerWentAway(MediaFile mediaFile, IOException gone) {
        log.debug("Reader went away while media file {} was being served: {}", mediaFile.getId(), gone.toString());
    }

    /**
     * True if the request's {@code Accept} header indicates the client will
     * take {@code image/webp} (Chrome/Firefox/Edge send this on every image
     * request; browsers that don't understand WebP simply omit it).
     */
    private static boolean acceptsWebp(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("image/webp");
    }

}
