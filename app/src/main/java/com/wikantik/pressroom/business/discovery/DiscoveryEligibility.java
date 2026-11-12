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
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * Which posts and blogs one blog's pages may offer to another blog's
 * readers: the one rule behind the front page, "more from the site" and a
 * post page's cross-blog Nearby ({@code docs/dev/discovery.md},
 * "Eligibility"). Pure.
 */
public final class DiscoveryEligibility {

    private DiscoveryEligibility() {
    }

    /** A published, indexable post of an eligible blog. */
    public static boolean isEligible(WeblogEntry entry) {
        return entry != null
                && entry.getStatus() == PubStatus.PUBLISHED
                && !Boolean.TRUE.equals(entry.getNoindex())
                && isEligible(entry.getWebsite());
    }

    /** A visible, active blog that is not the site space. */
    public static boolean isEligible(Weblog weblog) {
        return weblog != null
                && !weblog.isSiteSpace()
                && Boolean.TRUE.equals(weblog.getVisible())
                && Boolean.TRUE.equals(weblog.getActive());
    }
}
