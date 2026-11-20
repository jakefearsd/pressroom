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
package com.wikantik.pressroom.ui.controllers.core;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Builds a sitemap or sitemap-index document. Every URL goes through
 * {@link #escapeXml}; a lastmod is only an ISO instant; the rest is constant
 * markup. That is why there is one suppression here rather than one at every
 * call site.
 */
@SuppressFBWarnings(
        value = "POTENTIAL_XML_INJECTION",
        justification = "Every URL written into the XML goes through escapeXml(); lastmod is a date "
                + "formatted by DateTimeFormatter.ISO_INSTANT and the markup is constant.")
final class SitemapXml {

    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String IMAGE_NS = "http://www.google.com/schemas/sitemap-image/1.1";
    private static final String PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private final StringBuilder xml = new StringBuilder(256);
    private final String root;

    private SitemapXml(String root, String extraNamespaces) {
        this.root = root;
        xml.append(PROLOG).append('<').append(root).append(" xmlns=\"").append(SITEMAP_NS)
                .append('"').append(extraNamespaces).append(">\n");
    }

    /** A {@code urlset}, declaring the image namespace when {@code withImages}. */
    static SitemapXml urlset(boolean withImages) {
        return new SitemapXml("urlset",
                withImages ? " xmlns:image=\"" + IMAGE_NS + "\"" : "");
    }

    /** A {@code sitemapindex}. */
    static SitemapXml index() {
        return new SitemapXml("sitemapindex", "");
    }

    /** Adds a {@code url} with an optional lastmod ({@code null} leaves it out). */
    SitemapXml url(String loc, Instant lastmod) {
        return url(loc, lastmod, null);
    }

    /** Adds a {@code url} with an optional lastmod and an optional featured image URL. */
    SitemapXml url(String loc, Instant lastmod, String imageUrl) {
        return entry("url", loc, lastmod, imageUrl);
    }

    /** Adds a {@code sitemap} to an index, with an optional lastmod. */
    SitemapXml sitemap(String loc, Instant lastmod) {
        return entry("sitemap", loc, lastmod, null);
    }

    private SitemapXml entry(String tag, String loc, Instant lastmod, String imageUrl) {
        xml.append("  <").append(tag).append(">\n    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>")
                    .append(DateTimeFormatter.ISO_INSTANT.format(
                            Instant.ofEpochSecond(lastmod.toEpochMilli() / 1000)))
                    .append("</lastmod>\n");
        }
        if (imageUrl != null) {
            xml.append("    <image:image>\n      <image:loc>").append(escapeXml(imageUrl))
                    .append("</image:loc>\n    </image:image>\n");
        }
        xml.append("  </").append(tag).append(">\n");
        return this;
    }

    /** The finished document. */
    String build() {
        return xml + "</" + root + ">\n";
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
