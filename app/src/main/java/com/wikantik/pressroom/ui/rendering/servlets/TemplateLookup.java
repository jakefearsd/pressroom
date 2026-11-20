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
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.StaticThemeTemplate;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * The theme-template lookups the rendering servlets repeat: a template the
 * theme may override by name with a shipped default behind it, and a
 * template by action. A failed lookup is logged and answered as "none", so the
 * caller's fallback decides what a lookup failure means.
 */
final class TemplateLookup {

    private static final Logger log = LoggerFactory.getLogger(TemplateLookup.class);

    private TemplateLookup() {
    }

    /**
     * The theme's template of that name, else the shipped one at
     * {@code fallbackVm}. "No override" is a null from the theme, not an
     * error; an exception is a real lookup failure, which falls back too but
     * leaves a trace for whoever wonders why the page looks generic.
     */
    static ThemeTemplate namedOr(Weblog weblog, ThemeManager themes, String name, String fallbackVm) {
        ThemeTemplate template = null;
        try {
            template = themes.getTheme(weblog).getTemplateByName(name);
        } catch (Exception e) {
            log.warn("Error looking up '{}' template for weblog {}; using the shared one",
                    name, weblog.getHandle(), e);
        }
        return template != null ? template
                : new StaticThemeTemplate(fallbackVm, TemplateLanguage.VELOCITY);
    }

    /** The theme's template for this action, or null when it has none or the lookup fails. */
    static ThemeTemplate byAction(Weblog weblog, ThemeManager themes, ComponentType action, String label) {
        try {
            return themes.getTheme(weblog).getTemplateByAction(action);
        } catch (Exception e) {
            log.error("Error getting weblog page for action '{}'", label, e);
            return null;
        }
    }
}
