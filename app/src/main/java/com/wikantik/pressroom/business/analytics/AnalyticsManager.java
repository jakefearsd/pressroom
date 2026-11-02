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

/**
 * Pressroom's side of analytics: registers every blog with the
 * analytics service in the background and answers, from memory, which blogs
 * are registered and under which website ID.
 *
 * <p>Only {@link #sync()} and {@link #reconcile()} talk to the service, and
 * only background threads call them (the request-sync executor, the scheduled
 * task, the startup run). Everything a page asks ({@link #isRegistered},
 * {@link #websiteIdFor}, {@link #getStatus}) is answered from memory.
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
     * Reconciles, then reads traffic. Background threads only; the caller
     * releases the unit of work afterwards.
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
}
