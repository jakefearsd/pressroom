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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Template;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import com.wikantik.pressroom.util.I18nMessages;
import com.wikantik.pressroom.ui.rendering.Renderer;
import com.wikantik.pressroom.ui.rendering.RendererManager;
import com.wikantik.pressroom.ui.rendering.model.ModelLoader;
import com.wikantik.pressroom.ui.rendering.velocity.VelocityRenderer;
import com.wikantik.pressroom.util.cache.CachedContent;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

final class RenderingServletUtils {

    private static final Logger log = LoggerFactory.getLogger(RenderingServletUtils.class);

    private RenderingServletUtils() {}

    /**
     * Sends a 404 Not Found error, resetting the response first if not yet committed.
     */
    static void sendNotFound(HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Fills the rendering model, plus the site-wide models when this request is
     * for the front-page weblog.
     *
     * <p>All four rendering servlets loaded their own model list and then
     * conditionally loaded the site-wide one, in four identical copies that
     * differed only in which config key named the first list.
     *
     * <p>This deliberately does not handle failure: every caller already sits
     * inside a try that turns a WebloggerException into a reset-and-500, and
     * catching here as well would mean either duplicating that or handing back
     * a status flag the caller has to re-test -- which costs a branch in each
     * servlet to save one, and buys nothing.
     *
     * <p>The {@code weblogger} handed in is put into {@code initData} under the
     * key {@code "weblogger"}, next to the {@code urlStrategy} the servlets
     * already supply, so that models receive the business tier from the servlet
     * that built them rather than locating it statically.
     *
     * @param modelsProperty config key naming this servlet's model list
     * @param siteWide       whether to also load the site-wide models
     * @param weblogger      the servlet's business-tier facade, made available
     *                       to every model through {@code initData}
     */
    static void loadModels(String modelsProperty, Map<String, Object> model,
                           Map<String, Object> initData, boolean siteWide,
                           Weblogger weblogger)
            throws WebloggerException {

        initData.put("weblogger", weblogger);

        ModelLoader.loadModels(WebloggerConfig.getProperty(modelsProperty),
                model, initData, true);

        if (siteWide) {
            ModelLoader.loadModels(WebloggerConfig.getProperty("rendering.siteModels"),
                    model, initData, true);
        }
    }

    /**
     * Streams a resource file to the client and closes it.
     *
     * <p>The error path is the reason this is shared. Bytes are already going
     * out when the failure happens, so the response is normally COMMITTED by
     * then -- and {@code sendError} on a committed response throws
     * IllegalStateException, which would replace the IOException that actually
     * happened with a misleading one from the handler. ResourceServlet had that
     * bug (it guarded the reset with isCommitted but not the sendError) while
     * PreviewResourceServlet did not; this is the preview servlet's shape,
     * applied to both.
     *
     * <p>The stream is null-checked before use even though no current caller
     * can hand over null. Every caller reaches this having gone through three
     * separate lookups, and it holds only because MediaFile.getInputStream's
     * null return is unreachable through the one manager method they use --
     * an invariant three classes away that nothing here can see.
     */
    static void streamResource(InputStream resourceStream, HttpServletResponse response,
                               String logContext) throws IOException {

        if (resourceStream == null) {
            log.error("No content to stream for {}", logContext);
            sendNotFound(response);
            return;
        }

        try (resourceStream) {
            resourceStream.transferTo(response.getOutputStream());

        } catch (IOException ex) {
            if (response.isCommitted()) {
                // Bytes had already gone out, so this is the write side
                // failing: the reader closed the connection (Tomcat's
                // ClientAbortException). Not a server error, and once per
                // abandoned download it buried the real ones.
                log.debug("Reader went away while serving {}: {}", logContext, ex.toString());
                return;
            }
            log.error("Error writing resource file for {}", logContext, ex);
            response.reset();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Reports a server-side failure, discarding anything already written.
     *
     * <p>The reset is the part worth having in one place: without it a failure
     * partway through building a response leaves the bytes written so far in
     * front of the error, and the client sees a 500 stapled to half a page.
     */
    static void sendServerError(HttpServletResponse response, String message, Exception cause)
            throws IOException {

        log.error(message, cause);
        if (!response.isCommitted()) {
            response.reset();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        // else: sendError on a committed response throws IllegalStateException,
        // which would replace the failure just logged with a misleading one.
    }

    /**
     * Development-mode theme reloading: if the theme's files have changed on
     * disk, drop the rendered content that was built from them.
     *
     * <p>Whether to attempt this at all stays with the caller, because the
     * guards differ -- PageServlet additionally skips stylesheet requests. What
     * is shared is the part that must not drift: reloading and dropping the
     * cache have to happen together, since a reloaded theme behind a stale
     * cache serves the old page and looks like the reload silently failed.
     *
     * <p>Failure is logged and swallowed deliberately. This is a developer
     * convenience; a theme that will not reload must not turn a reader's page
     * into an error.
     */
    static void reloadThemeFromDisk(Weblog weblog, RenderCache<?> renderCache,
                                    ThemeManager themeManager) {
        try {
            boolean reloaded = themeManager.reLoadThemeFromDisk(weblog.getEditorTheme());
            if (reloaded) {
                renderCache.clear();
                I18nMessages.reloadBundle(weblog.getLocaleInstance());
            }
        } catch (Exception ex) {
            log.error("ERROR - reloading theme", ex);
        }
    }

    /**
     * The last three steps every rendering servlet ends with: find the renderer
     * for a template, run the model through it, and write the bytes out.
     *
     * <p>All four had these open-coded, identically apart from a buffer size, a
     * log message and whether they set a content type. Two of the three steps
     * fail by completing the response themselves, so a caller that got the
     * order or the early returns wrong would send a body after an error -- which
     * is the reason to have this in one place rather than the twenty lines it
     * saves.
     *
     * @param contentType  set on the response before the body is written, and
     *                     carried on the returned buffer so that a cached copy
     *                     replayed later announces the same type. Null means the
     *                     caller has already set it (or does not set one).
     * @param missingRendererMessage logged at error level when no renderer can
     *                     be found, or null to log nothing. FeedServlet passes
     *                     null deliberately: feed template ids are built from
     *                     request data and are routinely bunk, so this fires for
     *                     ordinary bad input rather than for anything an
     *                     operator can act on.
     * <p>A template that rendered an error (see
     * {@link VelocityRenderer#renderedError()}) still sends its body, but as
     * a 500, and comes back as null like every other completed error, so no
     * caller caches it.
     *
     * @return the rendered bytes, for a caller that wants to cache them, or
     *         null when the response has already been completed with an error
     *         and the caller must simply return
     */
    static CachedContent renderAndFlush(Template template, Map<String, Object> model,
                                        int bufferSize, String contentType,
                                        String logContext, String missingRendererMessage,
                                        HttpServletResponse response) throws IOException {

        Renderer renderer;
        try {
            log.debug("Looking up renderer");
            renderer = RendererManager.getRenderer(template);
        } catch (Exception e) {
            // nobody wants to render my content :(
            if (missingRendererMessage != null) {
                log.error(missingRendererMessage, e);
            }
            sendNotFound(response);
            return null;
        }

        CachedContent rendererOutput =
                render(renderer, model, bufferSize, contentType, logContext, response);
        if (rendererOutput == null) {
            return null;
        }

        boolean renderedError = renderer instanceof VelocityRenderer velocity
                && velocity.renderedError();
        if (renderedError) {
            log.debug("Rendered the error template for {}; answering 500, not caching", logContext);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        log.debug("Flushing response output");
        if (contentType != null) {
            response.setContentType(contentType);
        }
        response.setContentLength(rendererOutput.getContent().length);
        response.getOutputStream().write(rendererOutput.getContent());

        return renderedError ? null : rendererOutput;
    }

    /**
     * Renders content into a CachedContent buffer. Returns the buffer on success,
     * or null if rendering failed (in which case a 404 has already been sent).
     */
    static CachedContent render(Renderer renderer, Map<String, Object> model,
                                int bufferSize, String logContext,
                                HttpServletResponse response) throws IOException {
        return render(renderer, model, bufferSize, null, logContext, response);
    }

    /**
     * Renders content into a CachedContent buffer with a specific content type.
     * Returns the buffer on success, or null if rendering failed (in which case
     * a 404 has already been sent).
     */
    static CachedContent render(Renderer renderer, Map<String, Object> model,
                                int bufferSize, String contentType, String logContext,
                                HttpServletResponse response) throws IOException {
        CachedContent output = new CachedContent(bufferSize, contentType);
        try {
            log.debug("Doing rendering");
            renderer.render(model, output.getCachedWriter());
            output.flush();
            output.close();
            return output;
        } catch (Exception e) {
            log.error("Error during rendering for {}", logContext, e);
            try {
                output.close();
            } catch (IOException closeFailure) {
                log.debug("Failed to close render buffer after a render error", closeFailure);
            }
            sendNotFound(response);
            return null;
        }
    }
}
