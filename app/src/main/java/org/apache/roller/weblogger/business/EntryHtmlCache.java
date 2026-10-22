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

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.apache.roller.weblogger.config.WebloggerConfig;

/**
 * Finished entry HTML -- shortcodes, then Markdown, then the sanitizer -- one
 * slot per entry and host, each valid only for the inputs it was rendered
 * from. {@link EntryRenderer}'s live path is the only reader and writer; the
 * editor and API previews and the newsletter never touch it.
 *
 * <p><b>What a render depends on, and how each reaches this cache.</b> The
 * slot key ({@code EntryRenderer}'s to build) carries the entry id, whether
 * sanitizing is on, the weblog's absolute url and the relative context url,
 * so a changed {@code site.absoluteurl}, captured address, context path or
 * custom domain is a different slot, never a wrong hit. The slot is
 * validated by {@link Inputs}: the entry's own text and summary (compared by
 * value, so text that never reached the database cannot be served as if it
 * had), its {@code updateTime}, and its weblog's {@code lastModified}, which
 * every published entry save and every change to the weblog's own media,
 * directories, settings and business moves. Only published entries are kept
 * -- a draft's saves do not move that stamp -- and a render that read
 * another weblog's data ({@code [image]} of a foreign id) is not kept at all.
 *
 * <p>Why a slot validated by its inputs rather than a key that includes
 * them: an edited entry replaces its slot in place instead of leaving the old
 * version to age out, so the cache holds at most one version per entry and
 * host. The three parts an entry is shown as -- text, summary, display
 * content -- share the slot and fill it lazily; new inputs start it afresh.
 *
 * <p>Bounds: the total weight of every slot ({@code cache.entryhtml.maxBytes},
 * least recently used first out), one slot at most a sixteenth of that (a
 * larger render is still served, just never kept, and the version it
 * supersedes is dropped), and the age of a slot ({@code
 * cache.entryhtml.timeout} seconds from its first render) -- the backstop for
 * anything that one day reaches a render without moving a stamp, as the
 * rendition regeneration once did. Weight is two bytes per char of the key,
 * the sources and every rendered part: the UTF-16 upper bound (compact
 * Latin-1 strings take half), and an overcount where a slot's sources are the
 * entity's own strings.
 *
 * <p>Like the per-weblog render caches it is not built through
 * {@code CacheManager}, whose caches are bounded by count rather than bytes;
 * so {@code CacheManager.clear()} does not reach it, and does not need to:
 * expiry is the inputs' job, and the maintenance "flush cache" action moves
 * {@code lastModified}.
 *
 * <p>Thread safety: every operation synchronizes on the cache; values are
 * immutable. Two concurrent misses both render and the later put wins; a
 * part one of them added may be lost, which is only a later miss.
 */
public final class EntryHtmlCache {

    static final String CACHE_ID = "cache.entryhtml";

    /**
     * The default budget: some 4,500-7,000 typical entries (a ~500-char
     * text, its two rendered parts and the key, at two bytes a char).
     */
    static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    /** The default slot age, the same hour as the page and feed caches. */
    static final long DEFAULT_TIMEOUT_SECONDS = 3600;

    /** One slot is at most this fraction of the budget. */
    private static final int SLOT_SHARE = 16;

    /** The forms an entry is shown in, each cached on its own. */
    public enum Part { TEXT, SUMMARY, DISPLAY }

    /**
     * Everything a slot is valid for besides its key.
     *
     * @param weblogLastModified the entry's weblog's {@code lastModified}
     * @param entryUpdateTime    the entry's {@code updateTime}
     * @param text               the entry's source text, compared by value
     * @param summary            the entry's source summary, compared by value
     */
    public record Inputs(long weblogLastModified, long entryUpdateTime, String text, String summary) {

        private long chars() {
            return length(text) + length(summary);
        }
    }

    /** A budget of 0 disables the cache; every operation is then a miss. */
    private final long maxBytes;
    private final long maxSlotBytes;
    private final long timeoutMillis;
    private final LongSupplier clock;

    /** Access-ordered, so iteration starts at the least recently used slot. */
    private final Map<String, Slot> slots = new LinkedHashMap<>(256, 0.75f, true);
    private long weight;

    /**
     * @param maxBytes      the budget for every slot together; 0 disables the cache
     * @param maxSlotBytes  the most one slot may weigh
     * @param timeoutMillis how long a slot lives from its first render
     * @param clock         the time source, for the timeout
     */
    public EntryHtmlCache(long maxBytes, long maxSlotBytes, long timeoutMillis, LongSupplier clock) {
        this.maxBytes = maxBytes;
        this.maxSlotBytes = Math.min(maxSlotBytes, maxBytes);
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    /** The cache as {@code cache.entryhtml.*} in the Roller properties configures it. */
    public static EntryHtmlCache fromConfig() {
        if (!WebloggerConfig.getBooleanProperty(CACHE_ID + ".enabled", true)) {
            return disabled();
        }
        long budget = Long.parseLong(WebloggerConfig.getProperty(CACHE_ID + ".maxBytes",
                Long.toString(DEFAULT_MAX_BYTES)).trim());
        long timeoutSeconds = Long.parseLong(WebloggerConfig.getProperty(CACHE_ID + ".timeout",
                Long.toString(DEFAULT_TIMEOUT_SECONDS)).trim());
        return new EntryHtmlCache(budget, budget / SLOT_SHARE, timeoutSeconds * 1000L, System::currentTimeMillis);
    }

    /** A cache that keeps nothing. */
    public static EntryHtmlCache disabled() {
        return new EntryHtmlCache(0, 0, 0, System::currentTimeMillis);
    }

    /** The part rendered for exactly these inputs under this key, or null. */
    synchronized String get(String key, Inputs inputs, Part part) {
        if (maxBytes <= 0) {
            return null;
        }
        Slot slot = slots.get(key);
        if (slot == null || !slot.isFor(inputs, clock.getAsLong())) {
            return null;
        }
        return slot.parts.get(part);
    }

    /**
     * Keeps {@code html} as this part of the slot for these inputs, adding to
     * the slot when it holds the same inputs and is unexpired, starting it
     * afresh otherwise. A slot that would weigh more than a sixteenth of the
     * budget is removed instead, so the version it supersedes does not
     * linger either.
     */
    synchronized void put(String key, Inputs inputs, Part part, String html) {
        if (maxBytes <= 0 || html == null) {
            return;
        }
        long now = clock.getAsLong();
        Slot existing = slots.remove(key);
        if (existing != null) {
            weight -= existing.weight;
        }
        Slot base = existing != null && existing.isFor(inputs, now)
                ? existing
                : new Slot(inputs, now + timeoutMillis, new EnumMap<>(Part.class), weigh(key, inputs));
        Slot slot = base.with(part, html);
        if (slot.weight > maxSlotBytes) {
            return;
        }
        slots.put(key, slot);
        weight += slot.weight;
        Iterator<Slot> eldest = slots.values().iterator();
        while (weight > maxBytes && eldest.hasNext()) {
            weight -= eldest.next().weight;
            eldest.remove();
        }
    }

    /** The total weight of every slot held, in bytes. */
    synchronized long weight() {
        return weight;
    }

    long maxBytes() {
        return maxBytes;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    private static long weigh(String key, Inputs inputs) {
        return 2L * (key.length() + inputs.chars());
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** One entry's rendered parts, immutable once held. */
    private record Slot(Inputs inputs, long expiresAt, Map<Part, String> parts, long weight) {

        boolean isFor(Inputs current, long now) {
            return now < expiresAt && inputs.equals(current);
        }

        Slot with(Part part, String html) {
            Map<Part, String> next = new EnumMap<>(Part.class);
            next.putAll(parts);
            String replaced = next.put(part, html);
            return new Slot(inputs, expiresAt, next, weight + 2L * (html.length() - length(replaced)));
        }
    }
}
