/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.ajax;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.business.themes.ThemeManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Return theme information (id, name, description, relative path to thumbnail)
 * in JSON format.  Usage:
 * <ul>
 * <li>/authoring/themedata - get array of all shared theme data</li>
 * <li>/authoring/themedata?theme=xxx - get data for specific theme</li>
 * </ul>
 */
public class ThemeDataServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * The JSON is written by Jackson rather than by hand. It used to be
     * assembled with {@code print} calls, which meant a theme description
     * carrying a quote or a newline -- both legal in {@code theme.xml}, and a
     * description is prose -- emitted a document no parser accepts. jQuery
     * answers unparseable JSON by never calling {@code success}, so the
     * failure surfaced as a preview that silently never changed.
     */
    private final transient ObjectMapper mapper = new ObjectMapper();

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public ThemeDataServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    protected void doPost(
            HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    @Override
    @SuppressFBWarnings(
            value = "XSS_SERVLET",
            justification = "The body is JSON serialised by Jackson and sent as application/json; its "
                    + "fields come from the themes' own theme.xml, not from the request.")
    protected void doGet(
            HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        List<SharedTheme> themes;
        String themeId;

        themeId = request.getParameter("theme");

        ThemeManager themeMgr = weblogger.getThemeManager();
        if (themeId == null) {
            themes = themeMgr.getEnabledThemesList();
        } else {
            themes = new ArrayList<>(1);
            try {
                SharedTheme theme = themeMgr.getTheme(themeId);
                themes.add(theme);
            } catch (WebloggerException e) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "ERROR fetching theme data");
                return;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(themes.size());
        for (SharedTheme theme : themes) {
            rows.add(describe(theme));
        }

        response.setContentType("application/json; charset=utf-8");
        // Asked for one theme, the caller reads data.description off the answer
        // directly, so a single theme is written unwrapped; asked for
        // everything, it is an array. Serialized to a String rather than
        // straight to the writer because Jackson closes a Writer it is handed
        // (AUTO_CLOSE_TARGET), and the response's writer belongs to the
        // container. These lists are a handful of themes.
        response.getWriter().write(
                mapper.writeValueAsString(themeId == null ? rows : rows.get(0)));
        response.flushBuffer();
    }

    /**
     * The four fields the chooser reads. {@code getPreviewImage()} is
     * dereferenced unguarded, exactly as it always has been: a theme whose
     * {@code theme.xml} names a preview image that cannot be read leaves the
     * field null and takes this endpoint down with it. Pre-existing and left
     * alone here rather than fixed as a side effect of the encoding change.
     */
    private static Map<String, Object> describe(SharedTheme theme) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", theme.getId());
        row.put("name", theme.getName());
        row.put("description", theme.getDescription());
        row.put("previewPath", "/themes/" + theme.getId() + "/" + theme.getPreviewImage().getPath());
        return row;
    }
}
