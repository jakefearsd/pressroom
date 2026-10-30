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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * A blog's booking link: which URL it is, and how it is tagged for
 * attribution. Shared by the {@code [cta]} shortcode (when it has no
 * {@code href}) and the footer business card, so both tag identically.
 */
public final class BookingLink {

    private BookingLink() {
    }

    /** The same schemes the entry-content sanitizer accepts for anchor hrefs. */
    // No ALLOW_LOCAL_URLS: a localhost or intranet booking link would pass a laxer check and then be
    // deleted by the entry sanitizer, so it is refused up front. Keep this validator and the
    // sanitizer's in step (docs/dev/content-features.md).
    private static final UrlValidator URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    /** True for an absolute http(s) URL; the one rule every booking link obeys. */
    public static boolean isHttpUrl(String url) {
        return url != null && URL_VALIDATOR.isValid(url);
    }

    /**
     * weblog.bookingUrl, else weblog.business.bookingUrl, else null. A value
     * that is not an absolute http(s) URL is skipped, so a javascript: or
     * data: URL can never reach an href.
     */
    public static String resolve(Weblog w) {
        if (w == null) {
            return null;
        }
        String own = StringUtils.trimToNull(w.getBookingUrl());
        if (isHttpUrl(own)) {
            return own;
        }
        String shared = w.getBusiness() == null ? null
                : StringUtils.trimToNull(w.getBusiness().getBookingUrl());
        return isHttpUrl(shared) ? shared : null;
    }

    /**
     * {@code href} with the missing utm parameters appended before the
     * fragment: source = the weblog handle, medium = blog, campaign = the
     * entry anchor (each skipped when unavailable or already present).
     */
    public static String withUtmParams(String href, Weblog weblog, String slug) {
        int hash = href.indexOf('#');
        String base = hash >= 0 ? href.substring(0, hash) : href;
        String fragment = hash >= 0 ? href.substring(hash) : "";

        Set<String> existing = existingParamNames(base);
        String handle = weblog == null ? null : StringUtils.trimToNull(weblog.getHandle());
        String anchor = StringUtils.trimToNull(slug);

        StringBuilder url = new StringBuilder(base);
        appendParam(url, existing, "utm_source", handle);
        appendParam(url, existing, "utm_medium", "blog");
        appendParam(url, existing, "utm_campaign", anchor);
        return url.append(fragment).toString();
    }

    private static void appendParam(StringBuilder url, Set<String> existing,
            String name, String value) {
        if (value == null || existing.contains(name)) {
            return;
        }
        char last = url.charAt(url.length() - 1);
        if (last != '?' && last != '&') {
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
        }
        url.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** The (lower-cased) names of the query parameters already on {@code base}. */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases the query parameter names already on an author's own outbound "
                    + "link, so the ASCII utm_ parameters Pressroom appends are not added twice. It decides "
                    + "only which of Pressroom's own parameters to add, never access.")
    private static Set<String> existingParamNames(String base) {
        Set<String> names = new HashSet<>();
        int query = base.indexOf('?');
        if (query < 0) {
            return names;
        }
        for (String param : base.substring(query + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq >= 0 ? param.substring(0, eq) : param;
            if (!name.isBlank()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * The lower-cased host of {@code absoluteUrl} -- no userinfo, port or
     * path -- or null when it cannot be parsed. A host java.net.URI cannot
     * parse as a server name (an underscore, say) comes back from
     * {@code getHost()} as null; the host is then taken from the raw authority.
     */
    public static String destHost(String absoluteUrl) {
        if (absoluteUrl == null) {
            return null;
        }
        try {
            URI uri = new URI(absoluteUrl);
            String host = uri.getHost();
            if (host == null) {
                host = hostOfAuthority(uri.getRawAuthority());
            }
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** {@code authority} minus any userinfo and port, or null when nothing is left. */
    private static String hostOfAuthority(String authority) {
        if (authority == null) {
            return null;
        }
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(']') < colon) {
            host = host.substring(0, colon);
        }
        return host.isEmpty() ? null : host;
    }
}
