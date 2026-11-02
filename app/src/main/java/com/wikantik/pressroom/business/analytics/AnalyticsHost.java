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
package com.wikantik.pressroom.business.analytics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Locale;

/**
 * The hostname a blog is actually served on: its custom domain, else the host
 * of the site URL, lower-cased, without a port or a trailing dot. This is the
 * value the tracker's {@code data-domains} must hold, because Umami's tracker
 * compares it with {@code location.hostname} (never a port).
 */
public final class AnalyticsHost {

    private AnalyticsHost() {
    }

    /**
     * @param customDomain the blog's custom domain, or null
     * @param siteUrl      the site's absolute URL, or null
     * @return the host, or null when neither yields one
     */
    public static String of(String customDomain, String siteUrl) {
        String host = StringUtils.trimToNull(customDomain);
        if (host == null) {
            host = hostOf(siteUrl);
        }
        return normalise(host);
    }

    private static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a blog's own host for the tracker's data-domains list, which the browser "
                    + "compares with location.hostname; never used to decide access.")
    private static String normalise(String host) {
        if (host == null) {
            return null;
        }
        String lower = StringUtils.stripEnd(host.trim().toLowerCase(Locale.ROOT), ".");
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.startsWith("[")) {
            // an IPv6 literal: its colons are not a port
            return lower;
        }
        int colon = lower.indexOf(':');
        String bare = colon < 0 ? lower : lower.substring(0, colon);
        return bare.isEmpty() ? null : bare;
    }
}
