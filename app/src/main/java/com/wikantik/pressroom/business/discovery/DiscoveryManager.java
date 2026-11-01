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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

import java.util.List;

/**
 * The discovery picker: a popularity snapshot (eligible entries and their
 * views over the window) and the selections built from it for the front page
 * and for each blog's "more from the site" block.
 *
 * <p>Eligible means PUBLISHED, not {@code noindex}, on a visible, active
 * weblog that is not the site space. Selections hold for one snapshot
 * generation, and anything that stops being eligible before the next one is
 * dropped from them.
 */
public interface DiscoveryManager {

    /**
     * Rebuilds the snapshot: candidates from Pressroom, scores from the
     * traffic source. Never throws. If traffic cannot be read the previous
     * scores are kept and the failure is recorded in {@link #getStatus()}.
     */
    void refresh();

    /** How the last refresh went. */
    DiscoveryStatus getStatus();

    /** The front page's sections; the first call builds the snapshot if none exists. */
    FrontPageSelection selectFrontPage();

    /**
     * Up to {@code count} posts from weblogs other than {@code current}, for
     * that weblog's "more from the site" block.
     */
    List<WeblogEntry> selectMoreFromSite(Weblog current, int count);
}
