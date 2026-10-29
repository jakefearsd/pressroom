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

package com.wikantik.pressroom.business;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import com.wikantik.pressroom.config.WebloggerConfig;

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
 * approximately least recently read first out; once over it, an eviction
 * frees a further sixteenth so the next is a while off), one slot at most a
 * sixteenth of that (a
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
 * <p>Thread safety: every entry render on every thread reads this cache, so
 * a read takes no lock at all -- a cache-wide monitor here was the top
 * contention site under load, every render convoying behind whichever thread
 * held it. Slots live in a {@link ConcurrentHashMap} and are immutable but
 * for a last-read stamp; a put replaces its key's slot atomically
 * ({@code compute}) and moves the weight counter in the same step, so the
 * counter always describes what is held. Eviction runs only over budget, by
 * one thread at a time ({@code tryLock}: nobody waits for it), and removes
 * approximately least recently read slots first. Two concurrent misses both
 * render and the later put wins; a part one of them added may be lost, which
 * is only a later miss.
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
    /** An eviction runs the weight down to this, so the next one is a while off. */
    private final long lowWater;
    private final long timeoutMillis;
    private final LongSupplier clock;

    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>(256);
    /** The sum of every held slot's weight, moved only alongside the map change it describes. */
    private final AtomicLong weight = new AtomicLong();
    /** Orders reads for the approximate LRU; a counter, so ties cannot happen. */
    private final AtomicLong accessClock = new AtomicLong();
    /** One evictor at a time; anyone else who finds the cache over budget leaves it to that one. */
    private final ReentrantLock evicting = new ReentrantLock();

    /** Test seam: runs inside eviction, before any slot is removed. */
    volatile Runnable evictionHook = () -> { };

    /**
     * Evicts exactly down to the budget.
     *
     * @param maxBytes      the budget for every slot together; 0 disables the cache
     * @param maxSlotBytes  the most one slot may weigh
     * @param timeoutMillis how long a slot lives from its first render
     * @param clock         the time source, for the timeout
     */
    public EntryHtmlCache(long maxBytes, long maxSlotBytes, long timeoutMillis, LongSupplier clock) {
        this(maxBytes, maxSlotBytes, maxBytes, timeoutMillis, clock);
    }

    /**
     * @param lowWater what an eviction runs the weight down to, at most {@code maxBytes}
     */
    EntryHtmlCache(long maxBytes, long maxSlotBytes, long lowWater, long timeoutMillis, LongSupplier clock) {
        this.maxBytes = maxBytes;
        this.maxSlotBytes = Math.min(maxSlotBytes, maxBytes);
        this.lowWater = Math.min(lowWater, maxBytes);
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
        // An eviction frees a slot's worth below the budget, so a cache at
        // its budget sweeps once per sixteenth of new renders, not per render.
        return new EntryHtmlCache(budget, budget / SLOT_SHARE, budget - budget / SLOT_SHARE,
                timeoutSeconds * 1000L, System::currentTimeMillis);
    }

    /** A cache that keeps nothing. */
    public static EntryHtmlCache disabled() {
        return new EntryHtmlCache(0, 0, 0, System::currentTimeMillis);
    }

    /** The part rendered for exactly these inputs under this key, or null. Takes no lock. */
    String get(String key, Inputs inputs, Part part) {
        if (maxBytes <= 0) {
            return null;
        }
        Slot slot = slots.get(key);
        if (slot == null || !slot.isFor(inputs, clock.getAsLong())) {
            return null;
        }
        String html = slot.parts.get(part);
        if (html != null) {
            slot.lastAccess = accessClock.incrementAndGet();
        }
        return html;
    }

    /**
     * Keeps {@code html} as this part of the slot for these inputs, adding to
     * the slot when it holds the same inputs and is unexpired, starting it
     * afresh otherwise. A slot that would weigh more than a sixteenth of the
     * budget is removed instead, so the version it supersedes does not
     * linger either. Waits only on a put to the same key, never on a read or
     * an eviction.
     */
    void put(String key, Inputs inputs, Part part, String html) {
        if (maxBytes <= 0 || html == null) {
            return;
        }
        long now = clock.getAsLong();
        slots.compute(key, (k, existing) -> {
            Slot base = existing != null && existing.isFor(inputs, now)
                    ? existing
                    : new Slot(inputs, now + timeoutMillis, new EnumMap<>(Part.class), weigh(key, inputs));
            Slot slot = base.with(part, html);
            long released = existing == null ? 0 : existing.weight;
            if (slot.weight > maxSlotBytes) {
                weight.addAndGet(-released);
                return null;
            }
            slot.lastAccess = accessClock.incrementAndGet();
            weight.addAndGet(slot.weight - released);
            return slot;
        });
        if (weight.get() > maxBytes) {
            evict();
        }
    }

    /**
     * Runs the weight down to the low-water mark, least recently read first,
     * by one thread at a time. A thread that finds an eviction already
     * running leaves the cache to it and returns: the evictor checks the
     * weight again after letting go, so a put that landed meanwhile is not
     * left over budget. The order is a snapshot of the read stamps --
     * approximate LRU, which is all a cache needs.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // identity IS the contract: only the very
    // version the snapshot saw may be evicted, never one a put has replaced since
    private void evict() {
        // a sweep that removed nothing ends it: the counter could then never
        // come down, and spinning on it would hang this put forever
        boolean progress = true;
        while (progress && weight.get() > maxBytes && evicting.tryLock()) {
            progress = false;
            try {
                evictionHook.run();
                // stamps copied first: reads keep moving them, and a sort
                // over changing keys breaks its contract
                List<Candidate> byAge = new ArrayList<>(slots.size());
                slots.forEach((key, slot) -> byAge.add(new Candidate(key, slot, slot.lastAccess)));
                byAge.sort(Comparator.comparingLong(Candidate::lastAccess));
                Iterator<Candidate> eldest = byAge.iterator();
                while (weight.get() > lowWater && eldest.hasNext()) {
                    Candidate victim = eldest.next();
                    // only the version seen: a slot replaced since is someone's fresh render
                    slots.computeIfPresent(victim.key(), (k, held) -> {
                        if (held != victim.slot()) {
                            return held;
                        }
                        weight.addAndGet(-held.weight);
                        return null;
                    });
                    progress = true;
                }
            } finally {
                evicting.unlock();
            }
        }
    }

    /** The total weight of every slot held, in bytes. */
    long weight() {
        return weight.get();
    }

    /** The weight of the slots actually held, summed afresh: what {@link #weight()} must agree with. */
    long weighHeld() {
        return slots.values().stream().mapToLong(slot -> slot.weight).sum();
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

    /** A slot as an eviction found it. */
    private record Candidate(String key, Slot slot, long lastAccess) {
    }

    /** One entry's rendered parts, immutable once held but for when it was last read. */
    private static final class Slot {

        private final Inputs inputs;
        private final long expiresAt;
        private final Map<Part, String> parts;
        private final long weight;
        /** The access clock at the last read or write; only orders eviction. */
        private volatile long lastAccess;

        Slot(Inputs inputs, long expiresAt, Map<Part, String> parts, long weight) {
            this.inputs = inputs;
            this.expiresAt = expiresAt;
            this.parts = parts;
            this.weight = weight;
        }

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
