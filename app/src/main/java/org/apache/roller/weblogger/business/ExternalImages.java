package org.apache.roller.weblogger.business;

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
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
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
     */
    public record Ref(Kind kind, int start, int end, String url, String alt, String altSource, String title) { }

    /** Every external image in text order, and the tags refused outright. */
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
                            altOf(image), altSourceOf(text, image), image.getTitle()));
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
        // One forward pass: replacing in place would shift the tail once per ref.
        StringBuilder out = new StringBuilder(text.length() + 64);
        List<Ref> ordered = new ArrayList<>(refs);
        ordered.sort(Comparator.comparingInt(Ref::start));
        int pos = 0;
        for (Ref ref : ordered) {
            String local = localUrls.get(ref.url());
            if (local != null) {
                out.append(text, pos, ref.start()).append(ref.kind() == Kind.HTML_SRC
                        ? StringEscapeUtils.escapeHtml4(local)
                        : markdownImage(ref, local));
                pos = ref.end();
            }
        }
        return out.append(text, pos, text.length()).toString();
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
        Matcher open = OPEN.matcher(text).region(range[0], range[1]);
        while (open.find()) {
            String src = null;
            int srcStart = -1;
            int srcEnd = -1;
            boolean srcSeen = false;
            String alt = null;
            boolean srcsetExternal = false;
            Matcher attrName = NAME.matcher(text);
            Matcher attrValue = VALUE.matcher(text);
            int pos = open.end();
            while (true) {
                attrName.region(pos, range[1]);
                if (!attrName.lookingAt()) {
                    break;
                }
                pos = attrName.end();
                String name = attrName.group(1);
                String rawValue = null;
                int valueGroup = 0;
                attrValue.region(pos, range[1]);
                if (attrValue.lookingAt()) {
                    pos = attrValue.end();
                    valueGroup = attrValue.start(1) >= 0 ? 1 : attrValue.start(2) >= 0 ? 2 : 3;
                    rawValue = attrValue.group(valueGroup);
                }
                if (SRC.matcher(name).matches()) {
                    // A browser uses the first src and ignores the rest.
                    if (!srcSeen) {
                        srcSeen = true;
                        if (rawValue != null) {
                            src = StringEscapeUtils.unescapeHtml4(rawValue);
                            srcStart = attrValue.start(valueGroup);
                            srcEnd = attrValue.end(valueGroup);
                        }
                    }
                } else if (SRCSET.matcher(name).matches()) {
                    srcsetExternal |= rawValue != null && srcsetIsExternal(
                            StringEscapeUtils.unescapeHtml4(rawValue), ownHosts);
                } else if (ALT.matcher(name).matches() && rawValue != null) {
                    alt = StringEscapeUtils.unescapeHtml4(rawValue);
                }
            }
            int tagEnd = pos;
            while (tagEnd < range[1] && text.charAt(tagEnd) != '>') {
                tagEnd++;
            }
            if (tagEnd < range[1]) {
                tagEnd++;
            }
            if (srcsetExternal) {
                refusals.add("srcset is not imported; use one src, or [image id=...] for a "
                        + "responsive image: " + abbreviate(text.substring(open.start(), tagEnd)));
            }
            if (open.group(1) != null && isExternal(src, ownHosts)) {
                refs.add(new Ref(Kind.HTML_SRC, srcStart, srcEnd, src, alt, null, null));
            }
            // Resume after this tag, so a tag-looking run inside an attribute value is not a tag.
            open.region(tagEnd, range[1]);
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
