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
package com.wikantik.pressroom.ui.controllers.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.business.SitemapEntry;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;
import org.springframework.http.HttpStatus;
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
 * <p>{@code robots.txt} and the index are generated per request, uncached:
 * they read one small list. The per-weblog sitemap is not -- regenerating it
 * on every crawl grew linearly with the weblog and was measured at 42% of the
 * application's CPU under load -- so it answers conditional GETs, is cached
 * per weblog and host against {@code weblog.lastModified} (see
 * {@link SitemapCache} for what that covers), and is rebuilt from a column
 * projection rather than from entities.
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

    private static final MediaType APPLICATION_XML_UTF8 =
            new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Per controller instance: Spring holds one, and a test's own instance
     * starts with an empty cache rather than inheriting another test's.
     */
    private final SitemapCache sitemapCache = SitemapCache.fromConfig();

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
    public ResponseEntity<String> sitemapIndex(HttpServletRequest request) {
        String vhostHandle = weblogger.getVirtualHostRegistry().handleFor(request.getHeader("Host"));
        if (vhostHandle != null) {
            return weblogSitemap(vhostHandle, request);
        }

        List<Weblog> weblogs;
        try {
            weblogs = weblogger.getWeblogManager()
                    .getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1);
        } catch (WebloggerException e) {
            log.error("Error listing weblogs for the sitemap index", e);
            return ResponseEntity.internalServerError().build();
        }

        SitemapXml xml = SitemapXml.index();
        for (Weblog weblog : weblogs) {
            String customDomain = weblog.getCustomDomain();
            if (customDomain != null && !customDomain.isBlank()) {
                continue;
            }
            xml.sitemap(absoluteContextURL() + "/sitemap-" + weblog.getHandle() + ".xml",
                    instant(weblog.getLastModified()));
        }
        try {
            Weblog site = weblogger.getWeblogManager().getSiteSpace();
            if (site != null && !weblogger.getWeblogPageManager().getPublishedPages(site).isEmpty()) {
                xml.sitemap(absoluteContextURL() + "/sitemap-site-pages.xml", null);
            }
        } catch (WebloggerException e) {
            // A display-path catch: the index is still valid without the site's entry.
            log.error("Error checking the site pages for the sitemap index", e);
        }
        return xmlResponse(xml);
    }

    /**
     * The sitemap protocol's own limit: a sitemap may list at most 50,000
     * URLs. A weblog with more needs a sitemap index of its own, which is a
     * feature to add when some weblog here is within sight of it.
     */
    static final int MAX_SITEMAP_URLS = 50_000;

    /** PageModel's canonical-URL allowlist; see {@link #canonicalElsewhere}. */
    private static final UrlValidator CANONICAL_URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    /**
     * Sitemap for one weblog: home page plus published, indexable entries and
     * pages.
     *
     * <p>Anonymous and crawled constantly, so it is cheap on every path: an
     * {@code If-Modified-Since} at or after the weblog's {@code lastModified}
     * is answered 304 before anything else is read; an unchanged weblog is
     * served from {@link SitemapCache}; and a rebuild reads the entries as
     * six columns in one query ({@code getSitemapEntries}) instead of as
     * whole entities with a media lookup per featured image. Nothing is
     * cached from a build that failed.
     */
    @GetMapping("/sitemap-{handle}.xml")
    public ResponseEntity<String> weblogSitemap(@PathVariable("handle") String handle,
            HttpServletRequest request) {
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
        // The hidden site space is never a blog: its pages are /sitemap-site-pages.xml.
        if (weblog == null || weblog.isSiteSpace() || !Boolean.TRUE.equals(weblog.getActive())) {
            return ResponseEntity.notFound().build();
        }

        Date lastModified = weblog.getLastModified();
        if (lastModified != null
                && ModDateHeaderUtil.isNotModifiedSince(request, lastModified.getTime())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .lastModified(lastModified.getTime())
                    .build();
        }

        URLStrategy urlStrategy = weblogger.getUrlStrategy();
        String weblogURL = urlStrategy.getWeblogURL(weblog, null, true);
        String xml = lastModified == null ? null
                : sitemapCache.get(weblog.getHandle(), weblogURL, lastModified.getTime());
        if (xml == null) {
            try {
                xml = buildWeblogSitemap(weblog, urlStrategy, weblogURL);
            } catch (WebloggerException e) {
                log.error("Error building the sitemap for weblog {}", handle, e);
                return ResponseEntity.internalServerError().build();
            }
            if (lastModified != null) {
                sitemapCache.put(weblog.getHandle(), weblogURL, lastModified.getTime(), xml);
            }
        }

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().contentType(APPLICATION_XML_UTF8);
        if (lastModified != null) {
            ok.lastModified(lastModified.getTime());
        }
        return ok.body(xml);
    }

    /**
     * Sitemap of the site's own pages (the hidden site space's PUBLISHED,
     * indexable pages), at the addresses the one URL rule gives them: the
     * front door's page once, at the root. The {@code -} keeps this literal
     * from ever being a blog handle; it wins over {@code /sitemap-{handle}.xml}.
     * 404 when there is no site space or nothing published to list. Not
     * cached: one small query, and the file is tiny.
     */
    @GetMapping("/sitemap-site-pages.xml")
    public ResponseEntity<String> siteSitemap(HttpServletRequest request) {
        try {
            Weblog site = weblogger.getWeblogManager().getSiteSpace();
            if (site == null) {
                return ResponseEntity.notFound().build();
            }
            List<WeblogPage> pages = weblogger.getWeblogPageManager().getPublishedPages(site);
            if (pages.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            SitemapXml xml = SitemapXml.urlset(false);
            appendPages(xml, pages, site, weblogger.getUrlStrategy());
            return xmlResponse(xml);
        } catch (WebloggerException e) {
            log.error("Error building the site pages sitemap", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String buildWeblogSitemap(Weblog weblog, URLStrategy urlStrategy, String weblogURL)
            throws WebloggerException {
        // Bounded, because this is an anonymous endpoint. Unbounded it is a
        // free amplification lever for anyone who can spell the URL, and the
        // protocol caps a sitemap at 50,000 URLs anyway -- past that the file
        // is invalid, so fetching more would be work done to produce
        // something no crawler will accept.
        List<SitemapEntry> entries = weblogger.getWeblogEntryManager()
                .getSitemapEntries(weblog, MAX_SITEMAP_URLS);
        List<WeblogPage> pages = weblogger.getWeblogPageManager().getPublishedPages(weblog);

        // With a front door the root IS the front page: listed once, as the
        // root (never again at its slug), and only while that page is
        // indexable; the post listing has moved to /posts.
        WeblogPage frontPage = frontPage(weblog, pages);

        SitemapXml xml = SitemapXml.urlset(true);
        // the weblog home page follows the urlset opening
        // A front door that resolves to no published page falls back to the
        // listing, whose canonical is /posts: the root is not listed then.
        boolean listRoot = !weblog.hasFrontDoor()
                || frontPage != null && isSubmitted(frontPage, weblogURL);
        if (listRoot) {
            appendWeblogUrl(xml, weblogURL, weblog);
        }
        if (weblog.hasFrontDoor()) {
            appendWeblogUrl(xml, urlStrategy.getWeblogCollectionURL(weblog, null, null, null, null, -1, true),
                    weblog);
        }

        for (SitemapEntry entry : entries) {
            String loc = urlStrategy.getWeblogEntryURL(weblog, null, entry.anchor(), true);
            if (entry.noindex() || canonicalElsewhere(entry.canonicalUrl(), loc)) {
                continue;
            }
            xml.url(loc, entry.lastmod(), featuredImageURL(urlStrategy, weblog, entry.publicImageId()));
        }

        // Pages sit alongside entries in the sitemap: they are the addresses a
        // business site most wants indexed. noindex is honoured here for the
        // same reason it is for entries -- a page excluded from search must be
        // excluded from what we hand the crawler.
        List<WeblogPage> otherPages = frontPage == null ? pages
                : pages.stream().filter(page -> !page.getId().equals(frontPage.getId())).toList();
        appendPages(xml, otherPages, weblog, urlStrategy);
        return xml.build();
    }

    /** One {@code url} for a weblog-level address (its root, its listing), dated by the weblog. */
    private static void appendWeblogUrl(SitemapXml xml, String loc, Weblog weblog) {
        xml.url(loc, instant(weblog.getLastModified()));
    }

    /** The blog's front page among its published pages, or null (no front door, or the id resolves to none). */
    private static WeblogPage frontPage(Weblog weblog, List<WeblogPage> pages) {
        if (!weblog.hasFrontDoor()) {
            return null;
        }
        return pages.stream().filter(page -> weblog.getFrontPageId().equals(page.getId()))
                .findFirst().orElse(null);
    }

    /** Whether a page at {@code loc} belongs in the sitemap: indexable and not canonical elsewhere. */
    private static boolean isSubmitted(WeblogPage page, String loc) {
        return !Boolean.TRUE.equals(page.getNoindex()) && !canonicalElsewhere(page.getCanonicalUrl(), loc);
    }

    /**
     * One {@code url} per page that is indexable and not canonical elsewhere.
     * The {@code loc} comes from the URL strategy, the one rule the theme's
     * own navigation uses (encoding, and the site's front door at the root).
     */
    private void appendPages(SitemapXml xml, List<WeblogPage> pages, Weblog weblog,
            URLStrategy urlStrategy) {
        for (WeblogPage page : pages) {
            String loc = urlStrategy.getWeblogStaticPageURL(weblog, page, true);
            if (!isSubmitted(page, loc)) {
                continue;
            }
            xml.url(loc, instant(page.getUpdated()));
        }
    }

    /**
     * Whether a stored canonical-URL override names an address other than
     * the item's own. Such an item is not this site's to submit: Search
     * Console reports it as "Submitted URL not selected as canonical". The
     * test for a real override is PageModel's, which emits it in the head: a
     * blank value or one that is not an absolute http(s) URL is no override.
     */
    private static boolean canonicalElsewhere(String canonicalUrl, String loc) {
        return StringUtils.isNotBlank(canonicalUrl)
                && CANONICAL_URL_VALIDATOR.isValid(canonicalUrl)
                && !sameAddress(canonicalUrl, loc);
    }

    /**
     * Whether two absolute URLs name the same page, the way an author writes a
     * self-canonical rather than the way the sitemap spells it: the scheme
     * (http or https), the host's case, a trailing slash and percent-encoding
     * in the path make no difference. Host, port, path and query otherwise
     * must match. A value that does not parse is compared as written.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Case-insensitive equality of a URL's scheme and host, which RFC 3986 defines as "
                    + "case-insensitive; the answer only decides whether an item is listed in its own sitemap, "
                    + "never access.")
    static boolean sameAddress(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            URI x = new URI(a);
            URI y = new URI(b);
            return x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost())
                    && effectivePort(x) == effectivePort(y)
                    && pathOf(x).equals(pathOf(y))
                    && Objects.equals(x.getRawQuery(), y.getRawQuery());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * The port, with the scheme's own default counted as none, so
     * http://h, http://h:80 and https://h:443 all agree.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Case-insensitive equality of a URL's scheme and host, which RFC 3986 defines as "
                    + "case-insensitive; the answer only decides whether an item is listed in its own sitemap, "
                    + "never access.")
    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        int schemeDefault = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return port == schemeDefault ? -1 : port;
    }

    private static String pathOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * Emits an {@code image:image} element for the entry's featured image.
     * The query has already resolved it: a dangling id, and a file in a
     * private directory (which 404s on the public media path, so advertising
     * it would only dead-link), arrive as null and are skipped -- the sitemap
     * must stay valid even when a referenced upload was deleted after the
     * entry was saved.
     */
    private static String featuredImageURL(URLStrategy urlStrategy, Weblog weblog, String publicImageId) {
        if (publicImageId == null) {
            return null;
        }
        // Largest rendition of the pipeline's ladder; MediaResourceServlet
        // serves the original when no such rendition exists for the file.
        return urlStrategy.getMediaFileURL(weblog, publicImageId, true) + "?w=1600";
    }

    private static ResponseEntity<String> xmlResponse(SitemapXml xml) {
        return ResponseEntity.ok()
                .contentType(APPLICATION_XML_UTF8)
                .body(xml.build());
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static String absoluteContextURL() {
        return WebloggerRuntimeConfig.getAbsoluteContextURL();
    }
}
