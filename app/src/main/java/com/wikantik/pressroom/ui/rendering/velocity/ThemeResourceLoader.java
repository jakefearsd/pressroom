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
/*
 * ThemeResourceLoader.java
 *
 * Created on June 28, 2005, 12:25 PM
 */

package com.wikantik.pressroom.ui.rendering.velocity;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.runtime.resource.loader.ResourceLoader;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.ThemeNotFoundException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import org.apache.velocity.util.ExtProperties;

/**
 * The ThemeResourceLoader is a Velocity template loader which loads templates
 * from shared themes.
 * 
 * @author Allen Gilliland
 */
public class ThemeResourceLoader extends ResourceLoader {

    /**
     * Named {@code logger}, not {@code log}, deliberately: Velocity's own
     * {@link ResourceLoader} base class declares {@code protected Logger log},
     * and renaming this field to {@code log} would shadow it. Every other class
     * in this codebase uses {@code log}; this one cannot.
     */
    private static final Logger logger = LoggerFactory.getLogger(ThemeResourceLoader.class);

    private Weblogger weblogger;

    /**
     * Velocity calls {@code commonInit} (which sets {@code rsvc}) and then
     * this. The facade comes from the engine's application attributes, set by
     * {@link PressroomVelocity#initialize}; a loader instantiated by Velocity
     * cannot be constructor-injected.
     *
     * @throws IllegalStateException if the engine carries no {@link Weblogger}
     */
    @Override
    public void init(ExtProperties configuration) {
        logger.debug("{}", configuration);
        this.weblogger = PressroomResourceLoader.facadeFrom(rsvc);
    }

    /**
     * @throws ResourceNotFoundException
     */
    @Override
    public Reader getResourceReader(String name, String encoding) {

        logger.debug("Looking for: {}", name);

        if (name == null || name.length() < 1) {
            throw new ResourceNotFoundException(
                    "Need to specify a template name!");
        }

        // Velocity passes a NULL encoding from ResourceLoader.resourceExists(),
        // which ResourceManagerImpl.refreshResource() calls to work out which
        // loader owns a cached resource. Unreachable while this loader had
        // caching switched off; with caching on it is reached constantly, and
        // String.getBytes(null) throws NPE -- which surfaced as a renderer that
        // could not be created and an intermittent 404 on a page that was fine.
        String charset = (encoding == null || encoding.isEmpty())
                ? StandardCharsets.UTF_8.name() : encoding;

        RenditionType renditionType = RenditionType.STANDARD;
        if (name.contains("|")) {
            String[] pair = name.split("\\|");
            name = pair[0];
            renditionType = RenditionType.valueOf(pair[1].toUpperCase(Locale.ROOT));
        }

        try {
            // parse the name ... theme template names are
            // <theme>:<template>|<renditionType> (e.g., mytheme:mytemplate|standard)
            String[] split = name.split(":", 2);
            if (split.length < 2) {
                throw new ResourceNotFoundException("Invalid ThemeRL key "
                        + name);
            }

            // lookup the template from the proper theme
            ThemeManager themeMgr = weblogger.getThemeManager();
            Theme theme = themeMgr.getTheme(split[0]);
            ThemeTemplate template = theme.getTemplateByName(split[1]);

            if (template == null) {
                throw new ResourceNotFoundException("Template [" + split[1]
                        + "] doesn't seem to be part of theme [" + split[0]
                        + "]");
            }

            final String contents;

            if (template.getTemplateRendition(renditionType) != null) {
                contents = template.getTemplateRendition(renditionType).getTemplate();
            } else {
                throw new ResourceNotFoundException("Rendering [" + renditionType.name()
                        + "] of Template [" + split[1] + "] not found.");
            }

            logger.debug("Resource found!");

            // return the input stream
            return new InputStreamReader(new ByteArrayInputStream(contents.getBytes(charset)), charset);

        } catch (UnsupportedEncodingException uex) {
            // We expect UTF-8 in all JRE installation.
            // This rethrows as a Runtime exception after logging.
            logger.error("Unsupported encoding", uex);
            throw new RuntimeException(uex);

        } catch (ThemeNotFoundException tnfe) {
            String msg = "ThemeResourceLoader Error: " + tnfe.getMessage();
            logger.error(msg, tnfe);
            throw new ResourceNotFoundException(msg, tnfe);

        } catch (WebloggerException re) {
            String msg = "PressroomResourceLoader Error: " + re.getMessage();
            logger.error(msg, re);
            throw new ResourceNotFoundException(msg, re);
        }
    }

    /**
     * Files loaded by this resource loader are not reloadable here, as they are
     * stored in shared themes and there is no way velocity can trigger a
     * reload.
     * 
     * @see org.apache.velocity.runtime.resource.loader.ResourceLoader#isSourceModified(org.apache.velocity.runtime.resource.Resource)
     */
    @Override
    public boolean isSourceModified(Resource resource) {
        long current = getLastModified(resource);
        // 0 means "could not tell" -- an unresolvable theme, or one with no
        // timestamp. Answer "modified" there rather than "unchanged": unchanged
        // pins whatever is in the cache for the life of the JVM, while modified
        // costs one re-parse and lets the real error surface.
        return current == 0L || current > resource.getLastModified();
    }

    /**
     * @see org.apache.velocity.runtime.resource.loader.ResourceLoader#getLastModified(org.apache.velocity.runtime.resource.Resource)
     */
    @Override
    public long getLastModified(Resource resource) {
        if (resource == null || resource.getName() == null) {
            return 0L;
        }
        // Template keys are "<theme>:<template>|<rendition>"; only the theme
        // half carries a timestamp, and it is the theme as a whole that
        // ThemeManager.reLoadThemeFromDisk swaps out.
        String name = resource.getName();
        int pipe = name.indexOf('|');
        if (pipe >= 0) {
            name = name.substring(0, pipe);
        }
        int colon = name.indexOf(':');
        if (colon < 1) {
            return 0L;
        }
        try {
            Theme theme = weblogger.getThemeManager().getTheme(name.substring(0, colon));
            Date modified = theme == null ? null : theme.getLastModified();
            return modified == null ? 0L : modified.getTime();
        } catch (WebloggerException | RuntimeException ex) {
            // Deliberately not fatal and deliberately not "unchanged": this is
            // only ever a cache-freshness question, and isSourceModified turns
            // the 0 into "re-parse", which routes the real failure through
            // getResourceReader where it is already handled.
            logger.debug("Could not read the last-modified time of {}", name, ex);
            return 0L;
        }
    }

}
