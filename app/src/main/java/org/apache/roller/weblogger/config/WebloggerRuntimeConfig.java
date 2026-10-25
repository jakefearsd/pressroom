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

package org.apache.roller.weblogger.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefs;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefsParser;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;


/**
 * This class acts as a convenience gateway for getting property values
 * via the PropertiesManager.  We do this because most calls to the
 * PropertiesManager are just to get the value of a specific property and
 * thus the caller doesn't need the full RuntimeConfigProperty object.
 * 
 * We also provide some methods for converting to different data types.
 */
public final class WebloggerRuntimeConfig {
    
    private static final Logger log = LoggerFactory.getLogger(WebloggerRuntimeConfig.class);
    
    private static String RUNTIME_CONFIG = "/org/apache/roller/weblogger/config/runtimeConfigDefs.xml";

    // Lazily parsed once per JVM and read from many request threads
    // thereafter. `configDefs` is volatile so a completed parse is fully
    // visible to every reader (LI_LAZY_INIT_STATIC), and CONFIG_DEFS_LOCK is
    // a private lock object -- not this class's own intrinsic lock -- so
    // nothing outside this file can contend on or block the parse
    // (NonThreadSafeSingleton).
    private static volatile RuntimeConfigDefs configDefs = null;
    private static final Object CONFIG_DEFS_LOCK = new Object();
    // Same reasoning for the compare-and-clear in detach(PropertiesManager):
    // a private lock, not the class's own (USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION).
    private static final Object ATTACH_LOCK = new Object();
    
    // special case for our context urls
    private static String relativeContextURL = null;
    private static String absoluteContextURL = null;

    /**
     * The one business-tier reference this static facade still holds, and the
     * deliberate residual of the 2026-08-22 static-service-locator wave (spec
     * Decision 8): the runtime-config subsystem used to reach the whole tier
     * through the static locator ({@code getWeblogger().getPropertiesManager()})
     * from this one line; now it holds exactly the properties manager the
     * provider attached at bootstrap, and nothing else. {@code null} until
     * {@link #attach} -- every read then answers {@code null}, which is the
     * pre-bootstrap behaviour this class always had. Stage 2 of the same
     * program (configuration as beans) retires this field along with the
     * static readers; {@code StaticServiceLocatorTest.STATIC_RESIDUALS} names
     * it until then.
     */
    private static volatile PropertiesManager propertiesManager;


    // prevent instantiations
    private WebloggerRuntimeConfig() {}


    /**
     * Points the static readers at the bootstrapped tier's properties manager.
     * Called by {@code SpringWebloggerProvider.bootstrap()} and by the test
     * fixtures ({@code MockWeblogger}, {@code RuntimeConfigAttachment}) only --
     * it is public because those callers live in other packages, not because
     * anything else should call it.
     *
     * @return whatever was attached before, so a fixture can restore it
     */
    public static PropertiesManager attach(PropertiesManager manager) {
        PropertiesManager previous = propertiesManager;
        propertiesManager = manager;
        return previous;
    }

    /**
     * Forgets the attached properties manager; reads answer {@code null} again.
     * Called by the test fixtures.
     */
    public static void detach() {
        propertiesManager = null;
    }

    /**
     * Forgets the attached properties manager only if it is {@code expected} --
     * what a tier being torn down calls, so a context that never bootstrapped
     * (or a second tier in the same JVM) cannot clear an attachment it did not
     * make. Returns whether anything was cleared.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // identity IS the contract: only the very
    // instance that was attached may clear it; equals() on a manager (or a mock of one) says less
    public static boolean detach(PropertiesManager expected) {
        synchronized (ATTACH_LOCK) {
            if (expected != null && propertiesManager == expected) {
                propertiesManager = null;
                return true;
            }
            return false;
        }
    }


    /**
     * Retrieve a single property from the PropertiesManager ... returns null
     * if there is an error, or if no properties manager has been attached yet
     * (the tier is not bootstrapped).
     **/
    public static String getProperty(String name) {

        String value = null;

        PropertiesManager pmgr = propertiesManager;
        if (pmgr != null) {
            try {
                RuntimeConfigProperty prop = pmgr.getProperty(name);
                if(prop != null) {
                    value = prop.getValue();
                }
            } catch(Exception e) {
                log.warn("Trouble accessing property: {}", name, e);
            }
        }

        log.debug("fetched property [{}={}]", name, value);

        return value;
    }
    
    
    /**
     * Retrieve a property, checking the runtime (database) config first and
     * falling through to the static (roller.properties / environment)
     * config when unset or blank -- the same two-step lookup {@link
     * #getBooleanProperty} already applies below, generalised for callers
     * that need the raw string rather than a boolean. {@code
     * site.absoluteurl} is the motivating caller: it is a runtime property
     * an operator can also set via {@code ROLLER_SITE_ABSOLUTEURL} (which
     * lands in the static config, not the database row), and callers that
     * need to know "is this actually configured, by either route" must
     * check both rather than reimplementing this fallback each time.
     */
    public static String getPropertyWithConfigFallback(String name) {
        String value = getProperty(name);
        if (value == null || value.isBlank()) {
            value = WebloggerConfig.getProperty(name);
        }
        return value;
    }


    /**
     * Retrieve a property as a boolean ... defaults to false if there is an error
     **/
    public static boolean getBooleanProperty(String name) {

        // check runtime config first, then fall through to static config
        String value = getProperty(name);
        if (value == null) {
            value = WebloggerConfig.getProperty(name);
        }

        return value != null && Boolean.parseBoolean(value);
    }
    
    
    /**
     * Retrieve a property as an int ... defaults to -1 if there is an error
     **/
    public static int getIntProperty(String name) {
        
        // get the value first, then convert
        String value = getProperty(name);
        
        if (value == null) {
            return -1;
        }
        
        int intval = -1;
        try {
            intval = Integer.parseInt(value);
        } catch(Exception e) {
            log.warn("Trouble converting to int: {}", name, e);
        }
        
        return intval;
    }
    
    
    public static RuntimeConfigDefs getRuntimeConfigDefs() {

        // Double-checked locking, correct per JMM because configDefs is
        // volatile: the outer check avoids the lock on every call once
        // parsed, the inner check avoids a duplicate parse from a thread
        // that lost the race to CONFIG_DEFS_LOCK.
        if (configDefs == null) {
            synchronized (CONFIG_DEFS_LOCK) {
                if (configDefs == null) {

                    // unmarshall the config defs file
                    try (InputStream is =
                            WebloggerRuntimeConfig.class.getResourceAsStream(RUNTIME_CONFIG)) {

                        RuntimeConfigDefsParser parser = new RuntimeConfigDefsParser();
                        configDefs = parser.unmarshall(is);

                    } catch (Exception e) {
                        // error while parsing :(
                        log.error("Error parsing runtime config defs", e);
                    }
                }
            }
        }

        return configDefs;
    }
    
    
    /**
     * Get the runtime configuration definitions XML file as a string.
     *
     * This is basically a convenience method for accessing this file.
     * The file itself contains meta-data about what configuration
     * properties we change at runtime via the UI and how to setup
     * the display for editing those properties.
     */
    public static String getRuntimeConfigDefsAsString() {
        
        log.debug("Trying to load runtime config defs file");
        
        try {
            StringWriter configString = new StringWriter();
            try (InputStreamReader reader = new InputStreamReader(
                    WebloggerConfig.class.getResourceAsStream(RUNTIME_CONFIG), StandardCharsets.UTF_8)) {
                reader.transferTo(configString);
            }
            return configString.toString();
        } catch(Exception e) {
            log.error("Error loading runtime config defs file", e);
        }
        
        return "";
    }
    
    
    /**
     * Special method which sets the non-persisted absolute url to this site.
     *
     * This property is *not* persisted in any way.
     */
    public static void setAbsoluteContextURL(String url) {
        absoluteContextURL = url;
    }
    
    
    /**
     * Get the absolute url to this site.
     *
     * <p>Precedence: the {@code site.absoluteurl} database row (Admin ->
     * Global Config) when non-blank, then the static config -- where
     * {@code ROLLER_SITE_ABSOLUTEURL} lands -- when non-blank, and only then
     * the non-persisted value InitFilter latched from the first request after
     * boot. The row alone is not enough: its default is blank, so reading only
     * the row silently ignored the environment route the deployment runbook
     * documents, and every generated url took whichever host hit first.
     *
     * <p>A configured value loses any trailing slash: every caller appends
     * "/" plus a path, so {@code https://host/} produced {@code https://host//path},
     * which the site answers with 400.
     */
    public static String getAbsoluteContextURL() {

        String absURL = getPropertyWithConfigFallback("site.absoluteurl");
        if(absURL != null && !absURL.isBlank()) {
            return StringUtils.stripEnd(absURL, "/");
        }
        
        return absoluteContextURL;
    }
    
    
    /**
     * Special method which sets the non-persisted relative url to this site.
     *
     * This property is *not* persisted in any way.
     */
    public static void setRelativeContextURL(String url) {
        relativeContextURL = url;
    }
    
    
    public static String getRelativeContextURL() {
        return relativeContextURL;
    }
    
    
    /**
     * Convenience method for Roller classes trying to determine if a given
     * weblog handle represents the front page blog.
     */
    public static boolean isFrontPageWeblog(String weblogHandle) {

        String frontPageHandle = getProperty("site.frontpage.weblog.handle");

        // Null when no front page has been chosen yet, which is the state of
        // every installation between first boot and someone visiting
        // /roller-ui/setup. Comparing handle-first turned that into an NPE on
        // the cache-invalidation path, so editing a template on a fresh install
        // failed with a 500 rather than simply finding no front-page weblog.
        return java.util.Objects.equals(frontPageHandle, weblogHandle);
    }
    
    
    /**
     * Convenience method for Roller classes trying to determine if a given
     * weblog handle represents the front page blog configured to render
     * site-wide data.
     */
    public static boolean isSiteWideWeblog(String weblogHandle) {
        
        boolean siteWide = getBooleanProperty("site.frontpage.weblog.aggregated");
        
        return isFrontPageWeblog(weblogHandle) && siteWide;
    }
    
    /**
     * The slug of the site page chosen as the front door
     * ({@code site.frontpage.page}), trimmed, or null when it is unset or blank.
     */
    public static String getFrontDoorPageSlug() {
        String slug = getProperty("site.frontpage.page");
        return slug == null || slug.isBlank() ? null : slug.trim();
    }


    /**
     * Whether {@code slug} names the front door's site page. The one
     * comparison the URL rule ({@code <ctx>/} for that page), the mapper's
     * 301 from its slug to {@code /} and {@code FrontDoor} all use: the
     * property trimmed, the slug as given, exact case. Were they to disagree,
     * the 301 would send a reader to a root that does not show the page.
     */
    public static boolean isFrontDoorPage(String slug) {
        return slug != null && slug.equals(getFrontDoorPageSlug());
    }
}
