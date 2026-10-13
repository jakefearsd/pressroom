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
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
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
     * escapes), ready to fetch.
     */
    public record Ref(Kind kind, int start, int end, String url, String alt, String title) { }

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

    // An attribute value containing '>' ends the tag early; such a tag's
    // later attributes are not seen. Accepted: alt text is the only
    // attribute that plausibly carries one, and src/srcset precede it in
    // every generator this blog sees.
    // Names are told apart by which capture group matched, not by lower-casing
    // them: case mapping of text from the entry is what SpotBugs flags as
    // IMPROPER_UNICODE, and the pattern's own CASE_INSENSITIVE already does it.
    private static final Pattern TAG = Pattern.compile(
            "<(?:(img)|source)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    // The leading \s keeps data-src and similar from matching as src.
    // Groups: 1 src, 2 srcset, 3 alt; then the value in 4, 5 or 6 by quoting.
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\s(?:(src)|(srcset)|(alt))\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
            Pattern.CASE_INSENSITIVE);
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
                            altOf(image), image.getTitle()));
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
        StringBuilder out = new StringBuilder(text);
        List<Ref> backwards = new ArrayList<>(refs);
        backwards.sort(Comparator.comparingInt(Ref::start).reversed());
        for (Ref ref : backwards) {
            String local = localUrls.get(ref.url());
            if (local != null) {
                out.replace(ref.start(), ref.end(), ref.kind() == Kind.HTML_SRC
                        ? StringEscapeUtils.escapeHtml4(local)
                        : markdownImage(ref.alt(), local, ref.title()));
            }
        }
        return out.toString();
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
        Matcher tag = TAG.matcher(text).region(range[0], range[1]);
        while (tag.find()) {
            String src = null;
            int srcStart = -1;
            int srcEnd = -1;
            String alt = null;
            Matcher attribute = ATTRIBUTE.matcher(text).region(tag.start(), tag.end());
            while (attribute.find()) {
                int group = attribute.group(4) != null ? 4 : attribute.group(5) != null ? 5 : 6;
                String value = StringEscapeUtils.unescapeHtml4(attribute.group(group));
                if (attribute.group(1) != null) {
                    src = value;
                    srcStart = attribute.start(group);
                    srcEnd = attribute.end(group);
                } else if (attribute.group(2) != null) {
                    if (srcsetIsExternal(value, ownHosts)) {
                        refusals.add("srcset is not imported; use one src, or [image id=...] for a "
                                + "responsive image: " + abbreviate(tag.group()));
                    }
                } else {
                    alt = value;
                }
            }
            if (tag.group(1) != null && isExternal(src, ownHosts)) {
                refs.add(new Ref(Kind.HTML_SRC, srcStart, srcEnd, src, alt, null));
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
        });
        return alt.isEmpty() ? null : alt.toString();
    }

    private static String markdownImage(String alt, String url, String title) {
        StringBuilder md = new StringBuilder("![")
                .append(alt == null ? "" : alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"))
                .append("](").append(url);
        if (title != null && !title.isEmpty()) {
            md.append(" \"").append(title.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return md.append(')').toString();
    }

    /** {@code value} cut to a readable length for a message. */
    public static String abbreviate(String value) {
        return value.length() <= ABBREVIATE_AT ? value : value.substring(0, ABBREVIATE_AT) + "...";
    }
}
