package com.wikantik.pressroom.business;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Finds the images an entry's text loads from other sites, and rewrites the
 * text to load local copies instead. The text is parsed with the commonmark
 * parser and extensions {@link MarkdownRenderer} uses, with source spans on,
 * so an image is found exactly where the renderer would draw one: code
 * blocks and inline code are never touched, and Markdown reference images
 * resolve through their definitions. HTML {@code <img>} tags are found inside
 * the parser's HTML nodes; a {@code srcset} naming another site is refused
 * rather than half-imported.
 *
 * <p>Shortcodes are not images: {@code [video url=...]} and the like are
 * plain text to the parser, and expand only at render time.
 */
public final class ExternalImages {

    /** How a reference is written, which decides how it is rewritten. */
    public enum Kind { HTML_SRC, MARKDOWN }

    /**
     * One external image. {@code start}/{@code end} delimit what a rewrite
     * replaces: the raw {@code src} value of an HTML image, or the whole
     * Markdown image. {@code url} is decoded (HTML entities, Markdown
     * escapes), ready to fetch. {@code alt} is the plain text for a media
     * file's alt text; {@code altSource} is a Markdown image's alt exactly as
     * written (null for an HTML image), so a rewrite changes only the URL.
     * {@code standalone} is a Markdown image, without a title, that is the
     * whole of a top-level paragraph: one that can become an
     * {@code [image id=...]} figure without breaking the text around it.
     */
    public record Ref(Kind kind, int start, int end, String url, String alt, String altSource, String title,
                      boolean standalone) { }

    /**
     * Every external image in text order, and the tags refused outright (an
     * external {@code srcset}), each abbreviated, with no sentence around it.
     */
    public record Scan(List<Ref> refs, List<String> refusals) {
        /** Each URL once, in order of first appearance. */
        public List<String> distinctUrls() {
            return refs.stream().map(Ref::url).distinct().toList();
        }
    }

    private static final Parser PARSER = Parser.builder()
            .extensions(MarkdownRenderer.EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    // Names are told apart by a precompiled case-insensitive pattern, never by
    // lower-casing entry text: SpotBugs flags that as IMPROPER_UNICODE.
    private static final Pattern OPEN = Pattern.compile(
            "<(?:(img)|source)\\b", Pattern.CASE_INSENSITIVE);
    // One attribute, read where the previous one ended, the way a browser's
    // tokenizer reads it: separators are whitespace and/or '/', an attribute
    // may follow a quoted value with none, and a value is consumed as one
    // token, so text inside it is never read as an attribute.
    // Split in two, each read where the previous token ended: one combined
    // pattern with an optional value trips SpotBugs' ReDoS check. The name
    // group is 1; the value pattern's groups are 1 double-quoted, 2
    // single-quoted, 3 unquoted. Possessive runs cannot give text back.
    // A name is never empty: a leading '=' starts one (browsers take it as the
    // first character of the name), so the loop stops only at '>' or the end.
    private static final Pattern NAME = Pattern.compile("[\\s/]*+(=[^\\s/>=]*+|[^\\s/>=]++)");
    private static final Pattern VALUE = Pattern.compile(
            "\\s*+=\\s*+(?:\"([^\"]*+)\"|'([^']*+)'|([^\\s>]++))");
    private static final Pattern SRC = Pattern.compile("src", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRCSET = Pattern.compile("srcset", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile("alt", Pattern.CASE_INSENSITIVE);
    private static final int ABBREVIATE_AT = 120;

    private ExternalImages() {
    }

    /** The external images of {@code text}; {@code ownHosts} (lower case) are this site's. */
    public static Scan scan(String text, Set<String> ownHosts) {
        if (text == null || text.isEmpty()) {
            return new Scan(List.of(), List.of());
        }
        List<Ref> refs = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        PARSER.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                int[] range = range(image.getSourceSpans());
                if (range.length == 2 && isExternal(image.getDestination(), ownHosts)) {
                    refs.add(new Ref(Kind.MARKDOWN, range[0], range[1], image.getDestination(),
                            altOf(image), altSourceOf(text, image), image.getTitle(), isStandalone(image)));
                }
                // no visitChildren: an image's children are its alt text
            }

            @Override
            public void visit(HtmlInline html) {
                scanHtml(text, range(html.getSourceSpans()), ownHosts, refs, refusals);
            }

            @Override
            public void visit(HtmlBlock html) {
                scanHtml(text, range(html.getSourceSpans()), ownHosts, refs, refusals);
            }
        });
        refs.sort(Comparator.comparingInt(Ref::start));
        return new Scan(List.copyOf(refs), List.copyOf(refusals));
    }

    /**
     * {@code text} with each ref pointing at {@code localUrls.get(ref.url())}.
     * An HTML ref has only its {@code src} value replaced, so the rest of the
     * tag and the text around it are byte-for-byte unchanged; a Markdown ref
     * becomes an inline image, which also takes a reference image off its
     * definition without touching the definition (a link may still use it).
     * A ref with no local copy is left as it was.
     */
    public static String rewrite(String text, List<Ref> refs, Map<String, String> localUrls) {
        return rewrite(text, refs, localUrls, Map.of());
    }

    /**
     * As {@link #rewrite(String, List, Map)}, except that a
     * {@linkplain Ref#standalone() standalone} Markdown image whose URL has a
     * media file id in {@code mediaIds} becomes {@code [image id="..."]}: a
     * responsive figure rather than the full-size file. Its alt text goes
     * along when it has one that can be quoted; otherwise the file's own alt
     * text (set from the first use) is used. The figure is an HTML block,
     * which runs to the next blank line, so a line straight after the image
     * is moved past one.
     */
    public static String rewrite(String text, List<Ref> refs, Map<String, String> localUrls,
                                 Map<String, String> mediaIds) {
        // One forward pass: replacing in place would shift the tail once per ref.
        StringBuilder out = new StringBuilder(text.length() + 64);
        List<Ref> ordered = new ArrayList<>(refs);
        ordered.sort(Comparator.comparingInt(Ref::start));
        int pos = 0;
        for (Ref ref : ordered) {
            String local = localUrls.get(ref.url());
            String mediaId = ref.standalone() ? mediaIds.get(ref.url()) : null;
            if (mediaId != null) {
                out.append(text, pos, ref.start()).append(imageShortcode(mediaId, ref.alt()));
                pos = separateFollowingLine(text, ref.end(), out);
            } else if (local != null) {
                out.append(text, pos, ref.start()).append(ref.kind() == Kind.HTML_SRC
                        ? StringEscapeUtils.escapeHtml4(local)
                        : markdownImage(ref, local));
                pos = ref.end();
            }
        }
        return out.append(text, pos, text.length()).toString();
    }

    private static String imageShortcode(String mediaId, String alt) {
        StringBuilder code = new StringBuilder("[image id=\"").append(mediaId).append('"');
        if (alt != null && !alt.isBlank()) {
            if (alt.indexOf('"') < 0) {
                code.append(" alt=\"").append(alt).append('"');
            } else if (alt.indexOf('\'') < 0) {
                code.append(" alt='").append(alt).append('\'');
            }
        }
        return code.append(']').toString();
    }

    /**
     * Copies the line ending after {@code from} into {@code out} and, when a
     * non-blank line follows it, the same line ending again, so a blank line
     * separates the two. Returns where the copy stopped.
     */
    private static int separateFollowingLine(String text, int from, StringBuilder out) {
        int newline = text.indexOf('\n', from);
        if (newline < 0 || !text.substring(from, newline).isBlank()) {
            return from;
        }
        int next = newline + 1;
        int lineEnd = text.indexOf('\n', next);
        String following = text.substring(next, lineEnd < 0 ? text.length() : lineEnd);
        if (following.isBlank()) {
            return from;
        }
        String ending = newline > from && text.charAt(newline - 1) == '\r' ? "\r\n" : "\n";
        out.append(text, from, next).append(ending);
        return next;
    }

    /** A title-less image that is the only thing in a paragraph directly in the document. */
    private static boolean isStandalone(Image image) {
        Node parent = image.getParent();
        return parent instanceof Paragraph && parent.getParent() instanceof Document
                && image.getPrevious() == null && image.getNext() == null
                && (image.getTitle() == null || image.getTitle().isEmpty());
    }

    /**
     * An absolute http(s) URL on another host. One that starts like one but
     * does not parse counts as external too, so the fetch refuses it out
     * loud instead of it being silently left behind as a hotlink.
     */
    static boolean isExternal(String url, Set<String> ownHosts) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            String host = new URI(trimmed).getHost();
            return host == null || !ownHosts.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return true;
        }
    }

    /** {start, end} of a node in the input, or an empty array when the parser gave it no span. */
    private static int[] range(List<SourceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new int[0];
        }
        SourceSpan last = spans.get(spans.size() - 1);
        return new int[] {spans.get(0).getInputIndex(), last.getInputIndex() + last.getLength()};
    }

    private static void scanHtml(String text, int[] range, Set<String> ownHosts,
                                 List<Ref> refs, List<String> refusals) {
        if (range.length != 2) {
            return;
        }
        int limit = range[1];
        Matcher open = OPEN.matcher(text).region(range[0], limit);
        while (open.find()) {
            ImgAttributes img = new ImgAttributes(ownHosts);
            int tagEnd = closingOf(text, img.read(text, open.end(), limit), limit);
            if (img.srcsetExternal) {
                // The tag alone: the sentence around it is the API's keyed image.scanRefused text.
                refusals.add(abbreviate(text.substring(open.start(), tagEnd)));
            }
            if (open.group(1) != null && isExternal(img.src, ownHosts)) {
                refs.add(new Ref(Kind.HTML_SRC, img.srcStart, img.srcEnd, img.src, img.alt, null, null, false));
            }
            // Resume after this tag, so a tag-looking run inside an attribute value is not a tag.
            open.region(tagEnd, limit);
        }
    }

    /** Just past the {@code >} at or after {@code pos}, or {@code limit} when the tag is unclosed. */
    private static int closingOf(String text, int pos, int limit) {
        int tagEnd = pos;
        while (tagEnd < limit && text.charAt(tagEnd) != '>') {
            tagEnd++;
        }
        return tagEnd < limit ? tagEnd + 1 : tagEnd;
    }

    /** What one HTML image tag's attributes say about importing it. */
    private static final class ImgAttributes {
        private final Set<String> ownHosts;
        private String src;
        private int srcStart = -1;
        private int srcEnd = -1;
        private boolean srcSeen;
        private String alt;
        private boolean srcsetExternal;

        ImgAttributes(Set<String> ownHosts) {
            this.ownHosts = ownHosts;
        }

        /**
         * Reads the attributes from {@code from} up to the first thing that is
         * not one, and returns where they stop.
         */
        int read(String text, int from, int limit) {
            Matcher attrName = NAME.matcher(text);
            Matcher attrValue = VALUE.matcher(text);
            int pos = from;
            attrName.region(pos, limit);
            while (attrName.lookingAt()) {
                pos = attrName.end();
                attrValue.region(pos, limit);
                if (attrValue.lookingAt()) {
                    pos = attrValue.end();
                    int group = attrValue.start(1) >= 0 ? 1 : attrValue.start(2) >= 0 ? 2 : 3;
                    accept(attrName.group(1), attrValue.group(group), attrValue.start(group), attrValue.end(group));
                } else {
                    accept(attrName.group(1), null, -1, -1);
                }
                attrName.region(pos, limit);
            }
            return pos;
        }

        private void accept(String name, String rawValue, int valueStart, int valueEnd) {
            if (SRC.matcher(name).matches()) {
                // A browser uses the first src and ignores the rest.
                if (!srcSeen) {
                    srcSeen = true;
                    if (rawValue != null) {
                        src = StringEscapeUtils.unescapeHtml4(rawValue);
                        srcStart = valueStart;
                        srcEnd = valueEnd;
                    }
                }
            } else if (SRCSET.matcher(name).matches()) {
                srcsetExternal |= rawValue != null && srcsetIsExternal(
                        StringEscapeUtils.unescapeHtml4(rawValue), ownHosts);
            } else if (ALT.matcher(name).matches() && rawValue != null) {
                alt = StringEscapeUtils.unescapeHtml4(rawValue);
            }
        }
    }

    private static boolean srcsetIsExternal(String srcset, Set<String> ownHosts) {
        for (String candidate : srcset.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && isExternal(trimmed.split("\\s+")[0], ownHosts)) {
                return true;
            }
        }
        return false;
    }

    private static String altOf(Node image) {
        StringBuilder alt = new StringBuilder();
        image.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                alt.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                alt.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak lineBreak) {
                alt.append(' ');
            }

            @Override
            public void visit(HardLineBreak lineBreak) {
                alt.append(' ');
            }
        });
        return alt.isEmpty() ? null : alt.toString();
    }

    /**
     * The image's alt exactly as written: from its first child's span to its
     * last child's, so line breaks, container prefixes, escapes and entities
     * survive a rewrite. Null when the parser gave a child no span.
     */
    private static String altSourceOf(String text, Image image) {
        Node first = image.getFirstChild();
        if (first == null) {
            return "";
        }
        int[] from = range(first.getSourceSpans());
        int[] to = range(image.getLastChild().getSourceSpans());
        return from.length == 2 && to.length == 2 ? text.substring(from[0], to[1]) : null;
    }

    private static String markdownImage(Ref ref, String url) {
        String alt = ref.altSource() != null ? ref.altSource() : escapedAlt(ref.alt());
        String title = ref.title();
        StringBuilder md = new StringBuilder("![").append(alt).append("](").append(url);
        if (title != null && !title.isEmpty()) {
            md.append(" \"").append(title.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return md.append(')').toString();
    }

    private static String escapedAlt(String alt) {
        return alt == null ? "" : alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    /** {@code value} cut to a readable length for a message. */
    public static String abbreviate(String value) {
        return value.length() <= ABBREVIATE_AT ? value : value.substring(0, ABBREVIATE_AT) + "...";
    }
}
