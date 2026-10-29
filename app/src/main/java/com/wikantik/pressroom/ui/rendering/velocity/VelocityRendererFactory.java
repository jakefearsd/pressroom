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

package com.wikantik.pressroom.ui.rendering.velocity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.StaticThemeTemplate;
import com.wikantik.pressroom.pojos.Template;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.ui.rendering.Renderer;
import com.wikantik.pressroom.ui.rendering.RendererFactory;
import org.apache.velocity.exception.ResourceNotFoundException;


/**
 * RendererFactory for Velocity, creates VelocityRenderers.
 */
public class VelocityRendererFactory implements RendererFactory {
    private static final Logger log = LoggerFactory.getLogger(VelocityRendererFactory.class);

    @Override
    public Renderer getRenderer(Template template) {

        // nothing we can do with null values
        if (template == null || template.getId() == null) {
            return null;
        }

        // A StaticThemeTemplate (e.g. the static-page fallback shipped
        // inside the WAR) is not persisted, so it has no
        // rendition row to read a language from -- by design,
        // getTemplateRendition() always answers null for one. It carries its
        // language directly instead; read that rather than treating "no
        // rendition" as "not a Velocity template".
        TemplateLanguage lang;
        if (template instanceof StaticThemeTemplate staticThemeTemplate) {
            lang = staticThemeTemplate.getTemplateLanguage();
        } else {
            TemplateRendition tr;
            try {
                tr = template.getTemplateRendition(RenditionType.STANDARD);
                if (tr == null) {
                    return null;
                }
            } catch (WebloggerException e) {
                return null;
            }
            lang = tr.getTemplateLanguage();
        }

        Renderer renderer = null;

        if (TemplateLanguage.VELOCITY.equals(lang)) {
            // standard velocity template
            try {
               renderer = new VelocityRenderer(template);
            } catch (ResourceNotFoundException ignored) {
                // Already logged in VelocityRenderer -- logging again here
                // would just duplicate that entry. renderer stays null,
                // which the caller already treats as "no renderer".
            } catch(Exception ex) {
                // some kind of exception so we don't have a renderer
                log.error("ERROR creating VelocityRenderer", ex);
            }
        }
        return renderer;
    }
}
