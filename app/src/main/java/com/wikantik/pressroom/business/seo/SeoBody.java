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
package com.wikantik.pressroom.business.seo;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.business.seo.EntrySeoChecks.Finding;
import com.wikantik.pressroom.business.shortcodes.GalleryMarkup;
import com.wikantik.pressroom.util.URLUtilities;
import org.owasp.html.HtmlSanitizer;

/**
 * The body half of {@link EntrySeoChecks}: what an entry's rendered HTML
 * ({@code EntryRenderer.transformedText}) gets wrong for a search engine or a
 * reader -- an h1 beside the theme's, a skipped heading level, an image with
 * no description, a link to a part of the page or an entry that is not there.
 *
 * <p>The markup is read through the OWASP lexer, the way {@code Excerpts}
 * reads it, never pattern-matched: entities arrive decoded and attribute
 * order and quoting do not matter. Reading it takes two steps because two of
 * the checks need facts only the caller can look up: {@link #of} scans,
 * {@link #entryAnchors} and {@link #imageSources} say what to look up, and
 * {@link #findings} judges with the answers in a {@link Site}.
 */
public final class SeoBody {

    public static final String BODY_H1 = "body_h1";
    public static final String HEADING_SKIP = "heading_skip";
    public static final String IMAGE_ALT_MISSING = "image_alt_missing";
    public static final String FRAGMENT_LINK_BROKEN = "fragment_link_broken";
    public static final String ENTRY_LINK_BROKEN = "entry_link_broken";

    /**
     * What the body checks need to know about the weblog.
     *
     * @param entryUrlPrefixes the weblog's entry permalink urls with the anchor left off, relative and
     *                         absolute, as its url strategy builds them ({@code /blog/entry/})
     * @param publishedAnchors which of {@link #entryAnchors} are published entries (or the entry itself)
     * @param fileNames        for an image source that is one of the weblog's media files, its file name
     */
    public record Site(List<String> entryUrlPrefixes, Set<String> publishedAnchors, Map<String, String> fileNames) {
    }

    /** A heading, at its level, with its text. */
    private record Heading(int level, String text) {
        String label() {
            return "h" + level + " \"" + text + "\"";
        }
    }

    /** An image; {@code alt} is null when the attribute is absent, not when it is empty. */
    private record Image(String src, String alt) {
    }

    private final List<Heading> headings = new ArrayList<>();
    private final List<Image> images = new ArrayList<>();
    private final List<String> hrefs = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    private SeoBody() {
    }

    /** Scans rendered entry HTML; null or blank is a body with nothing in it. */
    public static SeoBody of(String html) {
        SeoBody body = new SeoBody();
        if (StringUtils.isNotBlank(html)) {
            HtmlSanitizer.sanitize(html, body.new Collector());
        }
        return body;
    }

    /** The anchors of every link to one of this weblog's entries, decoded, for the caller to look up. */
    public Set<String> entryAnchors(List<String> entryUrlPrefixes) {
        Set<String> anchors = new LinkedHashSet<>();
        for (String href : hrefs) {
            String anchor = entryAnchor(href, entryUrlPrefixes);
            if (anchor != null) {
                anchors.add(anchor);
            }
        }
        return anchors;
    }

    /** Every image's {@code src}, in document order, for the caller to name the media files among them. */
    public List<String> imageSources() {
        return images.stream().map(Image::src).filter(StringUtils::isNotEmpty).toList();
    }

    /** Every body finding, at most one per code, in a stable order. */
    public List<Finding> findings(Site site) {
        List<Finding> findings = new ArrayList<>();
        bodyH1(findings);
        headingSkips(findings);
        imagesWithoutAlt(site, findings);
        brokenFragments(findings);
        brokenEntryLinks(site, findings);
        return findings;
    }

    private void bodyH1(List<Finding> findings) {
        List<String> h1s = headings.stream().filter(h -> h.level() == 1).map(h -> "\"" + h.text() + "\"").toList();
        if (h1s.isEmpty()) {
            return;
        }
        findings.add(Finding.of(BODY_H1, EntrySeoChecks.WARNING, (h1s.size() == 1 ? "The body has an h1 "
                + "heading, " : "The body has " + h1s.size() + " h1 headings, ") + EntrySeoChecks.named(h1s)
                + "; the theme already shows the entry title as the page's h1. Start the body's headings at "
                + "h2 (## in Markdown)."));
    }

    /** The theme's title is the h1 every body heading follows, so a body opening at h3 skips h2. */
    private void headingSkips(List<Finding> findings) {
        List<String> skips = new ArrayList<>();
        String previous = "the entry title (h1)";
        int level = 1;
        for (Heading heading : headings) {
            if (heading.level() > level + 1) {
                skips.add(previous + " is followed by " + heading.label());
            }
            previous = heading.label();
            level = heading.level();
        }
        if (skips.isEmpty()) {
            return;
        }
        findings.add(Finding.of(HEADING_SKIP, EntrySeoChecks.INFO, (skips.size() == 1 ? "A heading skips a "
                + "level: " : skips.size() + " headings skip a level: ") + EntrySeoChecks.named(skips)
                + ". Go down one level at a time, so the outline has no gaps."));
    }

    /**
     * No {@code alt} at all, or one that is only the file name -- what the
     * {@code [image]} shortcode falls back to when the media file has no alt
     * text and the placement no caption. {@code alt=""} is the standard
     * mark of a decorative image, and the shortcode honours it, so it is not
     * reported.
     */
    private void imagesWithoutAlt(Site site, List<Finding> findings) {
        List<String> undescribed = new ArrayList<>();
        for (Image image : images) {
            if (image.alt() == null || isFileName(image.alt().strip(), image.src(), site)) {
                undescribed.add(StringUtils.defaultIfEmpty(image.src(), "(no src)"));
            }
        }
        if (undescribed.isEmpty()) {
            return;
        }
        findings.add(Finding.of(IMAGE_ALT_MISSING, EntrySeoChecks.WARNING, (undescribed.size() == 1
                ? "1 image has" : undescribed.size() + " images have") + " no alt text, or only a file name: "
                + EntrySeoChecks.named(undescribed) + ". Describe each one: in Markdown ![what it shows](url); "
                + "for a media file, its alt text or the [image] caption. Write alt=\"\" for a purely decorative "
                + "image."));
    }

    private static boolean isFileName(String alt, String src, Site site) {
        if (alt.isEmpty()) {
            return false;
        }
        String mediaName = src == null ? null : site.fileNames().get(src);
        return matchesFileName(alt, mediaName) || matchesFileName(alt, lastSegment(src));
    }

    /** The name as typed, without its extension, or read as words the way the shortcodes write it. */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Asks whether an alt text is only a file name, in any case, to advise the author; the "
                    + "answer is a warning and decides nothing about access.")
    private static boolean matchesFileName(String alt, String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        String trimmed = name.strip();
        int dot = trimmed.lastIndexOf('.');
        String stem = dot > 0 ? trimmed.substring(0, dot) : trimmed;
        return alt.equalsIgnoreCase(trimmed) || alt.equalsIgnoreCase(stem)
                || alt.equalsIgnoreCase(GalleryMarkup.fileNameAsAlt(trimmed));
    }

    /** The file name a url ends in, when its last path segment looks like one (has an extension). */
    private static String lastSegment(String src) {
        if (src == null) {
            return null;
        }
        String path = beforeQueryOrFragment(src);
        String segment = decode(path.substring(path.lastIndexOf('/') + 1));
        return segment.lastIndexOf('.') > 0 ? segment : null;
    }

    /**
     * A link to {@code #x} with no {@code id="x"} anywhere in the body. The
     * empty fragment and {@code #top} are the top of the page in every
     * browser (HTML's own rule), so they always work.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "HTML matches the fragment \"top\" ASCII case-insensitively; the answer is a warning "
                    + "and decides nothing about access.")
    private void brokenFragments(List<Finding> findings) {
        List<String> broken = new ArrayList<>();
        for (String href : hrefs) {
            if (!href.startsWith("#")) {
                continue;
            }
            String fragment = href.substring(1);
            if (fragment.isEmpty() || "top".equals(fragment.toLowerCase(Locale.ROOT))
                    || ids.contains(fragment) || ids.contains(decode(fragment))) {
                continue;
            }
            broken.add(href);
        }
        if (broken.isEmpty()) {
            return;
        }
        findings.add(Finding.of(FRAGMENT_LINK_BROKEN, EntrySeoChecks.WARNING, (broken.size() == 1
                ? "1 link points" : broken.size() + " links point") + " at a part of this entry that does not "
                + "exist: " + EntrySeoChecks.named(broken) + ". No element in the body has that id; a Markdown "
                + "heading's id is its text in lower case with hyphens for spaces (## Ferry times is "
                + "#ferry-times)."));
    }

    private void brokenEntryLinks(Site site, List<Finding> findings) {
        List<String> broken = new ArrayList<>();
        for (String href : hrefs) {
            String anchor = entryAnchor(href, site.entryUrlPrefixes());
            if (anchor != null && !site.publishedAnchors().contains(anchor)) {
                broken.add(href);
            }
        }
        if (broken.isEmpty()) {
            return;
        }
        findings.add(Finding.of(ENTRY_LINK_BROKEN, EntrySeoChecks.WARNING, (broken.size() == 1
                ? "1 link points" : broken.size() + " links point") + " at an entry of this weblog that is not "
                + "published: " + EntrySeoChecks.named(broken) + ". Readers get a page that is not found; "
                + "correct the anchor, or publish that entry."));
    }

    /**
     * The anchor of an entry permalink of this weblog, decoded the way the
     * url strategy encoded it; null for any other href. A query or fragment
     * after the anchor is not part of it, and neither is a trailing slash.
     */
    private static String entryAnchor(String href, List<String> prefixes) {
        String prefix = prefixes.stream().filter(p -> StringUtils.isNotEmpty(p) && href.startsWith(p))
                .findFirst().orElse(null);
        if (prefix == null) {
            return null;
        }
        String rest = beforeQueryOrFragment(href.substring(prefix.length()));
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return rest.isEmpty() || rest.contains("/") ? null : decode(rest);
    }

    private static String beforeQueryOrFragment(String url) {
        int end = StringUtils.indexOfAny(url, '?', '#');
        return end < 0 ? url : url.substring(0, end);
    }

    /** Percent-decoded as {@code URLUtilities.encode} encodes; a malformed escape is left as written. */
    private static String decode(String value) {
        try {
            return URLUtilities.decode(value);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    /** Gathers headings, images, links and ids from the lexer's events. */
    private final class Collector implements HtmlSanitizer.Policy {
        /** The text chunks of the heading being read, or null outside one. */
        private List<String> headingText;
        private int headingLevel;

        @Override
        public void openDocument() {
            // nothing to set up
        }

        @Override
        public void closeDocument() {
            endHeading();
        }

        @Override
        public void openTag(String elementName, List<String> attrs) {
            String id = attribute(attrs, "id");
            if (id != null) {
                ids.add(id);
            }
            int level = headingLevel(elementName);
            if (level > 0) {
                endHeading();
                headingLevel = level;
                headingText = new ArrayList<>();
            } else if ("img".equals(elementName)) {
                images.add(new Image(attribute(attrs, "src"), attribute(attrs, "alt")));
            } else if ("a".equals(elementName)) {
                String href = attribute(attrs, "href");
                if (href != null) {
                    hrefs.add(href.strip());
                }
            }
        }

        @Override
        public void closeTag(String elementName) {
            if (headingText != null && headingLevel(elementName) == headingLevel) {
                endHeading();
            }
        }

        @Override
        public void text(String textChunk) {
            if (headingText != null) {
                headingText.add(textChunk);
            }
        }

        private void endHeading() {
            if (headingText != null) {
                headings.add(new Heading(headingLevel, StringUtils.normalizeSpace(String.join("", headingText))));
                headingText = null;
            }
        }

        /** 1 to 6 for h1 to h6, else 0. The lexer hands element names over in lower case. */
        private static int headingLevel(String elementName) {
            if (elementName.length() == 2 && elementName.charAt(0) == 'h') {
                int level = elementName.charAt(1) - '0';
                return level >= 1 && level <= 6 ? level : 0;
            }
            return 0;
        }

        /** The lexer hands attributes over as a flat name, value, name, value list. */
        private static String attribute(List<String> attrs, String name) {
            for (int i = 0; i + 1 < attrs.size(); i += 2) {
                if (name.equals(attrs.get(i))) {
                    return attrs.get(i + 1);
                }
            }
            return null;
        }
    }
}
