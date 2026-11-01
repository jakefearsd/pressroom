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
package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.util.URLUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the site root {@code /} shows; {@code index.jsp} forwards to
 * {@link #forwardPath}. Three choices, in order:
 *
 * <ol>
 *   <li>{@code site.frontpage.page} set: that site page when it is published,
 *       else the fallback below. A front door whose page was deleted or
 *       unpublished is the fallback, never a 404 and never the blog the admin
 *       moved away from.</li>
 *   <li>{@code site.frontpage.weblog.handle} set: that blog, exactly as
 *       {@code index.jsp} forwarded before site pages existed.</li>
 *   <li>Neither: the fallback.</li>
 * </ol>
 *
 * <p>The fallback is the discovery front page -- the site space's
 * {@value #DISCOVERY_TEMPLATE} template, rendered by the page servlet -- when
 * the tier is bootstrapped and the site space exists, else the site
 * directory. So before bootstrap (when every runtime property reads null)
 * {@code /} is the directory without asking for a tier that is not there.
 */
public final class FrontDoor {

    private static final Logger log = LoggerFactory.getLogger(FrontDoor.class);

    static final String DIRECTORY = "/pressroom-ui/siteDirectory.rol";
    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page/";

    /**
     * The site space's template that renders the discovery front page. The
     * page servlet serves it, under the site space's handle, only to this
     * class's forward; the request mapper never routes it, so {@code /} is
     * its one address.
     */
    public static final String DISCOVERY_TEMPLATE = "discovery";

    static final String DISCOVERY = PAGE_SERVLET + Weblog.SITE_SPACE_HANDLE + "/page/" + DISCOVERY_TEMPLATE;

    private FrontDoor() {
    }

    /** As {@link #forwardPath(Weblogger)}, asking the provider for its tier only once it has one. */
    public static String forwardPath(WebloggerProvider provider) {
        return forwardPath(provider.isBootstrapped() ? provider.getWeblogger() : null);
    }

    /**
     * The dispatcher path for {@code /}. Never throws: a lookup that fails
     * falls back (see the class comment).
     *
     * @param tier the business tier, or null before bootstrap
     */
    public static String forwardPath(Weblogger tier) {
        String slug = WebloggerRuntimeConfig.getFrontDoorPageSlug();
        if (slug != null) {
            // A locale-shaped slug (saved before such slugs were refused) would
            // be read as a locale by the page servlet and 404: fall back.
            return !LocaleSegment.isLocale(slug) && canShowSitePage(tier, slug)
                    ? PAGE_SERVLET + Weblog.SITE_SPACE_HANDLE + "/" + URLUtilities.encode(slug)
                    : fallback(tier);
        }
        String handle = WebloggerRuntimeConfig.getProperty("site.frontpage.weblog.handle");
        if (handle != null && !handle.isBlank()) {
            return PAGE_SERVLET + handle;
        }
        return fallback(tier);
    }

    /** The discovery page when there is a site space to render it in, else the directory. */
    private static String fallback(Weblogger tier) {
        if (tier == null) {
            return DIRECTORY;
        }
        try {
            return tier.getWeblogManager().getSiteSpace() != null ? DISCOVERY : DIRECTORY;
        } catch (Exception ex) {
            // A display decision, like canShowSitePage's: the directory needs
            // no site space, so it is the safe answer when that lookup fails.
            log.warn("Could not look up the site space; showing the site directory", ex);
            return DIRECTORY;
        }
    }

    private static boolean canShowSitePage(Weblogger tier, String slug) {
        if (tier == null) {
            return false;
        }
        try {
            return isPublishedSitePage(tier, slug);
        } catch (Exception ex) {
            // A display decision: the fallback is always a safe thing to show
            // at /, and a page that could not be looked up is not one to forward to.
            log.warn("Could not look up the front door's site page '{}'; showing the fallback",
                    slug, ex);
            return false;
        }
    }

    /**
     * Whether the site space has a published page with this slug: the one rule
     * for what {@code /} and {@code /<slug>} may serve (a draft holds its slug
     * but is never served). Both lookups are remembered by the business tier
     * -- the site space or its absence, the published pages by id -- so a warm
     * answer costs no query, which matters to the mapper: it asks on every
     * single-segment url no blog claims.
     */
    static boolean isPublishedSitePage(Weblogger tier, String slug) throws WebloggerException {
        Weblog site = tier.getWeblogManager().getSiteSpace();
        if (site == null) {
            return false;
        }
        for (WeblogPage page : tier.getWeblogPageManager().getPublishedPages(site)) {
            if (slug.equals(page.getSlug())) {
                return true;
            }
        }
        return false;
    }
}
