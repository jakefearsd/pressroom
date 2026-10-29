package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * How a weblog-relative path is split into locale, context and data.
 *
 * <p>This decision runs before anything else knows what the url means: whether
 * the first segment is read as a locale determines what every segment after it
 * is taken to be. Get it wrong and the url is silently reinterpreted rather
 * than rejected, which is the failure mode worth guarding.
 */
class WeblogPathInfoParsingTest {

    // Pure path parsing: the mapper never reaches its provider or facade here.
    private final WeblogRequestMapper mapper =
            new WeblogRequestMapper(mock(WebloggerProvider.class), mock(Weblogger.class));

    private WeblogRequestMapper.WeblogPathInfo parse(String path) {
        return mapper.parsePathInfo(path);
    }

    @Test
    void aLocaleContextAndDataAreAllRecognised() {
        var parsed = parse("en_US/entry/my-post");

        assertEquals("en_US", parsed.locale());
        assertEquals("entry", parsed.context());
        assertEquals("my-post", parsed.data());
    }

    @Test
    void aTwoLetterLocaleWorksTheSameAsAFiveCharacterOne() {
        var parsed = parse("de/entry/my-post");

        assertEquals("de", parsed.locale());
        assertEquals("entry", parsed.context());
        assertEquals("my-post", parsed.data());
    }

    @Test
    void aLocaleAndContextWithNoDataLeavesDataNull() {
        var parsed = parse("de/tags");

        assertEquals("de", parsed.locale());
        assertEquals("tags", parsed.context());
        assertNull(parsed.data());
    }

    @Test
    void aBareLocaleIsJustALocale() {
        var parsed = parse("de");

        assertEquals("de", parsed.locale());
        assertNull(parsed.context(), "there is no context to find");
        assertNull(parsed.data());
    }

    @Test
    void withoutALocaleTheFirstSegmentIsTheContext() {
        var parsed = parse("entry/my-post");

        assertNull(parsed.locale());
        assertEquals("entry", parsed.context());
        assertEquals("my-post", parsed.data());
    }

    @Test
    void aBareContextHasNoData() {
        var parsed = parse("search");

        assertNull(parsed.locale());
        assertEquals("search", parsed.context());
        assertNull(parsed.data());
    }

    /**
     * The split is capped at three parts on the assumption that the first is a
     * locale. When it is not, the path has been split one time too many, and
     * the last two parts must be glued back together with the separator they
     * were divided on -- otherwise a dated permalink loses a slash and stops
     * resolving.
     */
    @Test
    void dataKeepsItsOwnSeparatorsWhenThereIsNoLocale() {
        var parsed = parse("entry/2005/11/my-post");

        assertNull(parsed.locale());
        assertEquals("entry", parsed.context());
        assertEquals("2005/11/my-post", parsed.data(),
                "every separator after the context belongs to the data, not to the split");
    }

    @Test
    void dataKeepsItsOwnSeparatorsAfterALocaleToo() {
        var parsed = parse("en_US/entry/2005/11/my-post");

        assertEquals("en_US", parsed.locale());
        assertEquals("entry", parsed.context());
        assertEquals("2005/11/my-post", parsed.data());
    }

    // --- what is and is not taken for a locale ----------------------------

    @Test
    void aSegmentThatMerelyLooksLikeALocaleIsNotOne() {
        assertNull(parse("en-US/entry/my-post").locale(),
                "a hyphen is not the separator this scheme uses, so en-US is a context");
        assertEquals("en-US", parse("en-US/entry/my-post").context());

        assertNull(parse("about/us").locale(), "three letters is not a locale");
        assertNull(parse("abcde/x").locale(), "five characters without an underscore is not");
    }

    /**
     * Characterises a limitation rather than endorsing it: any first segment
     * that parses as a locale is read as one, so a static page whose slug is
     * exactly two letters -- or five with an underscore in the middle -- can
     * never be reached. {@code /myblog/de} is the weblog home in German, not
     * the page slugged "de". The url scheme has no way to tell them apart, and
     * this test exists so that the next person to hit it finds an answer
     * instead of a mystery.
     */
    @Test
    void aTwoLetterPageSlugIsShadowedByLocaleDetection() {
        var parsed = parse("de");

        assertEquals("de", parsed.locale(),
                "read as a locale, so a page slugged \"de\" is unreachable");
        assertNull(parsed.context(),
                "and with no context the request becomes the weblog home page");
    }

    // --- stripping the context path and the outer slashes -----------------

    @Test
    void aRootContextPathLeavesTheWholePath() {
        var normalized = WeblogRequestMapper.normalizePath("/myblog/entry/my-post", "");

        assertEquals("myblog/entry/my-post", normalized.path());
        assertFalse(normalized.trailingSlash());
    }

    /**
     * The prefixed case is the one that has gone wrong here before: at the root
     * context the substring below is a no-op, so a mistake in it is invisible
     * until someone deploys under a prefix.
     */
    @Test
    void aPrefixedContextPathIsRemoved() {
        var normalized = WeblogRequestMapper.normalizePath("/pressroom/myblog/entry", "/pressroom");

        assertEquals("myblog/entry", normalized.path(),
                "the deployment prefix is not part of the weblog url space");
    }

    @Test
    void aTrailingSlashIsRecordedAndRemoved() {
        var normalized = WeblogRequestMapper.normalizePath("/myblog/", "");

        assertEquals("myblog", normalized.path());
        assertTrue(normalized.trailingSlash(),
                "http treats /myblog and /myblog/ as different resources, and the mapper "
                        + "redirects the first to the second, so the distinction must survive");
    }

    /**
     * The bare context root is the one path whose trailing slash is consumed
     * rather than recorded: stripping the leading slash from "/" leaves an
     * empty string, and "" does not end with a slash.
     *
     * <p>That is load-bearing further up. handleRequest force-sets
     * trailingSlash for an empty path in virtual-host mode precisely because
     * of this, since a custom domain's root already IS the canonical url and
     * would otherwise be redirected to itself.
     */
    @Test
    void theBareRootLosesItsOnlySlashAndSoIsNotSeenAsTrailing() {
        var normalized = WeblogRequestMapper.normalizePath("/", "");

        assertEquals("", normalized.path());
        assertFalse(normalized.trailingSlash(),
                "the leading-slash strip consumed it, which is what the vhost branch in "
                        + "handleRequest compensates for");
    }

    @Test
    void noUriMeansThisIsNotOurRequest() {
        assertNull(WeblogRequestMapper.normalizePath(null, ""),
                "null tells handleRequest to decline rather than to guess");
    }

    @Test
    void aNullContextPathIsToleratedRatherThanAssumedEmpty() {
        var normalized = WeblogRequestMapper.normalizePath("/myblog", null);

        assertEquals("myblog", normalized.path());
    }
}
