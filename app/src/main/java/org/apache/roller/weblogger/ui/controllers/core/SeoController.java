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
package org.apache.roller.weblogger.ui.controllers.core;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.URLUtilities;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the crawler-facing SEO surface: {@code /robots.txt}, the sitemap
 * index at {@code /sitemap.xml}, and one sitemap per weblog at
 * {@code /sitemap-<handle>.xml}.
 *
 * <p>Everything is generated per request, uncached: at this installation's
 * scale (10-50 weblogs, a few hundred entries each) regenerating the XML is
 * cheaper than wiring these documents into the render-cache invalidation
 * machinery.
 *
 * <p>The per-weblog sitemap lists the weblog home page plus every PUBLISHED
 * entry and PUBLISHED page that is not flagged {@code noindex} ({@code null}
 * on pre-existing rows means indexable). A page's {@code loc} is encoded the
 * same way {@code URLModel#staticPage} builds it, since that is the URL the
 * theme's own nav emits for the page. Entries with a featured image get a
 * Google image-sitemap {@code image:image} element whose {@code loc} is the
 * media-resource URL of the largest rendition ({@code ?w=1600}); before
 * renditions exist for a file, MediaResourceServlet serves the original for
 * that URL, so the link is valid either way.
 *
 * <p>Routing: the DispatcherServlet is mapped {@code *.rol} plus the explicit
 * SEO patterns added in {@code ServletRegistrationConfig} -- see
 * {@code ServletRegistrationConfig#SEO_URL_PATTERNS} for why the per-weblog
 * sitemaps ride on a {@code *.xml} extension mapping.
 *
 * <p>{@code /robots.txt} and {@code /sitemap.xml} are host-aware: on a
 * request whose {@code Host} header names a weblog's custom domain, they
 * describe THAT weblog rather than the whole site (see {@link #robots} and
 * {@link #sitemapIndex}).
 */
@Controller
public class SeoController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(SeoController.class);

    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String IMAGE_NS = "http://www.google.com/schemas/sitemap-image/1.1";
    private static final MediaType APPLICATION_XML_UTF8 =
            new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8);

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    /**
     * Allow-all robots policy that advertises the sitemap. On a custom
     * domain this advertises THAT weblog's own sitemap -- crawlers arriving
     * at a virtual host must be pointed at a document that host is actually
     * allowed to serve; the site index lives elsewhere and, per the sitemap
     * protocol, may only ever reference sitemaps on its own host (see
     * {@link #sitemapIndex}).
     */
    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots(HttpServletRequest request) {
        String vhostHost = VirtualHostRegistry.normalise(request.getHeader("Host"));
        String vhostHandle = weblogger.getVirtualHostRegistry().handleFor(request.getHeader("Host"));
        // Same webapp context reached under many hostnames -- the custom
        // domain still needs the context path, or a deployment under a
        // prefix advertises a sitemap url that 404s on that same deployment.
        String sitemapUrl = vhostHandle != null
                ? "https://" + vhostHost + WebloggerRuntimeConfig.getRelativeContextURL() + "/sitemap.xml"
                : absoluteContextURL() + "/sitemap.xml";
        String body = "User-agent: *\n"
                + "Disallow:\n"
                + "\n"
                + "Sitemap: " + sitemapUrl + "\n";
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN,
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * Sitemap index listing one sitemap per visible, active weblog on the
     * site host -- OR, on a custom domain, that weblog's own sitemap in
     * place of the index (see {@link #weblogSitemap}).
     *
     * <p>A weblog with its own custom domain is deliberately left out of the
     * site index: the sitemap protocol permits an index to reference only
     * sitemaps on its own host, so keeping such a weblog in the index would
     * produce a document that is invalid for exactly the entries most wanted
     * in the crawl. That weblog is discovered instead through its own host's
     * {@code robots.txt}.
     */
    @GetMapping("/sitemap.xml")
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "Every value written into the XML goes through escapeXml(); the URLs come from "
                    + "the configured absolute URL and weblog handles.")
    public ResponseEntity<String> sitemapIndex(HttpServletRequest request) {
        String vhostHandle = weblogger.getVirtualHostRegistry().handleFor(request.getHeader("Host"));
        if (vhostHandle != null) {
            return weblogSitemap(vhostHandle);
        }

        List<Weblog> weblogs;
        try {
            weblogs = weblogger.getWeblogManager()
                    .getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1);
        } catch (WebloggerException e) {
            log.error("Error listing weblogs for the sitemap index", e);
            return ResponseEntity.internalServerError().build();
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"").append(SITEMAP_NS).append("\">\n");
        for (Weblog weblog : weblogs) {
            String customDomain = weblog.getCustomDomain();
            if (customDomain != null && !customDomain.isBlank()) {
                continue;
            }
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(escapeXml(absoluteContextURL()
                    + "/sitemap-" + weblog.getHandle() + ".xml")).append("</loc>\n");
            appendLastmod(xml, "    ", weblog.getLastModified());
            xml.append("  </sitemap>\n");
        }
        xml.append("</sitemapindex>\n");
        return xmlResponse(xml);
    }

    /**
     * The sitemap protocol's own limit: a sitemap may list at most 50,000
     * URLs. A weblog with more needs a sitemap index of its own, which is a
     * feature to add when some weblog here is within sight of it.
     */
    static final int MAX_SITEMAP_URLS = 50_000;

    /** Sitemap for one weblog: home page plus published, indexable entries. */
    @GetMapping("/sitemap-{handle}.xml")
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "Every value written into the XML goes through escapeXml(); the URLs are built "
                    + "by the URL strategy from the stored weblog, entries and pages.")
    public ResponseEntity<String> weblogSitemap(@PathVariable("handle") String handle) {
        Weblog weblog;
        try {
            weblog = weblogger.getWeblogManager().getWeblogByHandle(handle, Boolean.TRUE);
        } catch (WebloggerException e) {
            // getWeblogByHandle throws for syntactically invalid handles
            // (crawlers probe all sorts of /sitemap-*.xml URLs) -- that is a
            // 404, not a server error.
            log.debug("Rejected sitemap request for handle {}", handle, e);
            return ResponseEntity.notFound().build();
        }
        if (weblog == null || !Boolean.TRUE.equals(weblog.getActive())) {
            return ResponseEntity.notFound().build();
        }

        List<WeblogEntry> entries;
        List<WeblogPage> pages;
        try {
            WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
            criteria.setWeblog(weblog);
            criteria.setStatus(PubStatus.PUBLISHED);
            // Bounded, because this is an anonymous endpoint that loads every
            // matching entry into memory and renders it. Unbounded it is a
            // free amplification lever for anyone who can spell the URL, and
            // the protocol caps a sitemap at 50,000 URLs anyway -- past that
            // the file is invalid, so fetching more would be work done to
            // produce something no crawler will accept.
            criteria.setMaxResults(MAX_SITEMAP_URLS);
            entries = weblogger.getWeblogEntryManager().getWeblogEntries(criteria);
            pages = weblogger.getWeblogPageManager().getPublishedPages(weblog);
        } catch (WebloggerException e) {
            log.error("Error building the sitemap for weblog {}", handle, e);
            return ResponseEntity.internalServerError().build();
        }

        URLStrategy urlStrategy = weblogger.getUrlStrategy();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"").append(SITEMAP_NS)
                .append("\" xmlns:image=\"").append(IMAGE_NS).append("\">\n");

        // the weblog home page
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(
                urlStrategy.getWeblogURL(weblog, null, true))).append("</loc>\n");
        appendLastmod(xml, "    ", weblog.getLastModified());
        xml.append("  </url>\n");

        for (WeblogEntry entry : entries) {
            if (Boolean.TRUE.equals(entry.getNoindex())) {
                continue;
            }
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(urlStrategy.getWeblogEntryURL(
                    weblog, null, entry.getAnchor(), true))).append("</loc>\n");
            appendLastmod(xml, "    ",
                    entry.getUpdateTime() != null ? entry.getUpdateTime() : entry.getPubTime());
            appendFeaturedImage(xml, urlStrategy, weblog, entry);
            xml.append("  </url>\n");
        }

        // Pages sit alongside entries in the sitemap: they are the addresses a
        // business site most wants indexed. noindex is honoured here for the
        // same reason it is for entries -- a page excluded from search must be
        // excluded from what we hand the crawler.
        for (WeblogPage page : pages) {
            if (Boolean.TRUE.equals(page.getNoindex())) {
                continue;
            }
            xml.append("  <url>\n");
            // Match URLModel#staticPage's encoding exactly: that is the same
            // /<handle>/<slug> URL the theme's own nav emits, and a slug is
            // free-form enough (spaces, punctuation) that the raw value is
            // not always a valid URL.
            xml.append("    <loc>").append(escapeXml(
                    urlStrategy.getWeblogURL(weblog, null, true)
                            + URLUtilities.encode(page.getSlug())))
                    .append("</loc>\n");
            appendLastmod(xml, "    ", page.getUpdated());
            xml.append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return xmlResponse(xml);
    }

    /**
     * Emits an {@code image:image} element for the entry's featured image, if
     * it has one that still resolves to a media file. A dangling id is skipped
     * silently: the sitemap must stay valid even when a referenced upload was
     * deleted after the entry was saved.
     */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "The image URL is built by the URL strategy from the media file's id and goes "
                    + "through escapeXml().")
    private void appendFeaturedImage(StringBuilder xml, URLStrategy urlStrategy,
            Weblog weblog, WeblogEntry entry) {
        String imageId = entry.getFeaturedImageId();
        if (imageId == null || imageId.isBlank()) {
            return;
        }
        MediaFile image = null;
        try {
            image = weblogger.getMediaFileManager().getMediaFile(imageId);
        } catch (WebloggerException e) {
            log.debug("Featured image {} of entry {} could not be loaded; omitting it from the sitemap",
                    imageId, entry.getAnchor(), e);
        }
        if (image == null) {
            return;
        }
        // A private directory's files 404 on the public media path and are
        // not served publicly at all, so advertising them to crawlers would
        // just dead-link.
        if (image.getDirectory() != null && image.getDirectory().isPrivate()) {
            return;
        }
        // Largest rendition of the pipeline's ladder; MediaResourceServlet
        // serves the original when no such rendition exists for the file.
        String imageURL = urlStrategy.getMediaFileURL(weblog, image.getId(), true) + "?w=1600";
        xml.append("    <image:image>\n");
        xml.append("      <image:loc>").append(escapeXml(imageURL)).append("</image:loc>\n");
        xml.append("    </image:image>\n");
    }

    private static ResponseEntity<String> xmlResponse(StringBuilder xml) {
        return ResponseEntity.ok()
                .contentType(APPLICATION_XML_UTF8)
                .body(xml.toString());
    }

    /** Appends a {@code lastmod} element in W3C datetime (UTC) if a date is known. */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "Writes only a date formatted by DateTimeFormatter.ISO_INSTANT, and a constant "
                    + "indent.")
    private static void appendLastmod(StringBuilder xml, String indent, Date date) {
        if (date == null) {
            return;
        }
        xml.append(indent).append("<lastmod>")
                .append(DateTimeFormatter.ISO_INSTANT.format(
                        java.time.Instant.ofEpochSecond(date.getTime() / 1000)))
                .append("</lastmod>\n");
    }

    private static String absoluteContextURL() {
        return WebloggerRuntimeConfig.getAbsoluteContextURL();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
