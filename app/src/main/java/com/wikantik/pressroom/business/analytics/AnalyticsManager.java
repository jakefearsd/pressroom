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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.pojos.Weblog;

import java.util.List;
import java.util.Map;

/**
 * Pressroom's side of analytics: registers every blog with the
 * analytics service in the background and answers, from memory, which blogs
 * are registered and under which website ID.
 *
 * <p>Only {@link #sync()} and {@link #reconcile()} talk to the service, and
 * only background threads call them (the request-sync executor, the scheduled
 * task, the startup run). Everything a page asks ({@link #isRegistered},
 * {@link #websiteIdFor}, {@link #getStatus}) is answered from memory, and the
 * traffic readers ({@link #getBlogTraffic}, {@link #getSiteTraffic},
 * {@link #entryViews}, {@link #entryViewsAllBlogs}) from the local snapshots
 * {@link #sync()} writes: no request path ever waits on the service.
 */
public interface AnalyticsManager {

    /**
     * Loads the registrations into memory and, when analytics is configured,
     * schedules the first sync, which is the backfill. Called once, from the
     * tier's initialisation.
     */
    void initialize();

    /**
     * Asks for a sync soon, on a background thread; never blocks and never
     * runs the sync on the calling thread. Requests coalesce: at most one sync
     * is in flight and one more queued behind it, and a request made while
     * one is queued is dropped (the queued one will see its change). A no-op
     * when analytics is not configured.
     */
    void requestSync();

    /**
     * Called after a blog's creation or save has committed. Requests a sync
     * only when the blog is not registered, or its name or domain differs from
     * what was last confirmed; otherwise does nothing. Never blocks, never
     * queries.
     */
    void weblogSaved(Weblog weblog);

    /**
     * Reconciles, then reads every registered blog's traffic, for each of
     * {@link #periodKeys()}, into the local snapshots. Background threads
     * only; the caller releases the unit of work afterwards. Serialised with
     * every other sync, whoever calls it.
     */
    void sync();

    /**
     * Registers every blog (and the site space) that is not yet registered, and updates any
     * whose name or domain changed since it was last confirmed. Background
     * threads only.
     */
    void reconcile();

    /** Whether this blog's website is confirmed to exist; from memory. */
    boolean isRegistered(String weblogId);

    /** The website ID this blog is registered under, or null; from memory. */
    String websiteIdFor(String weblogId);

    /** How registration (and reading) last went; from memory. */
    AnalyticsStatus getStatus();

    /**
     * The periods read and kept: {@code 24h, 7d, 30d, 90d}, then the discovery
     * window's {@code <N>d} when it is not one of them.
     *
     * @return the period keys, in that order
     */
    List<String> periodKeys();

    /**
     * One blog's snapshot for one period, from the local tables.
     *
     * @param weblogId  the blog
     * @param periodKey one of {@link #periodKeys()}
     * @return the snapshot and its state; never null
     * @throws IllegalArgumentException when the key is not a period
     */
    BlogTraffic getBlogTraffic(String weblogId, String periodKey);

    /**
     * Every blog's snapshot for one period, from the local tables.
     *
     * @param periodKey one of {@link #periodKeys()}
     * @return the blogs, totals of views and visits, and the site's top posts and sources; never null
     * @throws IllegalArgumentException when the key is not a period
     */
    SiteTraffic getSiteTraffic(String periodKey);

    /**
     * Views per entry of one blog in one period, from the local snapshot.
     *
     * @param weblogId  the blog
     * @param periodKey one of {@link #periodKeys()}
     * @return entry ID to views; entries with no recorded view are absent; empty when not configured
     */
    Map<String, Long> entryViews(String weblogId, String periodKey);

    /**
     * Whether one blog's snapshot for one period holds only the top paths
     * (ruling R23): the read filled the service's page, so an entry missing
     * from {@link #entryViews} is not known to have no views. From the local
     * snapshot.
     *
     * @return the number of paths the snapshot was cut at, or 0 when it holds every path (or there is none)
     */
    int pathsCappedAt(String weblogId, String periodKey);

    /**
     * Views per entry across every blog over the discovery window, from the
     * {@code <windowDays>d} snapshots. Views add; visitors would not.
     *
     * @param windowDays the window, 1 to 365 days
     * @return entry ID to views; empty when not configured or not read yet
     */
    Map<String, Long> entryViewsAllBlogs(int windowDays);
}
