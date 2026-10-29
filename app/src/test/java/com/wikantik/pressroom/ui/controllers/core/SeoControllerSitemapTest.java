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

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityManager;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.eclipse.persistence.jpa.JpaEntityManager;
import org.eclipse.persistence.queries.DatabaseQuery;
import org.eclipse.persistence.sessions.Session;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-weblog sitemap over the real business tier, pinned byte for byte.
 *
 * <p>{@link #theSitemapIsByteIdenticalToTheDocumentBuiltFromFullEntities} is
 * a CHARACTERISATION test: it was written before the sitemap stopped loading
 * full {@code WeblogEntry} entities and resolving each featured image with
 * its own lookup, and it passed against that old code unchanged. Its job is
 * to prove the cheaper query and the cache changed nothing a crawler can see.
 */
class SeoControllerSitemapTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/roller";
    private static final String HANDLE = "seositemapblog";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private SeoController controller;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");

        user = TestUtils.setupUser("seoSitemapUser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);

        controller = new SeoController();
        inject(controller, "weblogger", TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * CHARACTERISATION (see the class javadoc): passed against the old,
     * entity-loading implementation. Every case the sitemap distinguishes is
     * in one document: an entry without an image, one whose featured image is
     * in a public directory, one in a private directory, one whose image id
     * resolves to nothing, a noindex entry, a draft and a scheduled entry
     * (both excluded), an anchor that needs encoding, and published, noindex
     * and draft pages, one of whose slugs needs encoding. Entries are newest
     * first; pages follow in navigation order.
     */
    @Test
    void theSitemapIsByteIdenticalToTheDocumentBuiltFromFullEntities() throws Exception {
        MediaFile publicImage = TestUtils.setupImageMediaFile(weblog, "char-public.jpg");
        MediaFile privateImage = TestUtils.setupImageMediaFile(weblog, "char-private.jpg", "charprivdir");
        TestUtils.weblogger().getMediaFileManager().getMediaFile(privateImage.getId())
                .getDirectory().setPrivate(true);
        TestUtils.endSession(true);

        WeblogEntry plain = entry("plain-entry", PubStatus.PUBLISHED, 1, null, false);
        WeblogEntry withPublicImage = entry("public-image-entry", PubStatus.PUBLISHED, 2,
                publicImage.getId(), false);
        WeblogEntry withPrivateImage = entry("private-image-entry", PubStatus.PUBLISHED, 3,
                privateImage.getId(), false);
        WeblogEntry withMissingImage = entry("missing-image-entry", PubStatus.PUBLISHED, 4,
                "no-such-media-file-id", false);
        entry("noindex-entry", PubStatus.PUBLISHED, 5, publicImage.getId(), true);
        WeblogEntry ampersand = entry("fish-&-chips", PubStatus.PUBLISHED, 6, null, false);
        entry("draft-entry", PubStatus.DRAFT, 7, publicImage.getId(), false);
        entry("scheduled-entry", PubStatus.SCHEDULED, 8, publicImage.getId(), false);

        WeblogPage about = page("about", WeblogPage.PubStatus.PUBLISHED, false);
        WeblogPage qa = page("q&a", WeblogPage.PubStatus.PUBLISHED, false);
        page("hidden", WeblogPage.PubStatus.PUBLISHED, true);
        page("secret", WeblogPage.PubStatus.DRAFT, false);

        Date weblogLastModified = TestUtils.getManagedWebsite(weblog).getLastModified();
        String root = ABSOLUTE_CONTEXT + "/" + HANDLE + "/";
        String image = "    <image:image>\n"
                + "      <image:loc>" + root + "mediaresource/" + publicImage.getId()
                + "?w=1600</image:loc>\n"
                + "    </image:image>\n";
        String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""
                + " xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">\n"
                + url(root, weblogLastModified, "")
                + url(root + "entry/fish-%26-chips", updated(ampersand), "")
                + url(root + "entry/missing-image-entry", updated(withMissingImage), "")
                + url(root + "entry/private-image-entry", updated(withPrivateImage), "")
                + url(root + "entry/public-image-entry", updated(withPublicImage), image)
                + url(root + "entry/plain-entry", updated(plain), "")
                + url(root + "about", about.getUpdated(), "")
                + url(root + "q%26a", qa.getUpdated(), "")
                + "</urlset>\n";

        ResponseEntity<String> response = sitemap();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/xml;charset=UTF-8",
                String.valueOf(response.getHeaders().getContentType()));
        assertEquals(expected, response.getBody());
    }

    /**
     * An entry or page whose canonical override names another address is
     * not this site's to submit: Search Console reports it as "Submitted URL
     * not selected as canonical". It is left out. An override that names the
     * item's own address keeps it, and a value that is not an http(s) URL is
     * no override at all (PageModel ignores it in the head the same way).
     */
    @Test
    void anItemWhoseCanonicalPointsElsewhereIsLeftOut() throws Exception {
        String root = ABSOLUTE_CONTEXT + "/" + HANDLE + "/";
        WeblogEntry syndicated = entry("syndicated-entry", PubStatus.PUBLISHED, 1, null, false);
        WeblogEntry selfCanonical = entry("self-canonical-entry", PubStatus.PUBLISHED, 2, null, false);
        WeblogEntry bogusCanonical = entry("bogus-canonical-entry", PubStatus.PUBLISHED, 3, null, false);
        canonical(syndicated, "https://elsewhere.example.com/original");
        canonical(selfCanonical, root + "entry/self-canonical-entry");
        canonical(bogusCanonical, "javascript:alert(1)");
        WeblogPage movedPage = page("moved", WeblogPage.PubStatus.PUBLISHED, false);
        WeblogPage ownPage = page("own", WeblogPage.PubStatus.PUBLISHED, false);
        canonical(movedPage, "https://elsewhere.example.com/moved");
        canonical(ownPage, root + "own");

        String body = sitemap().getBody();

        assertFalse(body.contains("syndicated-entry"),
                "an entry canonical to another site must not be submitted:\n" + body);
        assertFalse(body.contains(root + "moved<"),
                "a page canonical to another site must not be submitted:\n" + body);
        assertTrue(body.contains("<loc>" + root + "entry/self-canonical-entry</loc>"),
                "a canonical naming the entry itself keeps it:\n" + body);
        assertTrue(body.contains("<loc>" + root + "entry/bogus-canonical-entry</loc>"),
                "a non-http canonical is no override:\n" + body);
        assertTrue(body.contains("<loc>" + root + "own</loc>"),
                "a canonical naming the page itself keeps it:\n" + body);
        assertFalse(body.contains("elsewhere.example.com"), body);
    }

    /**
     * A self-canonical written a little differently from the address the
     * sitemap builds -- a trailing slash, the other scheme, a host in
     * capitals, a percent-encoded character -- still names the item itself,
     * and the item stays in its own sitemap.
     */
    @Test
    void aSelfCanonicalSpelledDifferentlyStillKeepsTheItem() throws Exception {
        // Not localhost: the canonical validator refuses a local host, so a
        // localhost canonical is no override at all and would prove nothing.
        String context = "http://blog.example.com:8080/roller";
        WebloggerRuntimeConfig.setAbsoluteContextURL(context);
        try {
            String root = context + "/" + HANDLE + "/";
            WeblogEntry slash = entry("slash-entry", PubStatus.PUBLISHED, 1, null, false);
            WeblogEntry scheme = entry("scheme-entry", PubStatus.PUBLISHED, 2, null, false);
            WeblogEntry encoded = entry("encoded-entry", PubStatus.PUBLISHED, 3, null, false);
            WeblogEntry elsewhere = entry("elsewhere-entry", PubStatus.PUBLISHED, 4, null, false);
            canonical(slash, root + "entry/slash-entry/");
            canonical(scheme, "https://BLOG.example.com:8080/roller/" + HANDLE + "/entry/scheme-entry");
            canonical(encoded, root + "entry/%65ncoded-entry");
            canonical(elsewhere, root + "entry/some-other-entry");

            String body = sitemap().getBody();

            for (String anchor : new String[]{"slash-entry", "scheme-entry", "encoded-entry"}) {
                assertTrue(body.contains("<loc>" + root + "entry/" + anchor + "</loc>"),
                        anchor + ": a canonical naming the entry itself keeps it:\n" + body);
            }
            assertFalse(body.contains("elsewhere-entry"), "a different path is still elsewhere:\n" + body);
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        }
    }

    private void canonical(WeblogEntry entry, String url) throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setCanonicalUrl(url);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private void canonical(WeblogPage page, String url) throws Exception {
        WeblogPage managed = pages().getPageBySlug(TestUtils.getManagedWebsite(weblog), page.getSlug());
        managed.setCanonicalUrl(url);
        pages().savePage(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /**
     * The sitemap reads five columns of each entry; it must not build whole
     * entities (text, summary and every other column) to get them, nor look
     * each featured image up on its own. Before the fix this built a
     * {@code WeblogEntry} per published entry and ran a media lookup per
     * featured image, which made it 42% of the application's CPU under load.
     */
    @Test
    void buildingTheSitemapLoadsNoEntitiesAndOneEntryQueryWhateverTheImageCount() throws Exception {
        for (int i = 1; i <= 2; i++) {
            MediaFile image = TestUtils.setupImageMediaFile(weblog, "few-" + i + ".jpg");
            entry("few-" + i, PubStatus.PUBLISHED, i, image.getId(), false);
        }
        List<String> fewer = queriesWhileBuilding();

        for (int i = 1; i <= 3; i++) {
            MediaFile image = TestUtils.setupImageMediaFile(weblog, "more-" + i + ".jpg");
            entry("more-" + i, PubStatus.PUBLISHED, 10 + i, image.getId(), false);
        }
        List<String> more = queriesWhileBuilding();

        for (List<String> queries : List.of(fewer, more)) {
            assertFalse(queries.contains("entities:WeblogEntry"),
                    "no WeblogEntry entity may be built for the sitemap: " + queries);
            assertFalse(queries.contains("entities:MediaFile"),
                    "featured images must not be loaded one entity at a time: " + queries);
            assertEquals(1, queries.stream().filter(q -> q.contains("weblogentry")).count(),
                    "one query reads the entries and their images: " + queries);
        }
        // Excluding the site.absoluteurl read every absolute url makes
        // through WebloggerRuntimeConfig: an em.find served from the
        // persistence context, not a database round trip.
        assertEquals(withoutConfigReads(fewer), withoutConfigReads(more),
                "the queries must not grow with the number of featured images: "
                        + fewer + " vs " + more);
    }

    // --- C. cached ---

    /**
     * An unchanged weblog's sitemap is served from the cache: no entry query,
     * no page query, and the same document.
     */
    @Test
    void aRepeatRequestForAnUnchangedWeblogRunsNoEntryOrPageQuery() throws Exception {
        entry("cached-entry", PubStatus.PUBLISHED, 1, null, false);
        String first = sitemap().getBody();

        List<String> queries = queriesWhileBuilding();

        assertFalse(queries.stream().anyMatch(q -> q.contains("weblogentry")),
                "a repeat request must not query entries: " + queries);
        assertFalse(queries.contains("entities:WeblogPage"),
                "a repeat request must not query pages: " + queries);
        assertEquals(first, sitemap().getBody());
    }

    @Test
    void publishingAnEntryProducesAFreshSitemap() throws Exception {
        assertFalse(sitemap().getBody().contains("newly-published"), "fixture sanity check");

        entry("newly-published", PubStatus.PUBLISHED, 1, null, false);

        assertTrue(sitemap().getBody().contains("/entry/newly-published"));
    }

    @Test
    void updatingAPublishedEntryProducesAFreshSitemap() throws Exception {
        WeblogEntry entry = entry("soon-noindexed", PubStatus.PUBLISHED, 1, null, false);
        assertTrue(sitemap().getBody().contains("/entry/soon-noindexed"), "fixture sanity check");

        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setNoindex(Boolean.TRUE);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/entry/soon-noindexed"));
    }

    @Test
    void unpublishingAnEntryProducesAFreshSitemap() throws Exception {
        WeblogEntry entry = entry("soon-a-draft", PubStatus.PUBLISHED, 1, null, false);
        assertTrue(sitemap().getBody().contains("/entry/soon-a-draft"), "fixture sanity check");

        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setStatus(PubStatus.DRAFT);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/entry/soon-a-draft"),
                "an unpublished entry must leave the sitemap");
    }

    @Test
    void deletingAnEntryProducesAFreshSitemap() throws Exception {
        WeblogEntry entry = entry("soon-deleted", PubStatus.PUBLISHED, 1, null, false);
        assertTrue(sitemap().getBody().contains("/entry/soon-deleted"), "fixture sanity check");

        entries().removeWeblogEntry(TestUtils.getManagedWeblogEntry(entry));
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/entry/soon-deleted"));
    }

    @Test
    void trashingAnEntryProducesAFreshSitemap() throws Exception {
        WeblogEntry entry = entry("soon-trashed", PubStatus.PUBLISHED, 1, null, false);
        assertTrue(sitemap().getBody().contains("/entry/soon-trashed"), "fixture sanity check");

        entries().trashWeblogEntry(TestUtils.getManagedWeblogEntry(entry));
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/entry/soon-trashed"));
    }

    @Test
    void editingAStaticPageProducesAFreshSitemap() throws Exception {
        assertFalse(sitemap().getBody().contains("/contact<"), "fixture sanity check");

        page("contact", WeblogPage.PubStatus.PUBLISHED, false);

        assertTrue(sitemap().getBody().contains("/contact</loc>"));
    }

    @Test
    void unpublishingAStaticPageProducesAFreshSitemap() throws Exception {
        WeblogPage page = page("leaving", WeblogPage.PubStatus.PUBLISHED, false);
        assertTrue(sitemap().getBody().contains("/leaving</loc>"), "fixture sanity check");

        page.setStatus(WeblogPage.PubStatus.DRAFT);
        pages().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/leaving</loc>"));
    }

    @Test
    void deletingAStaticPageProducesAFreshSitemap() throws Exception {
        WeblogPage page = page("doomed", WeblogPage.PubStatus.PUBLISHED, false);
        assertTrue(sitemap().getBody().contains("/doomed</loc>"), "fixture sanity check");

        pages().removePage(pages().getPageBySlug(TestUtils.getManagedWebsite(weblog), "doomed"));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("/doomed</loc>"));
    }

    /**
     * A document over {@code cache.sitemap.maxBytes} is still served, with
     * {@code Last-Modified} and 304s -- it is only rebuilt every time rather
     * than held in memory.
     */
    @Test
    void anOversizedSitemapIsServedAndRebuiltButNotCached() throws Exception {
        entry("big-entry", PubStatus.PUBLISHED, 1, null, false);
        long lastModified = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();
        try (StaticConfigOverride _ = StaticConfigOverride.set("cache.sitemap.maxBytes", "100")) {
            controller = new SeoController();
            inject(controller, "weblogger", TestUtils.weblogger());
            ResponseEntity<String> first = sitemap();

            List<String> queries = queriesWhileBuilding();

            assertEquals(200, first.getStatusCode().value());
            assertTrue(first.getBody().contains("/entry/big-entry"));
            assertEquals(lastModified / 1000 * 1000, first.getHeaders().getLastModified());
            assertEquals(1, queries.stream().filter(q -> q.contains("weblogentry")).count(),
                    "an oversized document is rebuilt on every request: " + queries);
            assertEquals(304, controller.weblogSitemap(HANDLE, ifModifiedSince(lastModified))
                    .getStatusCode().value());
        }
    }

    /**
     * The private-directory toggle saves the weblog
     * ({@code MediaFileViewController.togglePrivate}, pinned in its own
     * test) so that this, and every rendered gallery, expires.
     */
    @Test
    void aFeaturedImageTurningPrivateProducesAFreshSitemap() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "soon-private.jpg", "soonprivdir");
        entry("pictured", PubStatus.PUBLISHED, 1, image.getId(), false);
        assertTrue(sitemap().getBody().contains("<image:loc>"), "fixture sanity check");

        TestUtils.weblogger().getMediaFileManager().getMediaFile(image.getId())
                .getDirectory().setPrivate(true);
        weblogs().saveWeblog(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);

        assertFalse(sitemap().getBody().contains("<image:loc>"));
    }

    @Test
    void changingTheCustomDomainProducesAFreshSitemap() throws Exception {
        assertTrue(sitemap().getBody().contains("<loc>" + ABSOLUTE_CONTEXT + "/" + HANDLE + "/</loc>"),
                "fixture sanity check");
        try {
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            managed.setCustomDomain("sitemap.example.com");
            weblogs().saveWeblog(managed);
            TestUtils.endSession(true);

            assertTrue(sitemap().getBody().contains("<loc>https://sitemap.example.com/roller/</loc>"));
        } finally {
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            managed.setCustomDomain(null);
            weblogs().saveWeblog(managed);
            TestUtils.endSession(true);
            TestUtils.weblogger().getVirtualHostRegistry().invalidate();
        }
    }

    /**
     * The site's absolute url changes no weblog's lastModified, so the cache
     * key must carry the url the document is built for.
     */
    @Test
    void anotherAbsoluteSiteUrlGetsItsOwnSitemap() throws Exception {
        assertTrue(sitemap().getBody().contains("<loc>" + ABSOLUTE_CONTEXT + "/" + HANDLE + "/</loc>"),
                "fixture sanity check");
        try {
            WebloggerRuntimeConfig.setAbsoluteContextURL("https://moved.example.com");

            assertTrue(sitemap().getBody().contains("<loc>https://moved.example.com/" + HANDLE + "/</loc>"));
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        }
    }

    // --- D. conditional GET ---

    @Test
    void theSitemapCarriesTheWeblogsLastModified() throws Exception {
        long lastModified = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();

        ResponseEntity<String> response = sitemap();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(lastModified / 1000 * 1000, response.getHeaders().getLastModified());
    }

    @Test
    void anIfModifiedSinceAtOrAfterLastModifiedIsAnswered304WithNoBody() throws Exception {
        long lastModified = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();

        for (long since : new long[] {lastModified / 1000 * 1000, lastModified + 60_000}) {
            ResponseEntity<String> response = controller.weblogSitemap(HANDLE, ifModifiedSince(since));

            assertEquals(304, response.getStatusCode().value(), "If-Modified-Since " + since);
            assertNull(response.getBody(), "a 304 carries no body");
            assertEquals(lastModified / 1000 * 1000, response.getHeaders().getLastModified());
        }
    }

    @Test
    void anOlderIfModifiedSinceGetsTheFullSitemap() throws Exception {
        long lastModified = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();

        ResponseEntity<String> response = controller.weblogSitemap(HANDLE,
                ifModifiedSince(lastModified - 1000));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<urlset"));
    }

    @Test
    void anUnparseableIfModifiedSinceGetsTheFullSitemap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-Modified-Since", "not a date");

        ResponseEntity<String> response = controller.weblogSitemap(HANDLE, request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<urlset"));
    }

    // --- support ---

    private static MockHttpServletRequest ifModifiedSince(long millis) {
        HttpHeaders headers = new HttpHeaders();
        headers.setIfModifiedSince(millis);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-Modified-Since", headers.getFirst("If-Modified-Since"));
        return request;
    }

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    /**
     * Every query the sitemap request executes, described as
     * {@code entities:<Class>} for a query that builds entity objects, or as
     * its lower-cased SQL / reference class otherwise.
     */
    private List<String> queriesWhileBuilding() throws Exception {
        TestUtils.endSession(true);
        EntityManager em = strategy().getEntityManager(false);
        Session session = em.unwrap(JpaEntityManager.class).getActiveSession();
        List<String> seen = new ArrayList<>();
        SessionEventAdapter counter = new SessionEventAdapter() {
            @Override
            public void preExecuteQuery(SessionEvent event) {
                DatabaseQuery query = event.getQuery();
                Class<?> type = query.getReferenceClass();
                if (query.isObjectLevelReadQuery() && !query.isReportQuery() && type != null) {
                    seen.add("entities:" + type.getSimpleName());
                } else {
                    String sql = query.getSQLString();
                    seen.add((sql != null ? sql : String.valueOf(type)).toLowerCase(Locale.ROOT));
                }
            }
        };
        session.getEventManager().addListener(counter);
        try {
            assertEquals(200, sitemap().getStatusCode().value());
        } finally {
            session.getEventManager().removeListener(counter);
        }
        return seen;
    }

    private static List<String> withoutConfigReads(List<String> queries) {
        return queries.stream().filter(q -> !q.equals("entities:RuntimeConfigProperty")).toList();
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = entries().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(entries());
    }


    private ResponseEntity<String> sitemap() {
        return controller.weblogSitemap(HANDLE, new MockHttpServletRequest());
    }

    /** A saved entry, re-read so its timestamps are the stored ones. */
    private WeblogEntry entry(String anchor, PubStatus status, int hoursAfterBase,
            String featuredImageId, boolean noindex) throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, weblog, user, status);
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(created);
        Instant pubTime = status == PubStatus.SCHEDULED
                ? Instant.now().plusSeconds(30L * 24 * 3600)
                : BASE.plusSeconds(hoursAfterBase * 3600L);
        managed.setPubTime(Timestamp.from(pubTime));
        managed.setFeaturedImageId(featuredImageId);
        managed.setNoindex(noindex);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return TestUtils.getManagedWeblogEntry(created);
    }

    private WeblogPage page(String slug, WeblogPage.PubStatus status, boolean noindex)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        page.setNoindex(noindex);
        pages().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return pages().getPageBySlug(TestUtils.getManagedWebsite(weblog), slug);
    }

    private static Date updated(WeblogEntry entry) {
        return entry.getUpdateTime();
    }

    private static String url(String loc, Date lastmod, String extra) {
        return "  <url>\n"
                + "    <loc>" + loc + "</loc>\n"
                + "    <lastmod>" + DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochSecond(lastmod.getTime() / 1000)) + "</lastmod>\n"
                + extra
                + "  </url>\n";
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private static WeblogPageManager pages() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    /**
     * Sets {@code BaseController}'s protected {@code weblogger} field the way
     * Spring's autowiring would; this test package cannot see the field
     * directly and spring-test is not on the classpath.
     */
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + target.getClass());
    }
}
