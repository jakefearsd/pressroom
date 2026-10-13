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

package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.StaticThemeTemplate;
import org.apache.roller.weblogger.pojos.Theme;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.pojos.Template;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPreviewRequest;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * Responsible for rendering weblog page previews.
 *
 * This servlet is used as part of the authoring interface to provide previews
 * of what a weblog will look like with a given theme.  It is not available
 * outside of the authoring interface.
 */
public class PreviewServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(PreviewServlet.class);
    
    
    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public PreviewServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    /**
     * Init method for this servlet
     */
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        
        super.init(servletConfig);
        
        log.info("Initializing PreviewServlet");
    }
    
    
    /**
     * Handle GET requests for weblog pages.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");
        
        Weblog weblog;
        
        WeblogPreviewRequest previewRequest;

        try {
            previewRequest = new WeblogPreviewRequest(weblogger, request);

            // lookup weblog specified by preview request
            weblog = previewRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: " +
                        previewRequest.getWeblogHandle());
            }
        } catch (Exception e) {
            // some kind of error parsing the request or getting weblog
            log.debug("error creating preview request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        
		Weblog tmpWebsite = weblog;
        
        if (previewRequest.getThemeName() != null) {
            // only create temporary weblog object if theme name was specified
            // in request, which indicates we're doing a theme preview

            // try getting the preview theme
            log.debug("preview theme = {}", previewRequest.getThemeName());
            Theme previewTheme = previewRequest.getTheme();

            // construct a temporary Website object for this request
            // and set the EditorTheme to our previewTheme
            tmpWebsite = new Weblog();
            tmpWebsite.setData(weblog);
            if(previewTheme != null && previewTheme.isEnabled()) {
                tmpWebsite.setEditorTheme(previewTheme.getId());
            } else if(WeblogTheme.CUSTOM.equals(previewRequest.getThemeName())) {
                tmpWebsite.setEditorTheme(WeblogTheme.CUSTOM);
            }

            // we've got to set the weblog in our previewRequest because that's
            // the object that gets referenced during rendering operations
            previewRequest.setWeblog(tmpWebsite);
        }
        
        // Multi-locale weblogs are gone: a weblog's preview always covers
        // every locale now, so forcing previewRequest.getLocale() to the
        // weblog's own locale here -- the old showAllLangs=false behaviour --
        // no longer has anything to trigger it.

        // WeblogPreviewRequest keeps no reference to the request it was parsed
        // from, so the one flag the shell needs is read here and passed down
        // rather than reached for inside selectTemplate.
        Template page = selectTemplate(previewRequest, weblog, tmpWebsite,
                "true".equals(request.getParameter("shell")));
        if (page == null) {
            // Either the tags rung refused outright (a tags preview without a
            // tags template must not fall through to the front page) or nothing
            // in the ladder matched. Both are a 404.
            RenderingServletUtils.sendNotFound(response);
            return;
        }
        
        
        log.debug("preview page found, dealing with it");
        
        // set the content type
        String pageLink = previewRequest.getWeblogPageName();
        String mimeType = pageLink !=  null ? RollerContext.getServletContext().getMimeType(pageLink) : null;        
        String contentType = "text/html; charset=utf-8";
        if(mimeType != null) {
            // we found a match ... set the content type
            contentType = mimeType+"; charset=utf-8";
        } else if ("_css".equals(previewRequest.getWeblogPageName())) {
            // TODO: store content-type for each page so this hack is unnecessary
            contentType = "text/css; charset=utf-8";
        }
        
        // looks like we need to render content
        Map<String, Object> model;
        try {
            model = buildModel(request, response, previewRequest, weblog);
        } catch (WebloggerException ex) {
            RenderingServletUtils.sendServerError(response, "ERROR building the rendering model for preview", ex);
            return;
        }
        
        
        if (RenderingServletUtils.renderAndFlush(page, model,
                RollerConstants.TWENTYFOUR_KB_IN_BYTES, contentType,
                "page " + page.getId(),
                "Couldn't find renderer for preview page " + page.getId(),
                response) == null) {
            return;
        }
        
        log.debug("Exiting");
    }

    /**
     * The model the preview template renders against, or null when building it
     * failed and a 500 has already been sent.
     *
     * <p>Note the url strategy: preview links have to point back into the
     * preview, carrying the theme being previewed, or every link on the page
     * would leave the preview and show the live weblog instead.
     */
    private Map<String, Object> buildModel(HttpServletRequest request,
                                           HttpServletResponse response,
                                           WeblogPreviewRequest previewRequest,
                                           Weblog weblog) throws WebloggerException {

        Map<String, Object> model = new HashMap<>();
        PageContext pageContext = JspFactory.getDefaultFactory().getPageContext(
                this, request, response,"", false, RollerConstants.EIGHT_KB_IN_BYTES, true);
        
        // special hack for menu tag
        request.setAttribute("pageRequest", previewRequest);
        
        // populate the rendering model
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", previewRequest);
        initData.put("pageContext", pageContext);
        
        // define url strategy
        initData.put("urlStrategy", weblogger.getUrlStrategy().getPreviewURLStrategy(previewRequest.getThemeName()));
        
        RenderingServletUtils.loadModels("rendering.previewModels", model, initData,
                WebloggerRuntimeConfig.isSiteWideWeblog(weblog.getHandle()), weblogger);

        return model;
    }

    /**
     * The template this preview renders with, or null for a 404.
     *
     * <p>A ladder, in order: the editor's shell, then a page named on the url,
     * then the tags index, then the permalink template, then the theme's
     * default. One rung does not fall through -- a tags-index request against a
     * theme with no tags template returns null rather than dropping to the
     * default, because rendering the front page for a /tags preview would tell
     * an author their tags page works when the theme has no such page at all.
     *
     * @param shell whether the request asked for the editor's live-preview
     *              shell ({@code ?shell=true}); false is every other caller and
     *              leaves the ladder below exactly as it was.
     */
    private Template selectTemplate(WeblogPreviewRequest previewRequest, Weblog weblog,
                                    Weblog tmpWebsite, boolean shell) {

        if (shell) {
            return previewShell(tmpWebsite);
        }

        Template page = null;

        if ("page".equals(previewRequest.getContext())) {
            page = previewRequest.getWeblogPage();

        } else if ("tags".equals(previewRequest.getContext())
                && previewRequest.getTags() == null) {
            page = templateForAction(weblog, ComponentType.TAGSINDEX, "tagsIndex");
            // deliberately no fall-through: null here means 404
            return page;

        } else if (previewRequest.getWeblogAnchor() != null) {
            page = templateForAction(weblog, ComponentType.PERMALINK, "permalink");
        }

        if (page == null) {
            try {
                page = weblogger.getThemeManager().getTheme(tmpWebsite).getDefaultTemplate();
            } catch (WebloggerException re) {
                log.error("Error getting default page for preview", re);
            }
        }
        return page;
    }

    /**
     * The theme's live-preview shell: stylesheet, asset macros and one empty
     * article, which the editor fills over {@code postMessage} with the
     * fragment {@code entryEdit!preview.rol} rendered.
     *
     * <p>The same two-rung shape as {@code PageServlet}'s {@code _page}
     * lookup, for the same reason. A theme may ship its own {@code _preview}
     * -- only the theme knows the classes its reading column is styled
     * through -- and one that has never heard of the editor falls back to the
     * shared shell, so every theme previews in its own stylesheet whether or
     * not it was written for this.
     *
     * <p>Never null: a shell request is a request the editor has already
     * committed to framing, so an empty surface in the weblog's stylesheet
     * beats a 404 in an iframe.
     */
    private Template previewShell(Weblog tmpWebsite) {
        ThemeTemplate shell = null;
        try {
            shell = weblogger.getThemeManager().getTheme(tmpWebsite).getTemplateByName("_preview");
        } catch (Exception e) {
            // Not simply "no _preview override" -- getTemplateByName returns
            // null for that. An exception is a real lookup failure, so fall
            // back to the shared shell (the author still gets a preview) but
            // leave a trace for whoever wonders why it looks generic.
            log.warn("Error looking up '_preview' template for weblog {}",
                    tmpWebsite.getHandle(), e);
        }
        return shell != null ? shell
                : new StaticThemeTemplate("templates/weblog/preview.vm", TemplateLanguage.VELOCITY);
    }

    /**
     * One template by action, or null when the theme does not define it or
     * cannot be read. Failing soft here is what lets the ladder above try the
     * next rung.
     */
    private Template templateForAction(Weblog weblog, ComponentType action, String label) {
        try {
            return weblogger.getThemeManager().getTheme(weblog).getTemplateByAction(action);
        } catch (Exception e) {
            log.error("Error getting weblog page for action '{}'", label, e);
            return null;
        }
    }
}
