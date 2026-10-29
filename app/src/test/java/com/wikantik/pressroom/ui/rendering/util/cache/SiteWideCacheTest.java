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

package com.wikantik.pressroom.ui.rendering.util.cache;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.Utilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the cache behind the site-wide "front page" weblog.
 *
 * This one aggregates content from every weblog on the site, so it cannot
 * expire an entry at a time the way the per-weblog caches do: anything changing
 * anywhere may appear on the front page, and the whole cache is dropped. That
 * makes it the one cache registered as a CacheHandler, and the rules for which
 * changes drop it are what these tests pin down -- dropping too eagerly costs a
 * re-render of the busiest page on the site, and not dropping at all leaves the
 * front page showing content that is gone.
 */
public class SiteWideCacheTest {

    private static final String FRONT_PAGE = "frontpage";

    private SiteWideCache cache;

    @BeforeAll
    public static void requireCachingToBeOn() {
        assertTrue(WebloggerConfig.getBooleanProperty(SiteWideCache.CACHE_ID + ".enabled"),
                "These tests exercise the caching path, so " + SiteWideCache.CACHE_ID
                        + ".enabled must be true in the test configuration");
    }

    @BeforeEach
    public void startFromAnEmptyCache() {
        cache = SiteWideCache.getInstance();
        cache.clear();
        // the real check asks the runtime config, which needs a bootstrapped
        // Weblogger behind it; the rules being tested here are the same either way
        cache.setSiteWideWeblogCheck(FRONT_PAGE::equals);
    }

    @AfterEach
    public void restoreTheRealSiteWideCheck() {
        cache.setSiteWideWeblogCheck(SiteWideCache.SITE_WIDE_WEBLOG);
        cache.clear();
    }

    @Test
    public void theInstanceIsShared() {
        assertSame(cache, SiteWideCache.getInstance(),
                "Every servlet must reach the same cache, or nothing is cached at all");
    }

    @Test
    public void cachedContentComesBackOut() {
        cache.put("key", "rendered page");

        assertEquals("rendered page", cache.get("key"), "A cached page must be served");
        assertNull(cache.get("never-cached"),
                "and an uncached key must read as null so the servlet renders it");
    }

    @Test
    public void removedContentIsNoLongerServed() {
        cache.put("key", "rendered page");

        cache.remove("key");

        assertNull(cache.get("key"), "A removed page must not be served again");
    }

    @Test
    public void clearDropsEverything() {
        cache.put("one", "one");
        cache.put("two", "two");

        cache.clear();

        assertNull(cache.get("one"), "clear() must drop every entry");
        assertNull(cache.get("two"), "including the last one added");
    }

    @Test
    public void theCacheIsSizedFromConfiguration() {
        int configuredSize = WebloggerConfig.getIntProperty(SiteWideCache.CACHE_ID + ".size");
        assertTrue(configuredSize > 1,
                SiteWideCache.CACHE_ID + ".size must be configured for this test to mean "
                        + "anything, but was " + configuredSize);

        // built here rather than read from the singleton, so that what is being
        // tested is the constructor reading the configuration
        SiteWideCache configured = SiteWideCache.newForTest(true);
        for (int i = 0; i < configuredSize; i++) {
            configured.put("key" + i, "page " + i);
        }

        assertEquals("page 0", configured.get("key0"),
                "The cache must hold as many pages as " + SiteWideCache.CACHE_ID
                        + ".size says. If the per-cache properties stop being read, every "
                        + "install silently falls back to the default size.");

        // key0 was just read, so key1 is the least recently used
        configured.put("one-too-many", "page");
        assertNull(configured.get("key1"),
                "and no more than that, or the cache grows without limit");
    }

    @Test
    public void lastModifiedIsRememberedBetweenRequests() {
        Date first = cache.getLastModified();

        assertSame(first, cache.getLastModified(),
                "The last-modified stamp is sent with every site-wide page and compared "
                        + "against every If-Modified-Since; it is cached rather than "
                        + "recomputed per request");
    }

    @Test
    public void lastModifiedMovesOnWhenTheCacheIsDropped() {
        Date before = cache.getLastModified();

        cache.clear();

        assertNotSame(before, cache.getLastModified(),
                "Flushing the cache must also forget the last-modified stamp, otherwise "
                        + "conditional requests keep getting 304 Not Modified for content "
                        + "that has just changed");
    }

    @Test
    public void aChangedEntryDropsEverything() {
        cache.put("key", "rendered page");
        Date before = cache.getLastModified();

        cache.invalidate(new WeblogEntry());

        assertNull(cache.get("key"),
                "Any entry anywhere may appear on the site-wide front page, so a changed "
                        + "entry drops the whole cache");
        assertNotSame(before, cache.getLastModified(), "and the last-modified stamp with it");
    }

    @Test
    public void aChangedWeblogDropsEverything() {
        cache.put("key", "rendered page");

        cache.invalidate(new Weblog());

        assertNull(cache.get("key"),
                "A changed weblog may change what the front page shows of it");
    }

    @Test
    public void aChangedUserChangesNothing() {
        cache.put("key", "rendered page");

        cache.invalidate(new User());

        assertEquals("rendered page", cache.get("key"),
                "A profile change does not alter rendered site-wide content, and dropping "
                        + "this cache is expensive enough not to do it for nothing");
    }

    @Test
    public void aCategoryOnTheFrontPageWeblogDropsTheCache() {
        cache.put("key", "rendered page");

        cache.invalidate(category(weblog(FRONT_PAGE)));

        assertNull(cache.get("key"), "Categories are rendered into the site-wide pages");
    }

    @Test
    public void aCategoryOnAnyOtherWeblogLeavesTheCacheAlone() {
        cache.put("key", "rendered page");

        cache.invalidate(category(weblog("someone-else")));

        assertEquals("rendered page", cache.get("key"),
                "Another weblog's categories do not appear on the site-wide pages");
    }

    @Test
    public void aTemplateOnTheFrontPageWeblogDropsTheCache() {
        cache.put("key", "rendered page");

        cache.invalidate(template(weblog(FRONT_PAGE)));

        assertNull(cache.get("key"),
                "The site-wide weblog's templates are what renders the front page");
    }

    @Test
    public void aTemplateOnAnyOtherWeblogLeavesTheCacheAlone() {
        cache.put("key", "rendered page");

        cache.invalidate(template(weblog("someone-else")));

        assertEquals("rendered page", cache.get("key"),
                "Another weblog's templates do not render the site-wide pages");
    }

    @Test
    public void pageAndFeedKeysCannotCollide() {
        WeblogPageRequest page = pageRequest();
        WeblogFeedRequest feed = feedRequest();

        assertTrue(cache.generateKey(page).startsWith(SiteWideCache.CACHE_ID + ":page/"),
                "Page keys must be labelled as pages");
        assertTrue(cache.generateKey(feed).startsWith(SiteWideCache.CACHE_ID + ":feed/"),
                "Feed keys must be labelled as feeds, so that the html page and the rss "
                        + "feed for one weblog cannot be served for each other");
    }

    @Test
    public void theSameRequestAlwaysProducesTheSameKey() {
        WeblogPageRequest one = pageRequest();
        one.setWeblogCategoryName("Travel Notes");
        one.setLocale("en_US");

        WeblogPageRequest other = pageRequest();
        other.setWeblogCategoryName("Travel Notes");
        other.setLocale("en_US");

        assertEquals(cache.generateKey(one), cache.generateKey(other),
                "The same page request must produce the same key every time");
        assertEquals(cache.generateKey(feedRequest()), cache.generateKey(feedRequest()),
                "and so must the same feed request");
    }

    @Test
    public void theKeyOfAPageRequestThatUsesEverything() {
        WeblogPageRequest request = pageRequest();
        request.setWeblogPageName("about me");
        request.setWeblogDate("20051110");
        request.setWeblogCategoryName("Travel Notes");
        request.setContext("tags");
        request.setTags(List.of("banana", "apple"));
        request.setLocale("en_US");
        request.setPageNum(2);
        request.setAuthenticUser("editor");
        request.setCustomParams(Map.of("colour", new String[]{"red"}));

        // spelled out rather than described: every one of these fragments is a
        // part of the request that has to survive into the key, and a mistake
        // that drops one of them shows up as one page being served for another
        assertEquals("cache.sitewide:page/frontpage"
                        + "/page/about%20me"
                        + "/date/20051110"
                        + "/cat/Travel%20Notes"
                        + "/tags/apple+banana"
                        + "/en_US"
                        + "/page=2"
                        + "/user=editor"
                        + "/qp=" + Utilities.toBase64("colour=red".getBytes(StandardCharsets.UTF_8)),
                cache.generateKey(request),
                "Every part of the request must appear in the key, escaped and labelled");
    }

    @Test
    public void theKeyOfAFeedRequestThatUsesEverything() {
        WeblogFeedRequest request = feedRequest();
        request.setType("comments");
        request.setFormat("atom");
        request.setTerm("winter storm");
        request.setLocale("en_US");
        request.setExcerpts(true);
        request.setTags(List.of("banana", "apple"));

        assertEquals("cache.sitewide:feed/frontpage"
                        + "/comments/atom"
                        + "/search/winter%20storm"
                        + "/en_US"
                        + "/excerpts"
                        + "/tags/apple+banana",
                cache.generateKey(request),
                "and the same for a feed request");
    }

    @Test
    public void theKeyOfAPlainFrontPage() {
        assertEquals("cache.sitewide:page/frontpage/page=0", cache.generateKey(pageRequest()),
                "The simplest page request must produce the simplest key");
        assertEquals("cache.sitewide:feed/frontpage/entries/rss", cache.generateKey(feedRequest()),
                "and so must the simplest feed request");
    }

    @Test
    public void everyPartOfAPageRequestChangesTheKey() {
        String base = cache.generateKey(pageRequest());

        WeblogPageRequest permalink = pageRequest();
        permalink.setWeblogAnchor("my post");
        assertNotEquals(base, cache.generateKey(permalink), "A permalink is a different page");

        WeblogPageRequest custom = pageRequest();
        custom.setWeblogPageName("aboutme");
        assertNotEquals(base, cache.generateKey(custom), "A custom page is a different page");

        WeblogPageRequest dated = pageRequest();
        dated.setWeblogDate("20051110");
        assertNotEquals(base, cache.generateKey(dated), "A date archive is a different page");

        WeblogPageRequest tagged = pageRequest();
        tagged.setContext("tags");
        tagged.setTags(List.of("apple"));
        assertNotEquals(base, cache.generateKey(tagged), "A tag view is a different page");

        WeblogPageRequest secondPage = pageRequest();
        secondPage.setPageNum(1);
        assertNotEquals(base, cache.generateKey(secondPage), "Page 2 holds different entries");

        WeblogPageRequest loggedIn = pageRequest();
        loggedIn.setAuthenticUser("editor");
        assertNotEquals(base, cache.generateKey(loggedIn),
                "A logged-in reader sees links a stranger must not be served from the cache");

        WeblogPageRequest withParams = pageRequest();
        withParams.setCustomParams(Map.of("colour", new String[]{"red"}));
        assertNotEquals(base, cache.generateKey(withParams),
                "Query parameters reach the template and change what it renders");

        WeblogPageRequest bareSlugPage = pageRequest();
        bareSlugPage.setPageSlug("about");
        assertNotEquals(base, cache.generateKey(bareSlugPage), "A bare-slug page is a different page");
    }

    /**
     * The defect this pins: two bare-slug pages on the site-wide weblog used
     * to share the front page's key, because none of the fields the key
     * already accounted for is set for that kind of request.
     */
    @Test
    public void twoBareSlugPagesGetDifferentKeys() {
        WeblogPageRequest about = pageRequest();
        about.setPageSlug("about");

        WeblogPageRequest contact = pageRequest();
        contact.setPageSlug("contact");

        assertNotEquals(cache.generateKey(pageRequest()), cache.generateKey(about),
                "A bare-slug page must not share the front page's cache key");
        assertNotEquals(cache.generateKey(about), cache.generateKey(contact),
                "Two different page slugs must not share a cache key");
    }

    @Test
    public void everyPartOfAFeedRequestChangesTheKey() {
        String base = cache.generateKey(feedRequest());

        WeblogFeedRequest comments = feedRequest();
        comments.setType("comments");
        assertNotEquals(base, cache.generateKey(comments), "The comments feed is a different feed");

        WeblogFeedRequest atom = feedRequest();
        atom.setFormat("atom");
        assertNotEquals(base, cache.generateKey(atom), "Atom and RSS are different documents");

        WeblogFeedRequest category = feedRequest();
        category.setWeblogCategoryName("Travel");
        assertNotEquals(base, cache.generateKey(category), "A category feed is a different feed");

        WeblogFeedRequest german = feedRequest();
        german.setLocale("de");
        assertNotEquals(base, cache.generateKey(german), "A different language is different content");

        WeblogFeedRequest excerpts = feedRequest();
        excerpts.setExcerpts(true);
        assertNotEquals(base, cache.generateKey(excerpts), "Excerpts are not full entries");

        WeblogFeedRequest search = feedRequest();
        search.setTerm("winter");
        assertNotEquals(base, cache.generateKey(search), "A search feed is a different feed");

        WeblogFeedRequest tagged = feedRequest();
        tagged.setTags(List.of("apple"));
        assertNotEquals(base, cache.generateKey(tagged), "A tag feed is a different feed");
    }

    @Test
    public void aViewWithNoTagsIsStillTheSamePage() {
        WeblogPageRequest noTagList = pageRequest();
        noTagList.setContext("tags");

        WeblogPageRequest emptyTagList = pageRequest();
        emptyTagList.setContext("tags");
        emptyTagList.setTags(List.of());

        // /tags/ with nothing after it parses to the tags context with no tags;
        // it must produce a key rather than a NullPointerException
        assertEquals(cache.generateKey(noTagList), cache.generateKey(emptyTagList),
                "A tags view with no tags and one with an empty tag list are the same page");

        WeblogFeedRequest emptyFeedTags = feedRequest();
        emptyFeedTags.setTags(List.of());

        assertEquals(cache.generateKey(feedRequest()), cache.generateKey(emptyFeedTags),
                "and filtering a feed by no tags is not filtering at all");
    }

    @Test
    public void aCategoryNamedLikeALanguageDoesNotStealItsPage() {
        WeblogPageRequest germanCategory = pageRequest();
        germanCategory.setWeblogCategoryName("de");

        WeblogPageRequest germanLanguage = pageRequest();
        germanLanguage.setLocale("de");

        // both used to append a bare "/de" to the key
        assertNotEquals(cache.generateKey(germanCategory), cache.generateKey(germanLanguage),
                "The category 'de' and the German front page are different pages");

        WeblogFeedRequest germanCategoryFeed = feedRequest();
        germanCategoryFeed.setWeblogCategoryName("de");

        WeblogFeedRequest germanFeed = feedRequest();
        germanFeed.setLocale("de");

        assertNotEquals(cache.generateKey(germanCategoryFeed), cache.generateKey(germanFeed),
                "and the same holds for their feeds");
    }

    @Test
    public void aSearchTermCannotImpersonateTheRestOfTheKey() {
        WeblogFeedRequest slashInTerm = feedRequest();
        slashInTerm.setTerm("winter/de");

        WeblogFeedRequest termAndLanguage = feedRequest();
        termAndLanguage.setTerm("winter");
        termAndLanguage.setLocale("de");

        assertNotEquals(cache.generateKey(slashInTerm), cache.generateKey(termAndLanguage),
                "A search for 'winter/de' must not produce the key of the German feed "
                        + "for 'winter'");
    }

    @Test
    public void aDisabledCacheCachesNothing() {
        SiteWideCache disabled = SiteWideCache.newForTest(false);
        disabled.setSiteWideWeblogCheck(FRONT_PAGE::equals);

        disabled.put("key", "rendered page");

        assertNull(disabled.get("key"),
                "A disabled cache must never serve content, or turning caching off would "
                        + "not actually turn it off");

        // and none of the invalidation paths may trip over the cache that was
        // never built
        disabled.invalidate(new WeblogEntry());
        disabled.invalidate(new Weblog());
        disabled.invalidate(category(weblog(FRONT_PAGE)));
        disabled.invalidate(template(weblog(FRONT_PAGE)));
        disabled.remove("key");
        disabled.clear();

        assertNull(disabled.get("key"), "and it still caches nothing afterwards");

        cache.put("key", "rendered page");
        assertEquals("rendered page", cache.get("key"),
                "while the configured instance, which is enabled, still caches");
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogCategory category(Weblog weblog) {
        WeblogCategory category = new WeblogCategory();
        category.setWeblog(weblog);
        return category;
    }

    private static WeblogTemplate template(Weblog weblog) {
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(weblog);
        return template;
    }

    private static WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblogHandle(FRONT_PAGE);
        return request;
    }

    private static WeblogFeedRequest feedRequest() {
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblogHandle(FRONT_PAGE);
        request.setType("entries");
        request.setFormat("rss");
        return request;
    }
}
