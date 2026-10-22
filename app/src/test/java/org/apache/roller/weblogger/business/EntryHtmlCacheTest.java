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
package org.apache.roller.weblogger.business;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.roller.weblogger.business.EntryHtmlCache.Inputs;
import org.apache.roller.weblogger.business.EntryHtmlCache.Part;
import org.apache.roller.weblogger.config.StaticConfigOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EntryHtmlCache}: one slot per entry and host, valid only for the
 * inputs it was rendered from, bounded by bytes and by age.
 */
class EntryHtmlCacheTest {

    private static final Inputs V1 = new Inputs(1000L, 500L, "text", "summary");

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private EntryHtmlCache cache(long maxBytes, long maxSlotBytes) {
        return new EntryHtmlCache(maxBytes, maxSlotBytes, 60_000L, now::get);
    }

    @Test
    void aPartIsServedOnlyForTheInputsItWasRenderedFrom() {
        EntryHtmlCache cache = cache(100_000, 10_000);
        cache.put("k", V1, Part.TEXT, "<p>text</p>");

        assertEquals("<p>text</p>", cache.get("k", V1, Part.TEXT));
        assertEquals("<p>text</p>", cache.get("k", new Inputs(1000L, 500L, new String("text"), "summary"), Part.TEXT),
                "inputs are compared by value, not identity");
        assertNull(cache.get("k", new Inputs(1001L, 500L, "text", "summary"), Part.TEXT),
                "a moved weblog lastModified is a miss");
        assertNull(cache.get("k", new Inputs(1000L, 501L, "text", "summary"), Part.TEXT),
                "a moved entry updateTime is a miss");
        assertNull(cache.get("k", new Inputs(1000L, 500L, "text!", "summary"), Part.TEXT),
                "different text is a miss even under the same stamps");
        assertNull(cache.get("k", new Inputs(1000L, 500L, "text", null), Part.TEXT),
                "different summary is a miss even under the same stamps");
        assertNull(cache.get("k", V1, Part.SUMMARY), "a part never rendered is a miss");
        assertNull(cache.get("other", V1, Part.TEXT), "another slot is a miss");
    }

    @Test
    void partsOfOneEntryShareItsSlotAndStaleInputsStartItAfresh() {
        EntryHtmlCache cache = cache(100_000, 10_000);
        cache.put("k", V1, Part.TEXT, "<p>text</p>");
        cache.put("k", V1, Part.DISPLAY, "<p>display</p>");

        assertEquals("<p>text</p>", cache.get("k", V1, Part.TEXT), "a second part keeps the first");
        assertEquals("<p>display</p>", cache.get("k", V1, Part.DISPLAY));

        Inputs v2 = new Inputs(2000L, 600L, "text 2", "summary");
        cache.put("k", v2, Part.SUMMARY, "<p>summary</p>");

        assertEquals("<p>summary</p>", cache.get("k", v2, Part.SUMMARY));
        assertNull(cache.get("k", v2, Part.TEXT),
                "a part rendered from the superseded inputs must not carry over into the new version");
        assertNull(cache.get("k", V1, Part.TEXT), "nor survive for the old inputs");
    }

    @Test
    void aSlotExpiresAfterTheTimeoutEvenWhenNothingMoved() {
        EntryHtmlCache cache = cache(100_000, 10_000);
        cache.put("k", V1, Part.TEXT, "<p>text</p>");

        now.addAndGet(59_999L);
        assertEquals("<p>text</p>", cache.get("k", V1, Part.TEXT));
        cache.put("k", V1, Part.DISPLAY, "<p>display</p>");

        now.addAndGet(1L);
        assertNull(cache.get("k", V1, Part.TEXT),
                "the timeout is the backstop for an input that changes without moving a stamp");
        assertNull(cache.get("k", V1, Part.DISPLAY),
                "and it runs from the slot's first render, not from the latest part added to it");
    }

    @Test
    void theLeastRecentlyUsedSlotIsEvictedOnceTheByteBudgetIsExceeded() {
        // Each slot weighs 2 bytes per char of key + text + summary + html:
        // 2 * (2 + 4 + 7 + 10) = 46 bytes. Three fit in 140; a fourth does not.
        EntryHtmlCache cache = cache(140, 140);
        String html = "<p>abc</p>";
        cache.put("k1", V1, Part.TEXT, html);
        cache.put("k2", V1, Part.TEXT, html);
        cache.put("k3", V1, Part.TEXT, html);
        cache.get("k1", V1, Part.TEXT); // k2 is now the least recently used

        cache.put("k4", V1, Part.TEXT, html);

        assertNull(cache.get("k2", V1, Part.TEXT), "the least recently used slot is evicted");
        assertEquals(html, cache.get("k1", V1, Part.TEXT));
        assertEquals(html, cache.get("k3", V1, Part.TEXT));
        assertEquals(html, cache.get("k4", V1, Part.TEXT));
        assertEquals(138L, cache.weight(), "three slots of 46 bytes");
    }

    @Test
    void aSlotOverThePerSlotCapIsNotKeptAndTheVersionItSupersedesIsDropped() {
        EntryHtmlCache cache = cache(100_000, 46);
        cache.put("k1", V1, Part.TEXT, "<p>abc</p>");
        assertEquals("<p>abc</p>", cache.get("k1", V1, Part.TEXT), "46 bytes is at the cap");

        cache.put("k1", V1, Part.DISPLAY, "x");
        assertNull(cache.get("k1", V1, Part.DISPLAY), "a slot grown past the cap is not kept");
        assertNull(cache.get("k1", V1, Part.TEXT), "and the slot it would have replaced is gone too");
        assertEquals(0L, cache.weight());
    }

    @Test
    void replacingASlotReleasesTheWeightOfTheOneItReplaces() {
        EntryHtmlCache cache = cache(100_000, 10_000);
        cache.put("k", V1, Part.TEXT, "<p>abc</p>");
        cache.put("k", new Inputs(2L, 2L, "text", "summary"), Part.TEXT, "<p>abc</p>");

        assertEquals(44L, cache.weight(), "the superseded slot's bytes are not counted twice");
    }

    @Test
    void rerenderingAPartCountsOnlyItsNewSize() {
        EntryHtmlCache cache = cache(100_000, 10_000);
        cache.put("k1", V1, Part.TEXT, "<p>abc</p>");       // 2 * (2 + 4 + 7 + 10) = 46
        cache.put("k1", V1, Part.TEXT, "<p>abcdef</p>");    // the 10-char part becomes 13

        assertEquals(52L, cache.weight(), "the replaced part's bytes are released, not kept on the slot");
        assertEquals("<p>abcdef</p>", cache.get("k1", V1, Part.TEXT));
    }

    @Test
    void aDisabledCacheMissesEverything() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("cache.entryhtml.enabled", "false")) {
            EntryHtmlCache cache = EntryHtmlCache.fromConfig();
            cache.put("k", V1, Part.TEXT, "<p>text</p>");

            assertNull(cache.get("k", V1, Part.TEXT));
            assertEquals(0L, cache.weight());
        }
    }

    @Test
    void theConfiguredBudgetBoundsTheCacheAndOneSlotIsAtMostASixteenthOfIt() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("cache.entryhtml.maxBytes", "1600")) {
            EntryHtmlCache cache = EntryHtmlCache.fromConfig();
            cache.put("fits", V1, Part.TEXT, "x".repeat(35)); // 2 * (4 + 4 + 7 + 35) = 100
            cache.put("over", V1, Part.TEXT, "x".repeat(37)); // 2 * (4 + 4 + 7 + 37) = 104

            assertEquals("x".repeat(35), cache.get("fits", V1, Part.TEXT), "100 bytes is at a sixteenth of 1600");
            assertNull(cache.get("over", V1, Part.TEXT), "104 bytes is over it");
        }
    }

    @Test
    void theShippedDefaultsAreSixteenMegabytesAndAnHour() {
        EntryHtmlCache cache = EntryHtmlCache.fromConfig();
        assertEquals(16L * 1024 * 1024, cache.maxBytes());
        assertEquals(3_600_000L, cache.timeoutMillis());
    }
}
