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

import java.util.List;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the cache in front of the RSS and Atom feeds.
 *
 * Feeds are the most heavily and most mechanically fetched thing a Pressroom
 * install serves -- readers poll them on a timer -- so this cache does more
 * work than any other, and a key that cannot tell two feeds apart is served
 * wholesale to the wrong subscribers.
 */
public class WeblogFeedCacheTest {

    private WeblogFeedCache cache;

    @BeforeAll
    public static void requireCachingToBeOn() {
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogFeedCache.CACHE_ID + ".enabled"),
                "These tests exercise the caching path, so " + WeblogFeedCache.CACHE_ID
                        + ".enabled must be true in the test configuration");
    }

    @BeforeEach
    public void startFromAnEmptyCache() {
        cache = WeblogFeedCache.getInstance();
        cache.clear();
    }

    @Test
    public void theInstanceIsShared() {
        assertSame(cache, WeblogFeedCache.getInstance(),
                "Every servlet must reach the same cache, or nothing is cached at all");
    }

    @Test
    public void cachedFeedIsServedUntilTheWeblogChanges() {
        String key = "cache.weblogfeed:test-feed";

        cache.put(key, "rendered feed");

        assertEquals("rendered feed", cache.get(key, 0L),
                "A cached feed must be served while it is newer than the weblog's last change");
        assertNull(cache.get(key, System.currentTimeMillis() + 60_000L),
                "and withheld once the weblog has changed since it was rendered, or "
                        + "subscribers never see the new post");
    }

    @Test
    public void anUncachedKeyReadsAsNull() {
        assertNull(cache.get("cache.weblogfeed:never-cached", 0L),
                "A key that was never cached must read as null so the servlet renders it");
    }

    @Test
    public void removedFeedIsNoLongerServed() {
        String key = "cache.weblogfeed:test-feed";
        cache.put(key, "rendered feed");

        cache.remove(key);

        assertNull(cache.get(key, 0L), "A removed feed must not be served again");
    }

    @Test
    public void clearDropsEverything() {
        cache.put("cache.weblogfeed:one", "one");
        cache.put("cache.weblogfeed:two", "two");

        cache.clear();

        assertNull(cache.get("cache.weblogfeed:one", 0L), "clear() must drop every entry");
        assertNull(cache.get("cache.weblogfeed:two", 0L), "including the last one added");
    }

    @Test
    public void theSameRequestAlwaysProducesTheSameKey() {
        WeblogFeedRequest one = feedRequest();
        one.setWeblogCategoryName("Travel Notes");
        one.setLocale("en_US");
        one.setExcerpts(true);

        WeblogFeedRequest other = feedRequest();
        other.setWeblogCategoryName("Travel Notes");
        other.setLocale("en_US");
        other.setExcerpts(true);

        assertEquals(cache.generateKey(one), cache.generateKey(other),
                "The same request must produce the same key every time, or the cache "
                        + "never hits and every poll re-renders the feed");
    }

    @Test
    public void keyIsScopedToThisCache() {
        assertTrue(cache.generateKey(feedRequest()).startsWith(WeblogFeedCache.CACHE_ID + ":"),
                "Keys are prefixed with the cache id so that entries from different "
                        + "caches cannot be confused when they share a backing store");
    }

    @Test
    public void theKeyOfAPlainEntriesFeed() {
        assertEquals("cache.weblogfeed:myblog/entries/rss", cache.generateKey(feedRequest()),
                "The simplest request must produce the simplest key");
    }

    @Test
    public void theKeyOfACategoryFeedInALanguage() {
        WeblogFeedRequest request = feedRequest();
        request.setType("comments");
        request.setFormat("atom");
        request.setWeblogCategoryName("Travel Notes");
        request.setLocale("en_US");
        request.setExcerpts(true);

        // spelled out rather than described: every one of these fragments is a
        // part of the request that has to survive into the key, and a mistake
        // that drops one of them serves one feed in place of another
        assertEquals("cache.weblogfeed:myblog/comments/atom/cat/Travel%20Notes/en_US/excerpts",
                cache.generateKey(request),
                "Every part of the request must appear in the key, escaped and labelled");
    }

    @Test
    public void theKeyOfASearchFeedNarrowedByTags() {
        WeblogFeedRequest request = feedRequest();
        request.setTerm("winter storm");
        request.setTags(List.of("banana", "apple"));
        request.setLocale("de");

        assertEquals("cache.weblogfeed:myblog/entries/rss"
                        + "/search/winter%20storm"
                        + "/tags/apple+banana"
                        + "/de",
                cache.generateKey(request),
                "and the same for a search feed: term, tags and language all appear");
    }

    @Test
    public void theCacheIsSizedFromConfiguration() {
        int configuredSize = WebloggerConfig.getIntProperty(WeblogFeedCache.CACHE_ID + ".size");
        assertTrue(configuredSize > 1,
                WeblogFeedCache.CACHE_ID + ".size must be configured for this test to mean "
                        + "anything, but was " + configuredSize);

        // built here rather than read from the singleton, so that what is being
        // tested is the constructor reading the configuration
        WeblogFeedCache configured = WeblogFeedCache.newForTest(true);
        for (int i = 0; i < configuredSize; i++) {
            configured.put("key" + i, "feed " + i);
        }

        assertEquals("feed 0", configured.get("key0", 0L),
                "The cache must hold as many feeds as " + WeblogFeedCache.CACHE_ID
                        + ".size says. If the per-cache properties stop being read, every "
                        + "install silently falls back to the default size.");

        // key0 was just read, so key1 is the least recently used
        configured.put("one-too-many", "feed");
        assertNull(configured.get("key1", 0L),
                "and no more than that, or the cache grows without limit");
    }

    @Test
    public void everyPartOfTheRequestChangesTheKey() {
        String base = cache.generateKey(feedRequest());

        WeblogFeedRequest otherWeblog = feedRequest();
        otherWeblog.setWeblogHandle("other");
        assertNotEquals(base, cache.generateKey(otherWeblog), "A different weblog is a different feed");

        WeblogFeedRequest comments = feedRequest();
        comments.setType("comments");
        assertNotEquals(base, cache.generateKey(comments),
                "The comments feed is not the entries feed");

        WeblogFeedRequest atom = feedRequest();
        atom.setFormat("atom");
        assertNotEquals(base, cache.generateKey(atom),
                "Atom and RSS are different documents; serving one for the other breaks "
                        + "every reader that polls it");

        WeblogFeedRequest category = feedRequest();
        category.setWeblogCategoryName("Travel");
        assertNotEquals(base, cache.generateKey(category), "A category feed is a different feed");

        WeblogFeedRequest german = feedRequest();
        german.setLocale("de");
        assertNotEquals(base, cache.generateKey(german), "A different language is different content");

        WeblogFeedRequest excerpts = feedRequest();
        excerpts.setExcerpts(true);
        assertNotEquals(base, cache.generateKey(excerpts),
                "An excerpts-only feed holds different content than a full one");

        WeblogFeedRequest search = feedRequest();
        search.setTerm("winter");
        assertNotEquals(base, cache.generateKey(search), "A search feed is a different feed");

        WeblogFeedRequest tagged = feedRequest();
        tagged.setTags(List.of("apple"));
        assertNotEquals(base, cache.generateKey(tagged), "A tag feed is a different feed");
    }

    @Test
    public void tagOrderDoesNotSplitTheCache() {
        WeblogFeedRequest oneOrder = feedRequest();
        oneOrder.setTags(List.of("apple", "banana"));

        WeblogFeedRequest otherOrder = feedRequest();
        otherOrder.setTags(List.of("banana", "apple"));

        assertEquals(cache.generateKey(oneOrder), cache.generateKey(otherOrder),
                "The same tags in a different order select the same entries and must "
                        + "share the cached feed");
    }

    @Test
    public void anEmptyTagListIsTheSameAsNoTags() {
        WeblogFeedRequest empty = feedRequest();
        empty.setTags(List.of());

        assertEquals(cache.generateKey(feedRequest()), cache.generateKey(empty),
                "Filtering by no tags is not filtering at all");
    }

    @Test
    public void aSearchTermCannotImpersonateTheRestOfTheKey() {
        // ?q= is arbitrary text from the query string, and it used to go into
        // the key unescaped, right where the language and tags follow
        WeblogFeedRequest slashInTerm = feedRequest();
        slashInTerm.setTerm("winter/de");

        WeblogFeedRequest termAndLanguage = feedRequest();
        termAndLanguage.setTerm("winter");
        termAndLanguage.setLocale("de");

        assertNotEquals(cache.generateKey(slashInTerm), cache.generateKey(termAndLanguage),
                "A search for 'winter/de' must not produce the key of the German feed for "
                        + "'winter'. A visitor could otherwise pick any cached feed and "
                        + "overwrite it by searching for its key.");

        WeblogFeedRequest tagsInTerm = feedRequest();
        tagsInTerm.setTerm("winter/tags/apple");

        WeblogFeedRequest termAndTag = feedRequest();
        termAndTag.setTerm("winter");
        termAndTag.setTags(List.of("apple"));

        assertNotEquals(cache.generateKey(tagsInTerm), cache.generateKey(termAndTag),
                "and the same for a term that imitates the tag segment");
    }

    @Test
    public void aCategoryNamedLikeALanguageDoesNotStealItsFeed() {
        WeblogFeedRequest germanCategory = feedRequest();
        germanCategory.setWeblogCategoryName("de");

        WeblogFeedRequest germanLanguage = feedRequest();
        germanLanguage.setLocale("de");

        // both used to append a bare "/de" to the key
        assertNotEquals(cache.generateKey(germanCategory), cache.generateKey(germanLanguage),
                "The feed for the category 'de' and the German feed are different feeds");
    }

    @Test
    public void aCategoryNamedLikeASegmentLabelDoesNotStealASearch() {
        WeblogFeedRequest category = feedRequest();
        category.setWeblogCategoryName("search");
        category.setLocale("en");

        WeblogFeedRequest search = feedRequest();
        search.setTerm("en");

        assertNotEquals(cache.generateKey(category), cache.generateKey(search),
                "A category called 'search' must not produce the key of a search: "
                        + "labels in the key are only safe if the values cannot imitate them");
    }

    @Test
    public void aDisabledCacheCachesNothing() {
        // how Pressroom runs in development, and how it runs for anyone who turns
        // cache.weblogfeed.enabled off: the calls must all be no-ops
        WeblogFeedCache disabled = WeblogFeedCache.newForTest(false);

        disabled.put("key", "rendered feed");

        assertNull(disabled.get("key", 0L),
                "A disabled cache must never serve content, or turning caching off would "
                        + "not actually turn it off");
        disabled.remove("key");
        disabled.clear();

        cache.put("key", "rendered feed");
        assertEquals("rendered feed", cache.get("key", 0L),
                "while the configured instance, which is enabled, still caches");
    }

    private static WeblogFeedRequest feedRequest() {
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblogHandle("myblog");
        request.setType("entries");
        request.setFormat("rss");
        return request;
    }
}
