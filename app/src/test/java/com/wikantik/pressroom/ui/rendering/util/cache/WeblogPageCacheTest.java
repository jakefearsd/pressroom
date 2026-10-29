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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.Utilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the cache that sits in front of every rendered weblog page.
 *
 * Two halves matter here. The key has to tell requests apart -- everything a
 * reader can vary in a url ends up in it, and two different requests sharing a
 * key means one reader is served the other's page. And the entry has to be
 * withheld once the weblog has changed, which is how a deleted post stops being
 * served.
 */
public class WeblogPageCacheTest {

    private WeblogPageCache cache;

    @BeforeAll
    public static void requireCachingToBeOn() {
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogPageCache.CACHE_ID + ".enabled"),
                "These tests exercise the caching path, so " + WeblogPageCache.CACHE_ID
                        + ".enabled must be true in the test configuration");
    }

    @BeforeEach
    public void startFromAnEmptyCache() {
        cache = WeblogPageCache.getInstance();
        cache.clear();
    }

    @Test
    public void theInstanceIsShared() {
        assertSame(cache, WeblogPageCache.getInstance(),
                "Every servlet must reach the same cache, or nothing is cached at all");
    }

    @Test
    public void cachedPageIsServedUntilTheWeblogChanges() {
        String key = "cache.weblogpage:test-page";

        cache.put(key, "rendered page");

        assertEquals("rendered page", cache.get(key, 0L),
                "A cached page must be served when the weblog has never been modified");
        assertEquals("rendered page", cache.get(key, 1L),
                "and while the last modification is older than the cached copy");
    }

    @Test
    public void cachedPageIsWithheldOnceTheWeblogHasChanged() {
        String key = "cache.weblogpage:test-page";
        cache.put(key, "rendered page");

        long afterCaching = System.currentTimeMillis() + 60_000L;

        assertNull(cache.get(key, afterCaching),
                "A page cached before the weblog was last modified must not be served. "
                        + "This is the whole invalidation mechanism for blog pages: a reader "
                        + "would otherwise keep seeing a post that has been deleted.");
    }

    @Test
    public void anUncachedKeyReadsAsNull() {
        assertNull(cache.get("cache.weblogpage:never-cached", 0L),
                "A key that was never cached must read as null so the servlet renders it");
    }

    @Test
    public void removedPageIsNoLongerServed() {
        String key = "cache.weblogpage:test-page";
        cache.put(key, "rendered page");

        cache.remove(key);

        assertNull(cache.get(key, 0L), "A removed page must not be served again");
    }

    @Test
    public void clearDropsEverything() {
        cache.put("cache.weblogpage:one", "one");
        cache.put("cache.weblogpage:two", "two");

        cache.clear();

        assertNull(cache.get("cache.weblogpage:one", 0L), "clear() must drop every entry");
        assertNull(cache.get("cache.weblogpage:two", 0L), "including the last one added");
    }

    @Test
    public void theSameRequestAlwaysProducesTheSameKey() {
        WeblogPageRequest one = pageRequest();
        one.setWeblogCategoryName("Travel Notes");
        one.setLocale("en_US");
        one.setPageNum(2);

        WeblogPageRequest other = pageRequest();
        other.setWeblogCategoryName("Travel Notes");
        other.setLocale("en_US");
        other.setPageNum(2);

        assertEquals(cache.generateKey(one), cache.generateKey(other),
                "The same request must produce the same key every time, or the cache "
                        + "never hits and every page is rendered from scratch");
    }

    @Test
    public void keyIsScopedToThisCache() {
        assertTrue(cache.generateKey(pageRequest()).startsWith(WeblogPageCache.CACHE_ID + ":"),
                "Keys are prefixed with the cache id so that entries from different "
                        + "caches cannot be confused when they share a backing store");
    }

    @Test
    public void theKeyOfAPlainWeblogHomepage() {
        assertEquals("cache.weblogpage:myblog/page=0", cache.generateKey(pageRequest()),
                "The simplest request must produce the simplest key");
    }

    @Test
    public void theKeyOfARequestThatUsesEverything() {
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
        assertEquals("cache.weblogpage:myblog"
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
    public void theKeyOfAPermalink() {
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("my first post");
        request.setLocale("en_US");
        request.setAuthenticUser("editor");

        // a permalink renders one entry, so there is no page number and none of
        // the collection filters apply
        assertEquals("cache.weblogpage:myblog/entry/my%20first%20post/en_US/user=editor",
                cache.generateKey(request),
                "A permalink key is the entry, the language and the reader");
    }

    @Test
    public void theCacheIsSizedFromConfiguration() {
        int configuredSize = WebloggerConfig.getIntProperty(WeblogPageCache.CACHE_ID + ".size");
        assertTrue(configuredSize > 1,
                WeblogPageCache.CACHE_ID + ".size must be configured for this test to mean "
                        + "anything, but was " + configuredSize);

        // built here rather than read from the singleton, so that what is being
        // tested is the constructor reading the configuration
        WeblogPageCache configured = WeblogPageCache.newForTest(true);
        for (int i = 0; i < configuredSize; i++) {
            configured.put("key" + i, "page " + i);
        }

        assertEquals("page 0", configured.get("key0", 0L),
                "The cache must hold as many pages as " + WeblogPageCache.CACHE_ID
                        + ".size says. If the per-cache properties stop being read, every "
                        + "install silently falls back to the default size.");

        // key0 was just read, so key1 is the least recently used
        configured.put("one-too-many", "page");
        assertNull(configured.get("key1", 0L),
                "and no more than that, or the cache grows without limit");
    }

    @Test
    public void everyPartOfTheRequestChangesTheKey() {
        String base = cache.generateKey(pageRequest());

        WeblogPageRequest otherWeblog = pageRequest();
        otherWeblog.setWeblogHandle("other");
        assertNotEquals(base, cache.generateKey(otherWeblog), "A different weblog is a different page");

        WeblogPageRequest permalink = pageRequest();
        permalink.setWeblogAnchor("my-post");
        assertNotEquals(base, cache.generateKey(permalink), "A permalink is a different page");

        WeblogPageRequest category = pageRequest();
        category.setWeblogCategoryName("Travel");
        assertNotEquals(base, cache.generateKey(category), "A category view is a different page");

        WeblogPageRequest date = pageRequest();
        date.setWeblogDate("20051110");
        assertNotEquals(base, cache.generateKey(date), "A date archive is a different page");

        WeblogPageRequest locale = pageRequest();
        locale.setLocale("de");
        assertNotEquals(base, cache.generateKey(locale), "A different language is different content");

        WeblogPageRequest secondPage = pageRequest();
        secondPage.setPageNum(1);
        assertNotEquals(base, cache.generateKey(secondPage),
                "Page 2 of the archive holds different entries than page 1");

        WeblogPageRequest loggedIn = pageRequest();
        loggedIn.setAuthenticUser("editor");
        assertNotEquals(base, cache.generateKey(loggedIn),
                "A logged-in reader sees edit links and drafts a stranger must not be "
                        + "served from the cache");

        WeblogPageRequest customPage = pageRequest();
        customPage.setWeblogPageName("aboutme");
        assertNotEquals(base, cache.generateKey(customPage), "A custom page is a different page");

        WeblogPageRequest bareSlugPage = pageRequest();
        bareSlugPage.setPageSlug("about");
        assertNotEquals(base, cache.generateKey(bareSlugPage), "A bare-slug page is a different page");
    }

    /**
     * The defect this pins: /myblog/about and /myblog/contact used to share
     * the homepage's key, because neither weblogAnchor, weblogPageName,
     * weblogDate, weblogCategoryName nor tags is ever set for a bare-slug
     * page request. Whichever page rendered first was then served, from
     * cache, for the homepage and for every other bare-slug page on the
     * weblog until the weblog's lastModified moved.
     */
    @Test
    public void aBareSlugPageGetsItsOwnKeyDistinctFromTheHomepageAndItsSiblings() {
        WeblogPageRequest about = pageRequest();
        about.setPageSlug("about");

        WeblogPageRequest contact = pageRequest();
        contact.setPageSlug("contact");

        assertNotEquals(cache.generateKey(pageRequest()), cache.generateKey(about),
                "A bare-slug page must not share the homepage's cache key");
        assertNotEquals(cache.generateKey(about), cache.generateKey(contact),
                "Two different page slugs on the same weblog must not share a cache key");
    }

    @Test
    public void twoReadersOfDifferentTagsGetDifferentKeys() {
        WeblogPageRequest apples = tagsRequest("apple");
        WeblogPageRequest bananas = tagsRequest("banana");
        WeblogPageRequest both = tagsRequest("apple", "banana");

        assertNotEquals(cache.generateKey(apples), cache.generateKey(bananas),
                "Different tags select different entries");
        assertNotEquals(cache.generateKey(apples), cache.generateKey(both),
                "and narrowing by a second tag changes the page");
        assertEquals(cache.generateKey(both), cache.generateKey(tagsRequest("banana", "apple")),
                "while the same tags in a different order are the same page");
    }

    @Test
    public void aTagsViewWithNoTagsIsStillTheTagsView() {
        WeblogPageRequest noTagList = pageRequest();
        noTagList.setContext("tags");

        WeblogPageRequest emptyTagList = tagsRequest();

        // /tags/ with nothing after it parses to the tags context with no tags;
        // it must produce a key rather than a NullPointerException
        assertEquals(cache.generateKey(noTagList), cache.generateKey(emptyTagList),
                "A tags view with no tags and one with an empty tag list are the same page");
    }

    @Test
    public void tagsOnlyCountWhereTheyAreActuallyUsed() {
        WeblogPageRequest homepage = pageRequest();
        homepage.setTags(List.of("apple"));

        // outside the tags view the tag list is not what gets rendered, and
        // baking it into the key would fragment the cache for no reason
        assertEquals(cache.generateKey(pageRequest()), cache.generateKey(homepage),
                "Tags outside the tags context must not change the key");
    }

    @Test
    public void aCategoryNamedLikeALanguageDoesNotStealItsPage() {
        WeblogPageRequest germanCategory = pageRequest();
        germanCategory.setWeblogCategoryName("de");

        WeblogPageRequest germanLanguage = pageRequest();
        germanLanguage.setLocale("de");

        // both used to append a bare "/de" to the key, so a weblog with a
        // category per language served one for the other
        assertNotEquals(cache.generateKey(germanCategory), cache.generateKey(germanLanguage),
                "The category 'de' and the German homepage are different pages and must "
                        + "not share a cache entry");
    }

    @Test
    public void aCustomPageNameCannotSwallowTheSegmentAfterIt() {
        WeblogPageRequest slashInName = pageRequest();
        slashInName.setWeblogPageName("aboutme/de");

        WeblogPageRequest nameAndLanguage = pageRequest();
        nameAndLanguage.setWeblogPageName("aboutme");
        nameAndLanguage.setLocale("de");

        // the page name comes straight off the url path, which can contain
        // slashes; unescaped it could close its own segment and open the next
        assertNotEquals(cache.generateKey(slashInName), cache.generateKey(nameAndLanguage),
                "A page name containing a slash must not produce the key of another page "
                        + "in another language");
    }

    @Test
    public void queryParametersOfACustomPageChangeTheKey() {
        WeblogPageRequest red = customPageRequest("colour", "red");
        WeblogPageRequest blue = customPageRequest("colour", "blue");

        assertNotEquals(cache.generateKey(red), cache.generateKey(blue),
                "A custom page template can read its query parameters, so two requests "
                        + "with different parameters render differently");
        assertNotEquals(cache.generateKey(red), cache.generateKey(pageRequest()),
                "and a parameterised request is not the same as a bare one");
    }

    @Test
    public void queryParametersAreDeliberatelyIgnoredOutsideCustomPages() {
        WeblogPageRequest homepage = pageRequest();
        homepage.setCustomParams(Map.of("utm_source", new String[]{"newsletter"}));

        // only custom page templates can read query parameters. Keying on them
        // everywhere would give every campaign link and every crawler its own
        // copy of the homepage, which is how a page cache turns into a leak.
        assertEquals(cache.generateKey(pageRequest()), cache.generateKey(homepage),
                "Query parameters must not change the key outside a custom page");
    }

    @Test
    public void aDisabledCacheCachesNothing() {
        // how Roller runs in development, and how it runs for anyone who turns
        // cache.weblogpage.enabled off: the calls must all be no-ops
        WeblogPageCache disabled = WeblogPageCache.newForTest(false);

        disabled.put("key", "rendered page");

        assertNull(disabled.get("key", 0L),
                "A disabled cache must never serve content, or turning caching off would "
                        + "not actually turn it off");
        disabled.remove("key");
        disabled.clear();

        cache.put("key", "rendered page");
        assertEquals("rendered page", cache.get("key", 0L),
                "while the configured instance, which is enabled, still caches");
    }

    private static WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblogHandle("myblog");
        return request;
    }

    private static WeblogPageRequest tagsRequest(String... tags) {
        WeblogPageRequest request = pageRequest();
        request.setContext("tags");
        request.setTags(List.of(tags));
        return request;
    }

    private static WeblogPageRequest customPageRequest(String param, String value) {
        WeblogPageRequest request = pageRequest();
        request.setWeblogPageName("aboutme");
        Map<String, String[]> params = new HashMap<>();
        params.put(param, new String[]{value});
        request.setCustomParams(params);
        return request;
    }
}
