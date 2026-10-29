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
package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.BookingLink;
import com.wikantik.pressroom.util.HTMLSanitizer;

/**
 * The built-in {@code [cta href=".." label=".." note=".."]} shortcode: a
 * styled call-to-action card for rental/booking/print links, rendered as an
 * anchor ({@code <button>} is not sanitizer-allow-listed):
 *
 * <pre>{@code
 * <a class="cta-card" href="https://..." rel="nofollow sponsored noopener"
 *    target="_blank"><span class="cta-label">Book this cottage</span>
 *    <span class="cta-note">From EUR 120/night</span></a>
 * }</pre>
 *
 * <p>{@code href} MUST be an absolute http(s) URL: the entry-content
 * sanitizer validates anchor hrefs with commons-validator's
 * {@code UrlValidator} and silently deletes the whole anchor on failure, so
 * this handler runs the same validation itself and returns null -- the
 * expander's "leave the shortcode text exactly as written" signal -- to
 * keep the mistake visible to the author instead of shipping an unlinked
 * label. (That validator also rejects the TLD-less {@code localhost}.)
 *
 * <p>UTM tagging: {@code utm_source=<weblog handle>}, {@code utm_medium=blog}
 * and {@code utm_campaign=<entry anchor>} are appended to the URL's query
 * string, preserving existing query parameters and the fragment. A utm
 * parameter the author already put in the URL wins -- it is never
 * overwritten or duplicated.
 *
 * <p>Umami click tracking: the anchor carries {@code data-umami-event="cta-click"}
 * plus {@code data-umami-event-entry} (the entry anchor, when present) and
 * {@code data-umami-event-dest} (the destination host, when parseable).
 *
 * <p>Without an {@code href} the blog's booking link is used
 * ({@link BookingLink#resolve}); with none configured the text is left as
 * written. An explicit {@code href} always wins.
 *
 * <p>{@code label} is required, {@code note} is optional; both are
 * attribute values and therefore HTML-escaped on emission.
 */
public class CtaShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(CtaShortcode.class);

    @Override
    public String getName() {
        return "cta";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("cta", "shortcode.cta.label",
                "[cta href=\"https://example.com/book\" label=\"Book this stay\" "
                        + "note=\"Free cancellation for 48 hours\"]");
    }

    @Override
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "Every author value goes through escape() "
                    + "(HTMLSanitizer.htmlEncodeApexesAndTags), the href is taken only as an absolute "
                    + "http(s) URL, and the expanded entry then passes through HTMLSanitizer, the one "
                    + "content boundary (EntryRenderer.render), whenever weblogAdminsUntrusted is on (the "
                    + "shipped default; with it off, authors are trusted with raw HTML anyway).")
    public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
        String href = StringUtils.trimToNull(attributes.get("href"));
        if (href == null && content != null) {
            // no explicit href: the blog's own booking link, else its business's
            href = BookingLink.resolve(content.getWeblog());
        }
        String label = StringUtils.trimToNull(attributes.get("label"));
        if (href == null || label == null) {
            log.debug("[cta] shortcode without href or label; leaving it as written");
            return null;
        }
        if (!BookingLink.isHttpUrl(href)) {
            // the sanitizer would silently delete the whole anchor; failing
            // here keeps the author's [cta ...] text visible instead
            log.debug("[cta] shortcode href is not an absolute http(s) URL;"
                    + " leaving it as written: {}", href);
            return null;
        }

        StringBuilder html = new StringBuilder(160);
        html.append("<a class=\"cta-card\" href=\"").append(escape(BookingLink.withUtmParams(href,
                content == null ? null : content.getWeblog(), content == null ? null : content.getSlug())))
                .append("\" rel=\"nofollow sponsored noopener\" target=\"_blank\"")
                .append(" data-umami-event=\"cta-click\"");
        String slug = content == null ? null : StringUtils.trimToNull(content.getSlug());
        if (slug != null) {
            html.append(" data-umami-event-entry=\"").append(escape(slug)).append('"');
        }
        String dest = BookingLink.destHost(href);
        if (dest != null) {
            html.append(" data-umami-event-dest=\"").append(escape(dest)).append('"');
        }
        html.append('>');
        html.append("<span class=\"cta-label\">").append(escape(label)).append("</span>");
        String note = StringUtils.trimToNull(attributes.get("note"));
        if (note != null) {
            html.append("<span class=\"cta-note\">").append(escape(note)).append("</span>");
        }
        html.append("</a>");
        return html.toString();
    }

    private static String escape(String value) {
        return HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }
}
