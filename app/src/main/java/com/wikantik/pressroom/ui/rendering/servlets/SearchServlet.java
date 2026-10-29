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
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCaches;

/**
 * Handles search queries for weblogs.
 */
public class SearchServlet extends HttpServlet {

    private static final long serialVersionUID = 6246730804167411636L;

    private static final Logger log = LoggerFactory.getLogger(SearchServlet.class);

    // Development theme reloading
    Boolean themeReload = false;

    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public SearchServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    /**
     * Init method for this servlet
     */
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);

        log.info("Initializing SearchServlet");

        // Development theme reloading
        themeReload = WebloggerConfig.getBooleanProperty("themes.reload.mode");
    }

    /**
     * Handle GET requests for weblog pages.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");

        Weblog weblog;
        WeblogSearchRequest searchRequest;

        // first off lets parse the incoming request and validate it
        try {
            searchRequest = new WeblogSearchRequest(weblogger, request);

            // now make sure the specified weblog really exists
            weblog = searchRequest.getWeblog();
            if (weblog == null) {
                RenderingServletUtils.sendNotFound(response);
                return;
            }
        } catch (Exception e) {
            // invalid search request format or weblog doesn't exist
            log.debug("error creating weblog search request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // Development only. Reload if theme has been modified
        if (themeReload && !WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())) {

            RenderingServletUtils.reloadThemeFromDisk(weblog,
                    RenderCaches.forPage(WebloggerRuntimeConfig
                            .isSiteWideWeblog(searchRequest.getWeblogHandle())),
                    weblogger.getThemeManager());
        }

        // Multi-locale weblogs are gone: a weblog's search always covers
        // every locale now, so forcing searchRequest.getLocale() to the
        // weblog's own locale here -- the old showAllLangs=false behaviour --
        // no longer has anything to trigger it.

        // lookup template to use for rendering
        ThemeTemplate page = null;
        try {
            // try looking for a specific search page
            WeblogTheme theme = weblogger.getThemeManager().getTheme(weblog);
            page = theme.getTemplateByAction(ThemeTemplate.ComponentType.SEARCH);

            // if not found then fall back on default page
            if (page == null) {
                page = theme.getDefaultTemplate();
            }

            // if still null then that's a problem
            if (page == null) {
                throw new WebloggerException("Could not lookup default page for weblog " + weblog.getHandle());
            }
        } catch (Exception e) {
            log.error("Error getting default page for weblog {}", weblog.getHandle(), e);
        }

        // set the content type
        response.setContentType("text/html; charset=utf-8");

        // looks like we need to render content
        Map<String, Object> model = new HashMap<>();
        try {
            PageContext pageContext = JspFactory.getDefaultFactory()
                    .getPageContext(this, request, response, "", false, PressroomConstants.EIGHT_KB_IN_BYTES, true);

            // this is a little hacky, but nothing we can do about it
            // we need the 'weblogRequest' to be a pageRequest so other models
            // are properly loaded, which means that searchRequest needs its
            // own custom initData property aside from the standard
            // weblogRequest.
            // possible better approach is make searchRequest extend
            // pageRequest.
            WeblogPageRequest pageRequest = new WeblogPageRequest(weblogger);
            pageRequest.setWeblogHandle(searchRequest.getWeblogHandle());
            pageRequest.setWeblogCategoryName(searchRequest.getWeblogCategoryName());
            pageRequest.setLocale(searchRequest.getLocale());
            pageRequest.setAuthenticUser(searchRequest.getAuthenticUser());

            // populate the rendering model
            Map<String, Object> initData = new HashMap<>();
            initData.put("request", request);
            initData.put("pageContext", pageContext);
            initData.put("parsedRequest", pageRequest);
            initData.put("searchRequest", searchRequest);
            initData.put("urlStrategy", weblogger.getUrlStrategy());

            RenderingServletUtils.loadModels("rendering.searchModels", model, initData,
                    WebloggerRuntimeConfig.isSiteWideWeblog(weblog.getHandle()), weblogger);

        } catch (WebloggerException ex) {
            log.error("Error building the rendering model for search", ex);

            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // render and write out; the content type was set above
        if (RenderingServletUtils.renderAndFlush(page, model,
                PressroomConstants.FOUR_KB_IN_BYTES, null, "search template",
                "Couldn't find renderer for search template", response) == null) {
            return;
        }

        log.debug("Exiting");
    }

}
