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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Supplier;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * The facts every footer shows: version, release date, copyright line and
 * privacy-policy link. Strings are raw (unescaped); escaping is the
 * template's job.
 *
 * <p>This is a display path, so {@link #of} never throws: anything that
 * cannot be read degrades to an empty or null component and the footer
 * renders without that clause.
 *
 * @param version         the Roller version, or "" when unknown
 * @param shortRevision   first 7 characters of the revision, or ""
 * @param releaseDate     {@code yyyy-MM-dd} in {@link #RELEASE_ZONE}, or null
 * @param copyrightText   the blog's custom copyright notice, or null
 * @param copyrightHolder blog name (site name on admin screens), or ""
 * @param copyrightYear   current year in {@link #RELEASE_ZONE}
 * @param privacyUrl      absolute URL of the privacy page, or null
 */
public record Colophon(String version, String shortRevision, String releaseDate,
                       String copyrightText, String copyrightHolder, int copyrightYear,
                       String privacyUrl) {

    /** The one zone dates and years are shown in; not configuration. */
    public static final ZoneId RELEASE_ZONE = ZoneId.of("America/Los_Angeles");

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(RELEASE_ZONE);

    private static final int SHORT_REVISION_LENGTH = 7;

    /** The formatted build date, or null when buildTime is not epoch millis. */
    public static String releaseDate(String buildTime) {
        if (buildTime == null) {
            return null;
        }
        try {
            return ISO_DATE.format(Instant.ofEpochMilli(Long.parseLong(buildTime.trim())));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null; // UNKNOWN, an unfiltered ${timestamp}, out of range
        }
    }

    /**
     * weblog may be null (admin screens: holder = site.name). weblogger may be
     * null too (install and pre-bootstrap pages): the version facts then come
     * straight from the packaged {@code /roller-version.properties}, with no
     * runtime-config or database access, so there is no copyright holder and
     * no privacy link. Never throws.
     */
    public static Colophon of(Weblogger weblogger, Weblog weblog, Clock clock) {
        if (weblogger == null) {
            return packaged(clock);
        }
        String version = nullToEmpty(guard(weblogger::getVersion));
        String revision = nullToEmpty(guard(weblogger::getRevision));
        String date = releaseDate(guard(weblogger::getBuildTime));
        String shortRevision = shorten(revision);

        String custom = null;
        String holder = "";
        try {
            if (weblog != null && !weblog.isSiteSpace()) {
                holder = nullToEmpty(weblog.getName());
                String copyright = weblog.getCopyright();
                custom = copyright == null || copyright.isBlank() ? null : copyright;
            } else {
                holder = nullToEmpty(WebloggerRuntimeConfig.getProperty("site.name"));
            }
        } catch (RuntimeException e) { // display path: holder stays what we have
            // Deliberately no retry: the getter that threw would throw again.
            custom = null;
        }

        int year = clock.instant().atZone(RELEASE_ZONE).getYear();
        return new Colophon(version, shortRevision, date, custom, holder, year,
                privacyUrl(weblogger));
    }

    private static String shorten(String revision) {
        return revision.length() > SHORT_REVISION_LENGTH
                ? revision.substring(0, SHORT_REVISION_LENGTH) : revision;
    }

    /** The no-Weblogger colophon: version facts only, same keys WebloggerImpl reads. */
    private static Colophon packaged(Clock clock) {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = Colophon.class.getResourceAsStream("/roller-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (java.io.IOException | RuntimeException e) { // display path: omit the clauses
            props.clear();
        }
        int year = clock.instant().atZone(RELEASE_ZONE).getYear();
        return new Colophon(nullToEmpty(props.getProperty("ro.version")),
                shorten(nullToEmpty(props.getProperty("ro.revision"))),
                releaseDate(props.getProperty("ro.buildTime")), null, "", year, null);
    }

    /** True when the blog supplied its own copyright notice. */
    public boolean hasCustomCopyright() {
        return copyrightText != null;
    }

    /**
     * The privacy page's absolute URL, or null when none is configured or it
     * cannot be built. The setting is a {@link PageReference}: a bare slug is
     * a site page, {@code handle/slug} a blog's. Validated when the setting is
     * saved, so there is no per-request existence check here.
     */
    private static String privacyUrl(Weblogger weblogger) {
        try {
            return PageReference.parse(WebloggerRuntimeConfig.getProperty("site.privacy.page"))
                    .flatMap(ref -> {
                        try {
                            return ref.absoluteUrl(weblogger);
                        } catch (WebloggerException e) { // display path: no link
                            return Optional.empty();
                        }
                    })
                    .orElse(null);
        } catch (RuntimeException e) { // display path: no link rather than no footer
            return null;
        }
    }

    /** The supplier's value, or null when it throws: a display path degrades. */
    private static String guard(Supplier<String> source) {
        try {
            return source.get();
        } catch (RuntimeException e) { // display path: omit the clause
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
