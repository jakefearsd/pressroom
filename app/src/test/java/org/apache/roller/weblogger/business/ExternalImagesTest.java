package org.apache.roller.weblogger.business;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Criteria 15 and 16, and Review Focus 2-4. */
class ExternalImagesTest {

    private static final Set<String> OWN = Set.of("blog.example.com");

    private static List<String> urls(String text) {
        return ExternalImages.scan(text, OWN).refs().stream().map(ExternalImages.Ref::url).toList();
    }

    @Test
    void anHtmlImageIsFoundWhicheverQuotesItsSrcUses() {
        assertEquals(List.of("https://a.example/1.jpg", "https://a.example/2.jpg", "https://a.example/3.jpg"),
                urls("<p><img src=\"https://a.example/1.jpg\"> <img src='https://a.example/2.jpg'> "
                        + "<img alt=x src=https://a.example/3.jpg></p>"));
    }

    @Test
    void inlineAndReferenceMarkdownImagesAreFound() {
        assertEquals(List.of("https://a.example/inline.jpg", "https://a.example/ref.jpg"),
                urls("![one](https://a.example/inline.jpg)\n\n![two][r]\n\n[r]: https://a.example/ref.jpg\n"));
    }

    @Test
    void codeIsLeftAlone() {
        assertEquals(List.of(), urls("```\n![x](https://a.example/fenced.jpg)\n```\n\n"
                + "    <img src=\"https://a.example/indented.jpg\">\n\n"
                + "`<img src=\"https://a.example/inline-code.jpg\">`\n"));
    }

    @Test
    void relativeProtocolRelativeDataAndOwnHostUrlsAreLeftAlone() {
        assertEquals(List.of(), urls("<img src=\"/myblog/mediaresource/abc\"> <img src=\"//cdn.example/x.jpg\"> "
                + "<img src=\"data:image/png;base64,AAAA\"> <img src=\"https://BLOG.example.com/myblog/x.jpg\"> "
                + "![a](images/local.png)"));
    }

    /** Review Focus 4. */
    @Test
    void shortcodesCarryingUrlsAreNotImages() {
        assertEquals(List.of(), urls("[video url=\"https://youtu.be/dQw4w9WgXcQ\" caption=\"x\"]\n\n"
                + "[cta href=\"https://example.com/book.jpg\" label=\"Book\"]\n\n[image id=\"abc\"]"));
    }

    @Test
    void aSrcIsDecodedForTheFetchButItsRawRangeIsWhatIsReplaced() {
        String text = "<img src=\"https://a.example/x.jpg?a=1&amp;b=2\" alt=\"A &amp; B\">";
        ExternalImages.Ref ref = ExternalImages.scan(text, OWN).refs().get(0);

        assertEquals("https://a.example/x.jpg?a=1&b=2", ref.url());
        assertEquals("A & B", ref.alt());
        assertEquals("https://a.example/x.jpg?a=1&amp;b=2", text.substring(ref.start(), ref.end()));
    }

    @Test
    void anExternalSrcsetIsRefusedAndAnOwnOneIsNot() {
        ExternalImages.Scan scan = ExternalImages.scan(
                "<img src=\"https://a.example/x.jpg\" srcset=\"https://a.example/x2.jpg 2x\">"
                + "<picture><source srcset=\"https://b.example/y.webp\"><img src=\"/y.jpg\"></picture>"
                + "<img src=\"/z.jpg\" srcset=\"/z2.jpg 2x, https://blog.example.com/z3.jpg 3x\">", OWN);

        assertEquals(2, scan.refusals().size());
    }

    @Test
    void theRewriteChangesOnlyTheSrcValue() {
        String text = "Before <img class=\"wide\" src=\"https://a.example/x.jpg\" alt=\"X\"> after.";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(),
                Map.of("https://a.example/x.jpg", "https://blog.example.com/myblog/mediaresource/m1"));

        assertEquals("Before <img class=\"wide\" src=\"https://blog.example.com/myblog/mediaresource/m1\" "
                + "alt=\"X\"> after.", rewritten);
    }

    @Test
    void aMarkdownImageBecomesInlineKeepingItsAltAndTitle() {
        String text = "Look: ![The [old] pier](https://a.example/p.jpg \"At \\\"dusk\\\"\") and ![r][ref]\n\n"
                + "[ref]: https://a.example/r.jpg\n";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(), Map.of(
                "https://a.example/p.jpg", "https://blog.example.com/m/p",
                "https://a.example/r.jpg", "https://blog.example.com/m/r"));

        assertEquals("Look: ![The [old] pier](https://blog.example.com/m/p \"At \\\"dusk\\\"\") and "
                + "![r](https://blog.example.com/m/r)\n\n[ref]: https://a.example/r.jpg\n", rewritten);
    }

    /** Review Focus 2. */
    @Test
    void crlfTextIsRewrittenAtTheRightOffsets() {
        String text = "Intro\r\n\r\n![a](https://a.example/a.jpg)\r\nmore <img src=\"https://a.example/b.png\"> end\r\n";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(), Map.of(
                "https://a.example/a.jpg", "L1", "https://a.example/b.png", "L2"));

        assertEquals("Intro\r\n\r\n![a](L1)\r\nmore <img src=\"L2\"> end\r\n", rewritten);
    }

    @Test
    void anHtmlBlockSpanningLinesIsScanned() {
        assertEquals(List.of("https://a.example/c.gif"),
                urls("<div>\r\n<img\r\n src='https://a.example/c.gif'>\r\n</div>\r\n"));
    }

    @Test
    void distinctUrlsKeepTheOrderOfFirstAppearance() {
        ExternalImages.Scan scan = ExternalImages.scan("![a](https://a.example/2.jpg) "
                + "<img src=\"https://a.example/1.jpg\"> ![b](https://a.example/2.jpg)", OWN);

        assertEquals(List.of("https://a.example/2.jpg", "https://a.example/1.jpg"), scan.distinctUrls());
        assertEquals(3, scan.refs().size());
    }

    @Test
    void aMalformedHttpUrlCountsAsExternalSoTheFetchRefusesItOutLoud() {
        assertEquals(List.of("https://a.example/bad path.jpg"),
                urls("<img src=\"https://a.example/bad path.jpg\">"));
    }

    @Test
    void aRefWithNoLocalCopyIsLeftAsItWas() {
        String text = "<img src=\"https://a.example/x.jpg\">";
        assertEquals(text, ExternalImages.rewrite(text, ExternalImages.scan(text, OWN).refs(), Map.of()));
    }

    @Test
    void aSrcInsideAnotherAttributesValueIsNotTheSrc() {
        String text = "<img src=\"https://ext.example/real.jpg\" title=\"see src=https://ext.example/other.jpg\">";

        assertEquals(List.of("https://ext.example/real.jpg"), urls(text));
        assertEquals("<img src=\"https://blog.example.com/m/real\" title=\"see src=https://ext.example/other.jpg\">",
                ExternalImages.rewrite(text, ExternalImages.scan(text, OWN).refs(),
                        Map.of("https://ext.example/real.jpg", "https://blog.example.com/m/real")));
    }

    @Test
    void theFirstOfTwoSrcAttributesIsTheOneLoaded() {
        assertEquals(List.of("https://ext.example/a.jpg"),
                urls("<img src=\"https://ext.example/a.jpg\" src=\"/local.jpg\">"));
    }

    @Test
    void anAttributeRightAfterAQuotedValueOrASlashIsSeen() {
        assertEquals(List.of("https://ext.example/a.jpg", "https://ext.example/b.jpg"),
                urls("<p><img alt=\"x\"src=\"https://ext.example/a.jpg\"> "
                        + "<img/src=\"https://ext.example/b.jpg\"></p>"));
    }

    @Test
    void tagAndAttributeNamesAreCaseInsensitive() {
        assertEquals(List.of("https://ext.example/c.jpg"), urls("<IMG SRC=\"https://ext.example/c.jpg\">"));
        assertEquals(1, ExternalImages.scan("<picture><Source SrcSet=\"https://ext.example/d.webp\"></picture>",
                OWN).refusals().size());
    }

    @Test
    void dataSrcIsNotSrc() {
        assertEquals(List.of(), urls("<img data-src=\"https://ext.example/e.jpg\" src=\"/local.jpg\">"));
    }

    /**
     * Inline, commonmark reads {@code <img =x ...>} as text (its grammar has
     * no such attribute name), so the renderer escapes it and no browser loads
     * it. Inside a raw HTML block the text passes through verbatim, so that is
     * where a leading {@code =} must be read as an attribute name.
     */
    @Test
    void aLeadingEqualsSignStartsAnAttributeName() {
        assertEquals(List.of("https://ext.example/a.jpg"),
                urls("<div>\n<img =x src=\"https://ext.example/a.jpg\">\n</div>\n"));
        assertEquals(List.of("https://ext.example/c.jpg"),
                urls("<div>\n<img =x title=\"a>b\" src=\"https://ext.example/c.jpg\">\n</div>\n"));
    }

    @Test
    void emptyTextHasNoImages() {
        assertTrue(ExternalImages.scan("", OWN).refs().isEmpty());
        assertTrue(ExternalImages.scan(null, OWN).refs().isEmpty());
    }

    /** Final review M5: only the URL changes; the alt's source text survives byte for byte. */
    @Test
    void aMultiLineAltKeepsItsLineBreakAndTheFilesAltReadsAsOneLine() {
        String text = "![Torrey\nPines](https://ext.example/x.jpg)";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        assertEquals("Torrey Pines", scan.refs().get(0).alt());
        assertEquals("![Torrey\nPines](https://blog.example.com/m/1)", ExternalImages.rewrite(text, scan.refs(),
                Map.of("https://ext.example/x.jpg", "https://blog.example.com/m/1")));
    }

    @Test
    void escapesAndEntitiesInAnAltAreKeptVerbatim() {
        String text = "![a\\*b\\* &lt;x&gt;](https://ext.example/y.jpg)";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        assertEquals("![a\\*b\\* &lt;x&gt;](https://blog.example.com/m/2)", ExternalImages.rewrite(text, scan.refs(),
                Map.of("https://ext.example/y.jpg", "https://blog.example.com/m/2")));
    }

    @Test
    void manyReferencesAreRewrittenInOnePassKeepingTheTextBetween() {
        String text = "a ![x](https://ext.example/1.jpg) b <img src=\"https://ext.example/1.jpg\"> c ![](https://ext.example/1.jpg) d";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        assertEquals("a ![x](L) b <img src=\"L\"> c ![](L) d",
                ExternalImages.rewrite(text, scan.refs(), Map.of("https://ext.example/1.jpg", "L")));
    }
}
