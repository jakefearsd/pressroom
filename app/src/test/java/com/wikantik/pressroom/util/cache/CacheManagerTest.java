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

package com.wikantik.pressroom.util.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CacheManager, which builds Pressroom's caches and fans invalidation
 * events out to everything that registered an interest.
 *
 * The fan-out is the part worth guarding. Every manager that changes a weblog
 * entry, category, template, user or weblog tells the CacheManager, and the
 * CacheManager tells everything that registered an interest to drop what it is
 * holding. A handler that silently stops being called does not fail anything --
 * it just leaves readers looking at yesterday's blog.
 *
 * Note that of the three render caches only SiteWideCache is such a handler;
 * the page and feed caches register nothing and expire lazily against
 * weblog.lastModified instead. RenderCacheHandlerRegistrationTest pins that
 * split.
 */
public class CacheManagerTest {

    /**
     * The handler registry is global and permanent: handlers are added for the
     * life of the JVM and there is no way to remove one. So that these tests
     * observe their own fan-out and nothing else -- and so that the caches
     * registered by the rest of the suite are handed back untouched -- the
     * registry is emptied for the duration of each test and restored after.
     */
    private Set<CacheHandler> registeredHandlers;

    private RecordingHandler first;
    private RecordingHandler second;

    @BeforeEach
    public void isolateTheHandlerRegistry() {
        registeredHandlers = new HashSet<>(CacheManager.handlers());
        CacheManager.handlers().clear();

        first = new RecordingHandler();
        second = new RecordingHandler();
    }

    @AfterEach
    public void restoreTheHandlerRegistry() {
        CacheManager.handlers().clear();
        CacheManager.handlers().addAll(registeredHandlers);
    }

    @Test
    public void everyRegisteredHandlerHearsAboutEveryKindOfChange() {
        CacheManager.registerHandler(first);
        CacheManager.registerHandler(second);
        // a handler that cares about nothing must still survive the fan-out;
        // all six CacheHandler methods have do-nothing defaults
        CacheManager.registerHandler(new CacheHandler() { });

        Weblog weblog = weblog("myblog");
        WeblogEntry entry = entry(weblog);

        CacheManager.invalidate(entry);
        CacheManager.invalidate(weblog);
        CacheManager.invalidate(new User());
        CacheManager.invalidate(category(weblog));
        CacheManager.invalidate(template(weblog));

        for (RecordingHandler handler : List.of(first, second)) {
            assertEquals(
                    List.of("entry", "weblog", "user", "category", "template"),
                    handler.invalidations,
                    "Every registered handler must be told about every kind of change. "
                            + "A missing event here is a cache that keeps serving content "
                            + "that no longer exists.");
        }
    }

    @Test
    public void invalidationPassesTheChangedObjectThrough() {
        CacheManager.registerHandler(first);
        Weblog weblog = weblog("myblog");

        CacheManager.invalidate(weblog);

        assertEquals(1, first.weblogs.size(), "The handler must be called exactly once");
        assertEquals(weblog, first.weblogs.get(0),
                "and handed the weblog that changed -- handlers decide what to drop by "
                        + "looking at it");
    }

    @Test
    public void registeringTheSameHandlerTwiceDoesNotDoubleTheWork() {
        CacheManager.registerHandler(first);
        CacheManager.registerHandler(first);

        CacheManager.invalidate(weblog("myblog"));

        assertEquals(1, first.weblogs.size(),
                "A handler registered twice must still be invalidated once");
    }

    @Test
    public void aNullHandlerIsIgnoredRatherThanRegistered() {
        CacheManager.registerHandler(null);
        CacheManager.registerHandler(first);

        // if null had been registered, this fan-out would throw
        CacheManager.invalidate(weblog("myblog"));

        assertEquals(1, CacheManager.handlers().size(),
                "A null handler must not be added to the registry");
        assertEquals(1, first.weblogs.size(), "and the real handler must still be called");
    }

    @Test
    public void constructingACacheRegistersItsHandler() {
        Cache cache = CacheManager.constructCache(first, props("cache.test.handler"));

        assertNotNull(cache, "constructCache must return a cache");
        CacheManager.invalidate(weblog("myblog"));

        assertEquals(1, first.weblogs.size(),
                "A cache built with a handler must have that handler registered for it; "
                        + "otherwise the cache is never told to drop anything");
    }

    @Test
    public void aCacheCanBeBuiltWithoutAHandler() {
        // the page and feed caches do this: they expire lazily and have nothing
        // to do when an object changes
        Cache cache = CacheManager.constructCache(null, props("cache.test.nohandler"));

        assertNotNull(cache, "A cache with no handler must still be built");
        assertTrue(CacheManager.handlers().isEmpty(),
                "and nothing must be registered on its behalf");
    }

    @Test
    public void theDefaultFactoryBuildsAnExpiringCache() {
        Cache cache = CacheManager.constructCache(null, props("cache.test.default"));

        assertTrue(cache instanceof ExpiringLRUCacheImpl,
                "cache.defaultFactory names the expiring LRU factory, so caches that do "
                        + "not ask for anything else must expire");
        assertEquals("cache.test.default", cache.getId(), "and must carry the id we asked for");
    }

    @Test
    public void aCacheCanAskForItsOwnFactory() {
        Map<String, String> props = props("cache.test.custom");
        props.put("factory", LRUCacheFactoryImpl.class.getName());

        Cache cache = CacheManager.constructCache(null, props);

        assertFalse(cache instanceof ExpiringLRUCacheImpl,
                "A cache that names its own factory must get one built by that factory, "
                        + "not by the system default");
        assertTrue(cache instanceof LRUCacheImpl, "and it must still be a usable cache");
    }

    @Test
    public void anUnusableFactoryFallsBackToTheDefaultInsteadOfFailing() {
        Map<String, String> missing = props("cache.test.missing");
        missing.put("factory", "com.example.NoSuchCacheFactory");

        Map<String, String> notAFactory = props("cache.test.notafactory");
        notAFactory.put("factory", "java.lang.String");

        Map<String, String> returnsNothing = props("cache.test.null");
        returnsNothing.put("factory", NullReturningFactory.class.getName());

        // a typo in a factory name must cost the custom caching strategy, not
        // the page it was caching
        assertNotNull(CacheManager.constructCache(null, missing),
                "A factory class that does not exist must fall back to the default factory");
        assertNotNull(CacheManager.constructCache(null, notAFactory),
                "A factory class that is not a CacheFactory must fall back to the default");
        assertNotNull(CacheManager.constructCache(null, returnsNothing),
                "A factory that hands back nothing must fall back to the default");
    }

    @Test
    public void clearingOneCacheLeavesTheOthersAlone() {
        Cache kept = CacheManager.constructCache(null, props("cache.test.kept"));
        Cache flushed = CacheManager.constructCache(null, props("cache.test.flushed"));
        kept.put("key", "value");
        flushed.put("key", "value");

        CacheManager.clear("cache.test.flushed");

        assertNull(flushed.get("key"), "The named cache must be flushed");
        assertEquals("value", kept.get("key"),
                "and no other cache may be: flushing a cache costs a re-render of "
                        + "everything in it");
    }

    @Test
    public void clearingACacheThatDoesNotExistIsHarmless() {
        Cache cache = CacheManager.constructCache(null, props("cache.test.survivor"));
        cache.put("key", "value");

        CacheManager.clear("cache.that.was.never.registered");

        assertEquals("value", cache.get("key"),
                "An unknown cache id must be ignored quietly rather than throwing -- "
                        + "cache ids come from configuration");
    }

    @Test
    public void clearingEverythingFlushesEveryRegisteredCache() {
        Cache one = CacheManager.constructCache(null, props("cache.test.one"));
        Cache two = CacheManager.constructCache(null, props("cache.test.two"));
        one.put("key", "value");
        two.put("key", "value");

        CacheManager.clear();

        assertNull(one.get("key"), "clear() must flush every registered cache");
        assertNull(two.get("key"), "including the last one registered");
    }

    @Test
    public void statsAreReportedPerCache() {
        Cache cache = CacheManager.constructCache(null, props("cache.test.stats"));
        cache.put("key", "value");
        cache.get("key");

        Map<String, Map<String, Object>> stats = CacheManager.getStats();

        assertTrue(stats.containsKey("cache.test.stats"),
                "Every registered cache must appear in the stats the admin page reads, "
                        + "filed under its own id");
        assertEquals(1.0, stats.get("cache.test.stats").get("hits"),
                "and must report its own counters");
    }

    @Test
    public void shutdownIsSafeToCall() {
        // called from the servlet context listener on the way down; there is
        // nothing to release today, but it must not start throwing
        CacheManager.shutdown();

        Cache cache = CacheManager.constructCache(null, props("cache.test.shutdown"));
        cache.put("key", "value");
        assertEquals("value", cache.get("key"), "Caches must still work after shutdown()");
    }

    private static Map<String, String> props(String id) {
        Map<String, String> props = new HashMap<>();
        props.put("id", id);
        props.put("size", "10");
        props.put("timeout", "3600");
        return props;
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogEntry entry(Weblog weblog) {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("an-entry");
        return entry;
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

    /** Notes down what it was told, in the order it was told. */
    static final class RecordingHandler implements CacheHandler {

        final List<String> invalidations = new ArrayList<>();
        final List<Weblog> weblogs = new ArrayList<>();

        @Override
        public void invalidate(WeblogEntry entry) {
            invalidations.add("entry");
        }

        @Override
        public void invalidate(Weblog weblog) {
            invalidations.add("weblog");
            weblogs.add(weblog);
        }

        @Override
        public void invalidate(User user) {
            invalidations.add("user");
        }

        @Override
        public void invalidate(WeblogCategory category) {
            invalidations.add("category");
        }

        @Override
        public void invalidate(WeblogTemplate template) {
            invalidations.add("template");
        }
    }

    /** A factory that builds nothing, to prove the fallback to the default. */
    public static final class NullReturningFactory implements CacheFactory {

        public NullReturningFactory() {
        }

        @Override
        public Cache constructCache(Map<String, ?> properties) {
            return null;
        }
    }
}
