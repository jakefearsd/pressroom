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

package org.apache.roller.weblogger.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * The boundary between what an author writes and what a visitor's browser
 * executes.
 *
 * <p>Entries are stored as Markdown and rendered to HTML, and raw HTML passes
 * through that rendering untouched -- both because authors paste embed
 * snippets and because the shortcodes ({@code [image]}, {@code [gallery]},
 * {@code [map]}, {@code [cta]}, {@code [faq]}) expand into HTML before Markdown
 * runs. Nothing upstream of here is a security control; this is.
 *
 * <p>Built on the OWASP Java HTML Sanitizer, which <em>parses</em> the document
 * and re-serializes only what the policy below permits. The implementation this
 * replaced pattern-matched HTML with regular expressions -- the class of
 * sanitizer that loses to markup a browser will "repair" into something
 * executable. Wave 3 hit exactly that: literal {@code &quot;} text survived two
 * escaping passes, and the browser's own attribute decoding then manufactured
 * real quotes inside a JSON payload.
 *
 * <p>The policy is deliberately permissive about <em>structure</em> and strict
 * about <em>behaviour</em>: everything the shortcodes emit survives intact,
 * relative links work (Markdown authors link to their own posts constantly, and
 * the previous sanitizer stripped those), and anything that can execute --
 * {@code <script>}, {@code <iframe>}, {@code <form>}, {@code on*} handlers,
 * {@code javascript:} and {@code data:} URLs -- is removed.
 *
 * <p><b>One thing this policy cannot express:</b> CSS custom properties.
 * {@code CssSchema.withProperties("--ar")} throws outright, so an inline
 * {@code style="--ar:1.34"} is dropped. The gallery grid therefore carries its
 * aspect ratio as an {@code ar-NNN} class resolved by rules in the theme's own
 * (unsanitized) style block -- see {@code GalleryMarkup} and the
 * {@code #showGalleryGridStyles} macro.
 */
public final class HTMLSanitizer {

    /**
     * Whether authored entry content is sanitized at all.
     *
     * <p>Mutable and public because tests flip it; {@code roller.properties}
     * ships it enabled. Turning it off trusts every blog author with raw script
     * on their own pages -- defensible for a single-owner install, dangerous the
     * moment a blog has members.
     */
    @SuppressFBWarnings(
            value = "MS_SHOULD_BE_FINAL",
            justification = "Deliberately mutable, not an oversight (see the javadoc above): at "
                    + "least seven test classes (HTMLSanitizerTest, WeblogEntryWrapperTest, "
                    + "WeblogWrapperTest, MediaFileWrapperTest, UserAccountTest, and others) save "
                    + "the previous value, assign a new one for the duration of one test, and "
                    + "restore it in a finally. Making this final would remove the only seam those "
                    + "tests have for exercising both the sanitized and unsanitized code paths.")
    @SuppressWarnings("PMD.MutableStaticState") // test seam, see the SpotBugs justification above
    public static Boolean xssEnabled =
            WebloggerConfig.getBooleanProperty("weblogAdminsUntrusted", Boolean.FALSE);

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // http/https/mailto only. Protocol checks do not apply to relative
            // or fragment URLs, so "/2026/08/post" and "#section" survive.
            .allowStandardUrlProtocols()

            // ---- structure an author (or Markdown) can produce
            .allowElements(
                    "p", "div", "span", "br", "hr",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li", "dl", "dt", "dd",
                    "blockquote", "pre", "code", "kbd", "samp", "var",
                    "b", "i", "em", "strong", "s", "strike", "del", "ins",
                    "sub", "sup", "small", "big", "mark", "abbr", "cite", "q",
                    "table", "thead", "tbody", "tfoot", "caption",
                    "colgroup", "col", "tr", "th", "td")

            // ---- media and links, including everything the shortcodes emit
            .allowElements("a", "img", "figure", "figcaption", "picture", "source")

            .allowAttributes("href", "title", "rel").onElements("a")
            // A new tab, and only that (CtaShortcode's booking card, outbound
            // references): _top or _parent would let a link replace the
            // page around a framed preview. The policy adds rel="noopener
            // noreferrer" to every link it keeps a target on.
            .allowAttributes("target").matching(true, "_blank").onElements("a")
            // the lightbox reads these off the gallery anchor
            .allowAttributes("data-pswp-width", "data-pswp-height", "data-caption",
                    "data-exif-camera", "data-exif-lens", "data-exif-exposure",
                    "data-exif-aperture", "data-exif-iso", "data-exif-focal",
                    "data-blurhash").onElements("a")
            // Umami's declarative click tracking (CtaShortcode). Exactly these three names:
            // a data-umami-* wildcard would let authors attach arbitrary event properties.
            .allowAttributes("data-umami-event", "data-umami-event-entry",
                    "data-umami-event-dest").onElements("a")

            .allowAttributes("src", "srcset", "sizes", "alt", "title",
                    "width", "height", "loading", "decoding", "data-blurhash")
            .onElements("img")

            .allowAttributes("srcset", "sizes", "type", "media").onElements("source")

            // the map shortcode's payload: pins as JSON in a data attribute;
            // the video shortcode's provider + id, which #showEmbedAssets
            // reads to build the frame on click; and the contact/subscribe
            // shortcodes' placeholder ids (plus the contact form's
            // server-built submit endpoint), which #showAudienceAssets reads
            // to build the real form client-side
            .allowAttributes("data-pins", "data-center", "data-zoom", "data-route",
                    "data-provider", "data-video-id", "data-weblog", "data-list-uuid",
                    "data-endpoint")
            .onElements("div")

            .allowAttributes("colspan", "rowspan", "align", "valign", "scope")
            .onElements("td", "th")

            .allowAttributes("class", "id", "lang", "dir", "title").globally()

            // style is validated declaration by declaration against OWASP's own
            // CSS schema, which is what keeps expression() and url(javascript:)
            // out. The blurhash placeholder's background-color survives it.
            .allowAttributes("style").globally()
            .allowStyling()

            .toFactory();

    private HTMLSanitizer() {
    }

    /**
     * A policy that permits no markup at all, used to reduce a value to its
     * text content.
     */
    private static final PolicyFactory TEXT_ONLY = new HtmlPolicyBuilder().toFactory();

    /** Sanitize unconditionally. */
    public static String sanitize(String html) {
        return html == null ? null : POLICY.sanitize(html);
    }

    /** Sanitize only when {@link #xssEnabled} is on. */
    public static String conditionallySanitize(String html) {
        return Boolean.TRUE.equals(xssEnabled) ? sanitize(html) : html;
    }

    /**
     * Strip markup from a value that is <em>text</em>, not a document -- a
     * screen name, an email address, an entry title, a photo caption.
     *
     * <p>These must not go through {@link #sanitize}. It is an HTML processor:
     * handed {@code jake@example.com} it returns {@code jake&#64;example.com},
     * which is correct HTML and completely wrong as a stored email address.
     * The regex implementation this replaced passed such values through
     * untouched, so the distinction never had to be made until now.
     *
     * <p>Tags are removed and the surviving text is returned as text --
     * entities the sanitizer introduced are decoded again, so
     * {@code Tom &amp; Jerry} comes back as {@code Tom & Jerry}. Escaping for
     * output is the caller's job and happens at the template or attribute
     * boundary, as it did before.
     */
    public static String sanitizeText(String text) {
        if (text == null || text.indexOf('<') < 0) {
            // Nothing that could open a tag, so there is nothing to strip --
            // and returning the value untouched is what makes this safe for
            // both families of field. Some are stored already-escaped (entry
            // titles: EntryBean.copyTo escapes on save and themes emit them
            // raw), and re-encoding or decoding those would corrupt them in
            // opposite directions. Others are stored raw (emails, screen
            // names, EXIF strings) and must survive byte for byte.
            return text;
        }
        // Markup is present: reduce to text content. The unescape undoes the
        // entities the sanitizer's serializer introduces, so what comes back
        // is text rather than escaped text.
        return StringEscapeUtils.unescapeHtml4(TEXT_ONLY.sanitize(text));
    }

    /** {@link #sanitizeText} when {@link #xssEnabled} is on. */
    public static String conditionallySanitizeText(String text) {
        return Boolean.TRUE.equals(xssEnabled) ? sanitizeText(text) : text;
    }

    // ---------------------------------------------------------------- escaping
    //
    // Plain-text escaping used by the shortcode emitters to place author text
    // into attributes and element bodies. These parse nothing and are not a
    // security boundary: the policy above is.

    public static String htmlEncodeApexesAndTags(String source) {
        return htmlEncodeTag(htmlEncodeApexes(source));
    }

    public static String htmlEncodeApexes(String source) {
        return replaceAllNoRegex(source, new String[]{"\"", "'"},
                new String[]{"&quot;", "&#39;"});
    }

    public static String htmlEncodeTag(String source) {
        return replaceAllNoRegex(source, new String[]{"<", ">"},
                new String[]{"&lt;", "&gt;"});
    }

    public static String replaceAllNoRegex(String source, String[] searches, String[] replaces) {
        if (source == null) {
            return null;
        }
        String result = source;
        for (int i = 0; i < searches.length; i++) {
            result = result.replace(searches[i], replaces[i]);
        }
        return result;
    }
}
