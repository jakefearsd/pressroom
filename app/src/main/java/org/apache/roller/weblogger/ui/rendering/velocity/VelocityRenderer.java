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

package org.apache.roller.weblogger.ui.rendering.velocity;

import java.io.Writer;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.pojos.Template;
import org.apache.roller.weblogger.ui.rendering.Renderer;
import org.apache.roller.weblogger.ui.rendering.RenderingException;
import org.apache.roller.weblogger.ui.rendering.model.UtilitiesModel;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.VelocityException;

/**
 * Renderer that renders using the Velocity template engine.
 */
public class VelocityRenderer implements Renderer {

    private static final Logger log = LoggerFactory.getLogger(VelocityRenderer.class);

    // the original template we are supposed to render
    private final Template renderTemplate;

    // the velocity template
    private org.apache.velocity.Template velocityTemplate;

    // a possible exception
    private Exception velocityException = null;

    public VelocityRenderer(Template template) throws Exception {

        // the Template we are supposed to render
        this.renderTemplate = template;

        try {
            // make sure that we can locate the template
            // if we can't then this will throw an exception
            velocityTemplate = RollerVelocity.getTemplate(template.getId(), "UTF-8");

        } catch (ResourceNotFoundException ex) {
            // velocity couldn't find the resource so lets log a warning
            log.warn("Error creating renderer for {} due to [{}]", template.getId(), ex.getMessage());

            // then just rethrow so that the caller knows this instantiation
            // failed
            throw ex;

        } catch (VelocityException ex) {
            // in the case of a velocity error we want to render an
            // error page instead so the user knows what was wrong; the
            // detail goes to the log, never to the reader
            log.error("Template {} could not be loaded; rendering the error page", template.getId(), ex);
            velocityException = ex;

            // need to lookup error page template
            velocityTemplate = RollerVelocity.getTemplate("error-page.vm");

        } catch (Exception ex) {
            // some kind of generic/unknown exception, dump it to the logs
            log.error("Unknown exception creating renderer for {}", template.getId(), ex);

            // throw if back to the caller
            throw ex;
        }
    }

    /**
     * @see org.apache.roller.weblogger.ui.rendering.Renderer#render(java.util.Map,
     *      java.io.Writer)
     */
    @Override
    public void render(Map<String, Object> model, Writer out)
            throws RenderingException {

        try {

            if (velocityException != null) {

                // Render exception
                renderException(model, out, null);

                // and we're done
                return;
            }

            long startTime = System.currentTimeMillis();

            // convert model to Velocity Context
            Context ctx = new VelocityContext(model);

            // Decorator support (rendering a base template, then re-merging
            // it into a second "decorator" template) was never wired up:
            // velocityDecorator had no setter and was assigned nothing but
            // its initial null anywhere in this class, so the branch that
            // used to live here (UWF_NULL_FIELD) was permanently dead code
            // that always fell through to this same call. Simplified to
            // what it always actually did.
            velocityTemplate.merge(ctx, out);

            long endTime = System.currentTimeMillis();
            long renderTime = (endTime - startTime) / RollerConstants.SEC_IN_MS;

            log.debug("Rendered [{}] in {} secs", renderTemplate.getId(), renderTime);

        } catch (VelocityException ex) {

            // in the case of a velocity error including a page we want to
            // render an error page instead so the user knows what was wrong;
            // the detail goes to the log, never to the reader
            log.error("Error rendering template {}; splicing in the error block",
                    renderTemplate.getId(), ex);
            velocityException = ex;

            // need to lookup parse error template
            renderException(model, out, "error-parse.vm");

        } catch (Exception ex) {
            // wrap and rethrow so caller can deal with it
            throw new RenderingException("Error during rendering", ex);
        }
    }

    /**
     * Whether what this renderer wrote is an error: the whole-page error
     * template in place of a template that would not parse, or the error
     * block spliced in where a render failed partway. The reader still gets
     * that body, but the response must not be a 200 and must not be cached:
     * a rendered page expires only when {@code weblog.lastModified} moves, so
     * a cached error (one database blip inside a model getter) would stay in
     * front of every reader until an author next saved something.
     */
    public boolean renderedError() {
        return velocityException != null;
    }

    /**
     * Render Velocity Exception.
     * 
     * @param model
     *            the model
     * @param out
     *            the out
     * @param template
     *            the template. Null if using existing template name
     * 
     * @throws RenderingException
     *             the rendering exception
     */
    private void renderException(Map<String, Object> model, Writer out,
            String template) throws RenderingException {

        try {

            if (template != null) {
                // need to lookup error page template
                velocityTemplate = RollerVelocity.getTemplate(template);
            }

            Context ctx = new VelocityContext(model);
            ctx.put("exception", velocityException);
            ctx.put("exceptionSource", renderTemplate.getId());
            ctx.put("utils", new UtilitiesModel());

            // render output to Writer
            velocityTemplate.merge(ctx, out);

        } catch (Exception e) {
            // wrap and rethrow so caller can deal with it
            throw new RenderingException("Error during rendering", e);
        }

    }

}
