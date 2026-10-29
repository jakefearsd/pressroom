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
package com.wikantik.pressroom.ui.rendering.servlets;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCaches;
import com.wikantik.pressroom.util.cache.CachedContent;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link PageServlet} does before it renders anything: conditional GETs,
 * the page cache, and the way both of them change for a signed-in reader.
 *
 * <p>The existing rendering tests all take the render path. These take the ones
 * that avoid it, which is most requests on a live site -- and where the
 * failures are silent by construction. A cache key that stops varying serves
 * one reader the page built for another; a 304 handed to a signed-in author
 * shows them a page with no edit links and no way to tell why.
 */
class PageServletCachingTest {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        clearPageCaches();

        user = TestUtils.setupUser("pagecacheuser");
        weblog = TestUtils.setupWeblog("pagecacheblog", user);
        entry = TestUtils.setupWeblogEntry("cached-entry", weblog, user);
        TestUtils.endSession(true);

        // saveWeblog stamps lastModified with "now" whatever it was set to, so
        // the backdating below does not survive the save. What matters is the
        // stored value: a sentinel planted in that same millisecond counts as
        // stale (LazyExpiringCacheEntry treats a same-millisecond change as
        // newer), so wait for the clock to pass it before any test plants one.
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setLastModified(new Timestamp(
                Instant.now().minusSeconds(3600).toEpochMilli()));
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        long stamped = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();
        TestUtils.endSession(true);
        while (System.currentTimeMillis() <= stamped) {
            Thread.onSpinWait();
        }
    }

    /**
     * CacheManager.clear() does not reach the per-weblog page cache, and these
     * tests seed it with a sentinel under the key of a weblog whose handle every
     * method reuses: an entry left behind would be served to the next method.
     */
    private static void clearPageCaches() {
        RenderCaches.forPage(false).clear();
        RenderCaches.forPage(true).clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearPageCaches();
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------ conditional GETs

    @Test
    void aFirstRequestCarriesALastModifiedHeader() throws Exception {
        MockHttpServletResponse response = get(null);

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Last-Modified") != null,
                "without this header a reader can never make a conditional request");
    }

    /**
     * The saving that matters on a busy blog: a reader who already has the page
     * is told so rather than sent it again.
     */
    @Test
    void anUnchangedPageAnswersNotModified() throws Exception {
        MockHttpServletResponse first = get(null);
        String lastModified = first.getHeader("Last-Modified");

        MockHttpServletResponse second = get(lastModified);

        assertEquals(304, second.getStatus(),
                "the same page, unchanged, must not be sent twice");
        assertEquals(0, second.getContentAsByteArray().length,
                "and a 304 carries no body");
    }

    @Test
    void aPageModifiedSinceTheReadersCopyIsSentInFull() throws Exception {
        String longAgo = HTTP_DATE.format(Instant.now().minusSeconds(86_400 * 30));

        MockHttpServletResponse response = get(longAgo);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("cached-entry")
                        || response.getContentAsByteArray().length > 0,
                "a stale conditional request must be answered with the page");
    }

    /**
     * Deliberately skipped for a signed-in reader. The scenario the servlet
     * guards against: an author views their weblog anonymously, signs in, and
     * would otherwise be handed the 304 for the anonymous copy -- a page with
     * none of their edit links, and no way to tell why.
     */
    @Test
    void aSignedInReaderIsNeverAnsweredNotModified() throws Exception {
        MockHttpServletResponse anonymous = get(null);
        String lastModified = anonymous.getHeader("Last-Modified");

        MockHttpServletResponse signedIn = get(lastModified, user.getUserName());

        assertNotEquals(304, signedIn.getStatus(),
                "a signed-in author must get the page built for them, not the "
                        + "anonymous copy's 304");
    }

    // ----------------------------------------------------------- page caching

    /**
     * A second anonymous request for the same page is served from the cache.
     * Asserted on the bytes rather than on a hit counter, because identical
     * output is the thing callers actually depend on.
     */
    @Test
    void aRepeatedAnonymousRequestIsServedFromCache() throws Exception {
        byte[] first = get(null).getContentAsByteArray();
        byte[] second = get(null).getContentAsByteArray();

        assertTrue(first.length > 0, "the first request must render something");
        assertEquals(new String(first), new String(second),
                "the cached copy must be byte-identical to what was rendered");
    }

    /**
     * The cache key has to separate a signed-in reader's view from the
     * anonymous one. If it stopped doing so, the first author to view their own
     * weblog would poison the cache with a page carrying edit links for every
     * anonymous reader after them.
     */
    @Test
    void aSignedInViewIsCachedSeparatelyFromTheAnonymousOne() throws Exception {
        String anonymous = get(null).getContentAsString();
        String authored = get(null, user.getUserName()).getContentAsString();
        String anonymousAgain = get(null).getContentAsString();

        assertEquals(anonymous, anonymousAgain,
                "an author's visit must not change what the next anonymous reader "
                        + "is served -- that is a cache key that stopped varying by user");
        assertFalse(authored.isEmpty());
    }

    /**
     * {@code skipCache} is the escape hatch the preview and the editor rely on
     * to see their own unsaved work rather than yesterday's render.
     */
    @Test
    void skipCacheBypassesTheCacheEntirely() throws Exception {
        get(null);

        MockHttpServletRequest request = pageRequest(null, null);
        request.setParameter("skipCache", "true");
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsByteArray().length > 0,
                "the page still renders, just not from the cache");
    }

    /**
     * A change to the weblog has to reach readers. WeblogPageCache has no
     * CacheHandler, so nothing evicts it -- entries expire lazily against
     * weblog.lastModified, and saveWeblog bumping that is the only thing
     * standing between an edit and a stale page.
     */
    @Test
    void bumpingTheWeblogsLastModifiedExpiresTheCachedPage() throws Exception {
        String before = get(null).getContentAsString();
        assertFalse(before.isEmpty());

        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Renamed while cached");
        managed.setLastModified(new Timestamp(Instant.now().toEpochMilli()));
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        String after = get(null).getContentAsString();

        assertTrue(after.contains("Renamed while cached"),
                "a cached page must expire once the weblog it belongs to changes");
    }

    /**
     * A POST reaches the same rendering as a GET but never from the cache. The
     * comment servlet forwards here after a post so the reader sees their own
     * comment, or the moderation message about it -- serving that from the
     * cache would show them the page as it was before they wrote anything.
     */
    @Test
    void aPostRendersLikeAGetButAlwaysSkipsTheCache() throws Exception {
        get(null);

        MockHttpServletRequest request = pageRequest(null, null);
        request.setMethod("POST");
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsByteArray().length > 0,
                "a POST must still render the page");
        assertEquals("true", request.getAttribute("skipCache"),
                "and must have marked the request as bypassing the cache");
    }

    // ------------------------------------------- what the cache really does

    private static final String SENTINEL = "SERVED-FROM-THE-CACHE";

    private static CachedContent sentinel() throws Exception {
        CachedContent content = new CachedContent(64, "text/html; charset=utf-8");
        content.getCachedWriter().print(SENTINEL);
        content.flush();
        return content;
    }

    private String keyFor(MockHttpServletRequest request) throws Exception {
        return RenderCaches.forPage(false).generateKey(
                new WeblogPageRequest(TestUtils.weblogger(), request));
    }

    private boolean cacheHolds(String key) {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        return cache.get(key, 0L) != null;
    }

    /** Characterisation of the shipped read path, with the cache genuinely in play. */
    @Test
    void anAnonymousRequestIsAnsweredFromTheEntryHoldingItsKey() throws Exception {
        RenderCaches.forPage(false).put(keyFor(pageRequest(null, null)), sentinel());

        MockHttpServletResponse response = get(null);

        assertEquals(SENTINEL, response.getContentAsString());
        assertEquals(SENTINEL.length(), response.getContentLength());
        assertEquals("text/html; charset=utf-8", response.getContentType());
    }

    @Test
    void aSignedInReaderIsNotHandedTheEntryKeptForAnonymousReaders() throws Exception {
        MockHttpServletRequest anonymous = pageRequest(null, null);
        MockHttpServletRequest signedIn = pageRequest(null, user.getUserName());
        RenderCaches.forPage(false).put(keyFor(anonymous), sentinel());

        assertNotEquals(keyFor(anonymous), keyFor(signedIn));
        assertTrue(keyFor(signedIn).contains("/user=" + user.getUserName()), keyFor(signedIn));
        assertNotEquals(SENTINEL, get(null, user.getUserName()).getContentAsString());
    }

    @Test
    void theSkipCacheParameterIgnoresAnExistingEntryButStillRepopulatesIt() throws Exception {
        String key = keyFor(pageRequest(null, null));
        RenderCaches.forPage(false).put(key, sentinel());
        MockHttpServletRequest request = pageRequest(null, null);
        request.setParameter("skipCache", "true");

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), request);

        assertNotEquals(SENTINEL, response.getContentAsString());
        assertEquals(response.getContentAsString(),
                RenderCaches.forPage(false).get(key, 0L).getContentAsString(),
                "a parameter suppresses the read only; the fresh render replaces the entry");
    }

    @Test
    void theSkipCacheAttributeNeitherReadsNorWrites() throws Exception {
        String key = keyFor(pageRequest(null, null));
        RenderCaches.forPage(false).put(key, sentinel());
        MockHttpServletRequest request = pageRequest(null, null);
        request.setAttribute("skipCache", "true");

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), request);

        assertNotEquals(SENTINEL, response.getContentAsString());
        assertEquals(SENTINEL, RenderCaches.forPage(false).get(key, 0L).getContentAsString(),
                "internally uncacheable content must leave the cache untouched");
    }

    @Test
    void aRenderPopulatesTheCacheAndAnEntryOlderThanTheWeblogIsNotServed() throws Exception {
        MockHttpServletRequest request = pageRequest(null, null);
        String key = keyFor(request);
        assertFalse(cacheHolds(key), "nothing is cached before the first request");

        String rendered = get(null).getContentAsString();

        assertTrue(cacheHolds(key), "the first render must be stored");
        assertEquals(rendered, RenderCaches.forPage(false).get(key, 0L).getContentAsString());
        long afterTheEntryWasStored = System.currentTimeMillis() + 60_000;
        assertEquals(null, RenderCaches.forPage(false).get(key, afterTheEntryWasStored),
                "an entry older than the weblog's lastModified is expired, not served");
    }

    /** With cache.excludeOwnerEditPages on, a signed-in render is neither read from nor written to the cache. */
    @Test
    void withOwnerPagesExcludedASignedInRenderIsNeverCached() throws Exception {
        com.wikantik.pressroom.ui.rendering.servlets.PageServlet servlet = RenderingTestSupport.pageServlet();
        ReflectionTestUtils.setField(servlet, "excludeOwnerPages", true);
        MockHttpServletRequest signedIn = pageRequest(null, user.getUserName());
        String key = keyFor(signedIn);
        RenderCaches.forPage(false).put(key, sentinel());

        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, signedIn);

        assertNotEquals(SENTINEL, response.getContentAsString(), "the read side is closed to a signed-in reader");
        assertEquals(SENTINEL, RenderCaches.forPage(false).get(key, 0L).getContentAsString(),
                "and the write side leaves the existing entry alone");
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletResponse get(String ifModifiedSince) throws Exception {
        return get(ifModifiedSince, null);
    }

    private MockHttpServletResponse get(String ifModifiedSince, String signedInAs)
            throws Exception {
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                pageRequest(ifModifiedSince, signedInAs));
    }

    private MockHttpServletRequest pageRequest(String ifModifiedSince, String signedInAs) {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/pagecacheblog");
        // anonymousGet marks every request skipCache so the render tests never
        // see a stale page; this class is about the cache, so it must not.
        // Without this the "served from cache" tests below passed without the
        // cache ever being read or written.
        request.removeAttribute("skipCache");
        if (ifModifiedSince != null) {
            request.addHeader("If-Modified-Since", ifModifiedSince);
        }
        if (signedInAs != null) {
            request.setUserPrincipal(() -> signedInAs);
        }
        return request;
    }
}
