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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;

/**
 * A weblog resource found by path -- a theme's file or an uploaded one -- as
 * the resource servlets serve it: its bytes, still to be closed, and when it
 * last changed. Shared by {@link ResourceServlet} and
 * {@link PreviewResourceServlet}, which look in the same places.
 */
record ServableResource(long lastModified, InputStream stream) {

    private static final Logger log = LoggerFactory.getLogger(ServableResource.class);

    /** The theme file, or null when there is none or it has no bytes. */
    static ServableResource of(ThemeResource resource) {
        if (resource == null) {
            return null;
        }
        long lastModified = resource.getLastModified();
        InputStream stream = resource.getInputStream();
        return stream == null ? null : new ServableResource(lastModified, stream);
    }

    /**
     * The file uploaded to the weblog at that original path, or null. A
     * private directory's files are not served publicly by any route (see
     * MediaResourceServlet), and the authoring preview admits any editor, not
     * only this weblog's members: such a file answers as one that does not
     * exist, so nothing here reveals that it does. A lookup that fails is a
     * miss too.
     */
    static ServableResource uploaded(Weblogger weblogger, Weblog weblog, String path) {
        try {
            MediaFile mf = weblogger.getMediaFileManager().getMediaFileByOriginalPath(weblog, path);
            if (mf == null || (mf.getDirectory() != null && mf.getDirectory().isPrivate())) {
                return null;
            }
            long lastModified = mf.getLastModified();
            InputStream stream = mf.getInputStream();
            return stream == null ? null : new ServableResource(lastModified, stream);
        } catch (WebloggerException | RuntimeException ex) {
            log.debug("Unable to get resource", ex);
            return null;
        }
    }

    /**
     * Answers 304 when the reader's copy is current; otherwise sends the
     * bytes with their modification time and the container's mime type for
     * the path. The stream is closed either way: here for a 304, which sends
     * no bytes, and by streamResource otherwise.
     */
    void serve(HttpServletRequest request, HttpServletResponse response, ServletContext context, String path)
            throws IOException {
        if (ModDateHeaderUtil.respondIfNotModified(request, response, lastModified)) {
            try {
                stream.close();
            } catch (IOException e) {
                // the 304 is already decided; a stream that will not close is no reason to fail it
                log.debug("Could not close the stream of {}", path, e);
            }
            return;
        }
        ModDateHeaderUtil.setLastModifiedHeader(response, lastModified);
        response.setContentType(context.getMimeType(path));
        RenderingServletUtils.streamResource(stream, response, path);
    }
}
