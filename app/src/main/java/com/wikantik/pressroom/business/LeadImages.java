package com.wikantik.pressroom.business;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * The media-library pictures an entry's text shows, in the order a reader
 * meets them (spec 2026-11-07, pictures by default). A picture is an
 * {@code [image id=...]} shortcode, or an HTML or Markdown image whose URL is
 * a media file's ({@code .../mediaresource/<id>}). Code is not a picture, nor
 * is a link to a media file, nor an image on another site.
 *
 * <p>Ids only: whether an id names a public image of the right weblog is the
 * caller's check ({@code WeblogOwnership.publicImage}). Two uses: the first
 * usable id is a post's featured image when none is set (Blogger's rule), and
 * a theme leaves out its hero when the text already shows the featured image.
 */
public final class LeadImages {

    private static final Parser PARSER = Parser.builder()
            .extensions(MarkdownRenderer.EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    // Each read in two steps, the tag then its attribute, so no pattern has to
    // backtrack through a possessive run (SpotBugs' ReDoS check). The
    // look-behind keeps data-id and data-src from counting as id and src.
    private static final Pattern SHORTCODE = Pattern.compile("\\[image\\b([^\\]]*+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile(
            "(?<![\\w-])id\\s*+=\\s*+(?:\"([^\"]*+)\"|'([^']*+)'|([^\\s\\]]++))", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("<img\\b([^>]*+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile(
            "(?<![\\w-])src\\s*+=\\s*+(?:\"([^\"]*+)\"|'([^']*+)'|([^\\s>]++))", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_URL = Pattern.compile("/mediaresource/([^/?#\\s\"'<>)]++)");

    private record Found(int at, String id) { }

    private LeadImages() {
    }

    /** Each media id the text shows as a picture, once, in text order. */
    public static List<String> mediaIds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        char[] visible = text.toCharArray();
        List<Found> found = new ArrayList<>();
        PARSER.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String id = mediaId(image.getDestination());
                int[] range = range(image);
                if (id != null && range.length == 2) {
                    found.add(new Found(range[0], id));
                }
                // no visitChildren: an image's children are its alt text
            }

            @Override
            public void visit(Code code) {
                blank(visible, range(code));
            }

            @Override
            public void visit(FencedCodeBlock code) {
                blank(visible, range(code));
            }

            @Override
            public void visit(IndentedCodeBlock code) {
                blank(visible, range(code));
            }
        });
        String outsideCode = new String(visible);
        Matcher shortcode = SHORTCODE.matcher(outsideCode);
        while (shortcode.find()) {
            String id = attribute(ID, shortcode.group(1));
            if (id != null && !id.isBlank()) {
                found.add(new Found(shortcode.start(), id.trim()));
            }
        }
        Matcher img = IMG.matcher(outsideCode);
        while (img.find()) {
            String id = mediaId(attribute(SRC, img.group(1)));
            if (id != null) {
                found.add(new Found(img.start(), id));
            }
        }
        found.sort(Comparator.comparingInt(Found::at));
        Set<String> ids = new LinkedHashSet<>();
        found.forEach(f -> ids.add(f.id()));
        return List.copyOf(ids);
    }

    private static String attribute(Pattern pattern, String attributes) {
        Matcher m = pattern.matcher(attributes);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return StringEscapeUtils.unescapeHtml4(value);
    }

    private static String mediaId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = MEDIA_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** {start, end} of a node in the input, or an empty array when the parser gave it no span. */
    private static int[] range(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return new int[0];
        }
        SourceSpan last = spans.get(spans.size() - 1);
        return new int[] {spans.get(0).getInputIndex(), last.getInputIndex() + last.getLength()};
    }

    private static void blank(char[] visible, int[] range) {
        if (range.length == 2) {
            for (int i = range[0]; i < range[1] && i < visible.length; i++) {
                visible[i] = ' ';
            }
        }
    }
}
