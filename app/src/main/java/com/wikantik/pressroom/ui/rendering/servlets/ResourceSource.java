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
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;

/**
 * One place a resource path may be found. The resource servlets try an ordered
 * list of these and serve the first hit; the preview servlet is the normal
 * list with one more source in front.
 */
@FunctionalInterface
interface ResourceSource {

    /** The resource at that path, or null when this source has none. */
    ServableResource find(String path) throws WebloggerException;

    /** The files of the weblog's configured theme. */
    static ResourceSource theme(Weblogger weblogger, Weblog weblog) {
        return path -> {
            WeblogTheme weblogTheme = weblogger.getThemeManager().getTheme(weblog);
            return weblogTheme == null ? null : ServableResource.of(weblogTheme.getResource(path));
        };
    }

    /** The files uploaded to the weblog. */
    static ResourceSource uploaded(Weblogger weblogger, Weblog weblog) {
        return path -> ServableResource.uploaded(weblogger, weblog, path);
    }

    /**
     * Serves the first source's hit: 500 when a source fails, 404 when none
     * has the path.
     */
    static void serveFirst(List<ResourceSource> sources, HttpServletRequest request,
            HttpServletResponse response, ServletContext context, String path) throws IOException {
        ServableResource found = null;
        try {
            for (ResourceSource source : sources) {
                found = source.find(path);
                if (found != null) {
                    break;
                }
            }
        } catch (WebloggerException | RuntimeException ex) {
            // some kind of error getting a theme. that's an error.
            RenderingServletUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (found == null) {
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        found.serve(request, response, context, path);
    }
}
