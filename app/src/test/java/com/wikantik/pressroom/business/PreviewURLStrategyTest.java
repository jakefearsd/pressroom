package com.wikantik.pressroom.business;

import java.util.List;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Url generation for the theme-preview rendering pass.
 *
 * <p>This strategy overrides most of {@link MultiWeblogURLStrategy} in order to
 * point urls at {@code /roller-ui/authoring/preview/...} and to carry the
 * previewed theme along as a query parameter. Overriding that much is the
 * hazard the tests below exist for: an override that quietly drops a guard or
 * an escape its parent applies produces urls that are wrong only in preview,
 * where nobody is looking, while the same call in production stays correct.
 * Three such drops were found by writing this class and are pinned by name
 * below -- an unencoded page link, an unencoded resource path, and a missing
 * null check that turned into a NullPointerException.
 *
 * <p>These are pure-function tests: the Weblog is detached rather than
 * persisted, because only the handle is read.
 */
class PreviewURLStrategyTest {

    private final PreviewURLStrategy preview = new PreviewURLStrategy("journal");
    private final PreviewURLStrategy noTheme = new PreviewURLStrategy(null);

    private static final String ROOT = "/roller-ui/authoring/preview/myblog/";
    private static final String RESOURCE_ROOT = "/roller-ui/authoring/previewresource/myblog/";

    private String previousRelativeContextURL;
    private String previousAbsoluteContextURL;

    /**
     * The context urls are per-JVM statics that other test classes set without
     * reliably restoring, so they are pinned here for the same reason
     * {@link MultiWeblogURLStrategyTest} pins them: otherwise these assertions
     * depend on run order.
     */
    @BeforeEach
    void pinContextURLs() {
        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        previousAbsoluteContextURL = WebloggerRuntimeConfig.getAbsoluteContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
        WebloggerRuntimeConfig.setAbsoluteContextURL("https://example.com");
    }

    @AfterEach
    void restoreContextURLs() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(previousAbsoluteContextURL);
    }

    private static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        return weblog;
    }

    // --- the three defects this class was written to catch ----------------

    /**
     * The parent encodes the page link ({@code MultiWeblogURLStrategy} calls
     * {@code URLUtilities.encode}); this override did not, so an author who
     * named a page "my page" got a url with a raw space in it -- and a page
     * named with a "?" or "&" could push text into the query string that
     * carries the previewed theme.
     */
    @Test
    void aPageLinkIsEncodedTheWayTheParentEncodesIt() {
        String url = preview.getWeblogPageURL(
                weblog(), null, "my page", null, null, null, List.of(), -1, false);

        assertTrue(url.startsWith(ROOT + "page/my+page"),
                "A page link must be encoded before it goes in the path, exactly as the "
                        + "parent strategy encodes it. Was: " + url);
        assertFalse(url.contains(' ' + ""), "and must not contain a raw space: " + url);
    }

    @Test
    void aPageLinkCannotOpenItsOwnQueryString() {
        String url = preview.getWeblogPageURL(
                weblog(), null, "evil?theme=attacker", null, null, null, List.of(), -1, false);

        assertEquals(1, url.chars().filter(c -> c == '?').count(),
                "An encoded page link cannot introduce a second '?', which would let a page "
                        + "name prepend its own theme parameter ahead of the real one. Was: " + url);
    }

    /**
     * The parent returns null for a missing file path
     * ({@code weblog == null || StringUtils.isEmpty(filePath)}); this override
     * checked only the weblog and then dereferenced the path.
     */
    @Test
    void aMissingResourcePathIsNullRatherThanAnException() {
        assertNull(preview.getWeblogResourceURL(weblog(), null, false),
                "A null file path must produce no url, the way the parent strategy does, "
                        + "rather than a NullPointerException out of a template");
        assertNull(preview.getWeblogResourceURL(weblog(), "", false),
                "and an empty one likewise");
    }

    /**
     * The parent runs the path through {@code URLUtilities.encodePath}; this
     * override appended it raw.
     */
    @Test
    void aResourcePathIsEncodedTheWayTheParentEncodesIt() {
        assertEquals(RESOURCE_ROOT + "my+file.png?theme=journal",
                preview.getWeblogResourceURL(weblog(), "/my file.png", false),
                "A resource path must be encoded per segment, as the parent does");
    }

    // --- weblog root ------------------------------------------------------

    @Test
    void noWeblogMeansNoUrl() {
        assertNull(preview.getWeblogURL(null, null, false));
        assertNull(preview.getWeblogEntryURL(null, null, "x", false));
        assertNull(preview.getWeblogCollectionURL(null, null, null, null, null, -1, false));
        assertNull(preview.getWeblogPageURL(null, null, "p", null, null, null, null, -1, false));
        assertNull(preview.getWeblogResourceURL(null, "f.png", false));
    }

    @Test
    void theWeblogRootCarriesThePreviewedTheme() {
        assertEquals(ROOT + "?theme=journal", preview.getWeblogURL(weblog(), null, false));
    }

    @Test
    void withoutAPreviewThemeThereIsNoQueryString() {
        assertEquals(ROOT, noTheme.getWeblogURL(weblog(), null, false));
    }

    @Test
    void anAbsoluteUrlIsPrefixedWithTheAbsoluteContextUrl() {
        assertEquals("https://example.com" + ROOT + "?theme=journal",
                preview.getWeblogURL(weblog(), null, true));
    }

    @Test
    void aLocaleFollowsTheWeblogHandle() {
        assertEquals(ROOT + "de/?theme=journal", preview.getWeblogURL(weblog(), "de", false));
    }

    // --- entry ------------------------------------------------------------

    @Test
    void anEntryIsPreviewedByAnchorAsAQueryParameter() {
        String url = preview.getWeblogEntryURL(weblog(), null, "my-post", false);

        assertTrue(url.startsWith(ROOT + "?"), "The entry preview hangs off the weblog root");
        assertTrue(url.contains("previewEntry=my-post"), "carrying the anchor: " + url);
        assertTrue(url.contains("theme=journal"), "and the previewed theme: " + url);
    }

    @Test
    void anEntryWithoutAnAnchorJustCarriesTheTheme() {
        assertEquals(ROOT + "?theme=journal",
                preview.getWeblogEntryURL(weblog(), null, null, false));
    }

    @Test
    void anEntryAnchorIsEncoded() {
        assertTrue(preview.getWeblogEntryURL(weblog(), null, "my post", false)
                        .contains("previewEntry=my+post"),
                "An anchor with a space must not reach the url raw");
    }

    // --- collections ------------------------------------------------------

    @Test
    void aCategoryBecomesAPathSegment() {
        assertEquals(ROOT + "category/Travel+Notes?theme=journal",
                preview.getWeblogCollectionURL(
                        weblog(), null, "Travel Notes", null, null, -1, false));
    }

    @Test
    void theRootCategoryIsTreatedAsNoCategoryAtAll() {
        assertEquals(ROOT + "?theme=journal",
                preview.getWeblogCollectionURL(weblog(), null, "root", null, null, -1, false),
                "\"root\" is the whole weblog, so it must not become a category segment");
    }

    @Test
    void aDateBecomesAPathSegment() {
        assertEquals(ROOT + "date/20051110?theme=journal",
                preview.getWeblogCollectionURL(weblog(), null, null, "20051110", null, -1, false));
    }

    @Test
    void tagsBecomeAPathSegment() {
        assertEquals(ROOT + "tags/apple+banana?theme=journal",
                preview.getWeblogCollectionURL(
                        weblog(), null, null, null, List.of("apple", "banana"), -1, false));
    }

    @Test
    void anEmptyTagListIsNotATagsView() {
        assertEquals(ROOT + "?theme=journal",
                preview.getWeblogCollectionURL(weblog(), null, null, null, List.of(), -1, false));
    }

    @Test
    void aCategoryAndADateTogetherFallBackToQueryParameters() {
        String url = preview.getWeblogCollectionURL(
                weblog(), null, "Travel", "20051110", null, -1, false);

        assertTrue(url.contains("cat=Travel"), "both survive as parameters: " + url);
        assertTrue(url.contains("date=20051110"), "both survive as parameters: " + url);
    }

    @Test
    void aPageNumberIsAQueryParameterAndOnlyWhenPositive() {
        assertTrue(preview.getWeblogCollectionURL(weblog(), null, null, null, null, 2, false)
                .contains("page=2"));
        assertFalse(preview.getWeblogCollectionURL(weblog(), null, null, null, null, 0, false)
                .contains("page="), "page 0 is the first page and is left implicit");
    }

    // --- custom pages -----------------------------------------------------

    @Test
    void aCustomPageBecomesAPageSegment() {
        assertEquals(ROOT + "page/about?theme=journal",
                preview.getWeblogPageURL(
                        weblog(), null, "about", null, null, null, null, -1, false));
    }

    @Test
    void aPageUrlWithNoPageLinkIsJustACollectionUrl() {
        assertEquals(
                preview.getWeblogCollectionURL(weblog(), null, "Travel", null, null, -1, false),
                preview.getWeblogPageURL(
                        weblog(), null, null, null, "Travel", null, null, -1, false),
                "With no page link there is no custom page, so this is the collection url");
    }

    @Test
    void aCustomPageTakesItsNarrowingAsQueryParameters() {
        String url = preview.getWeblogPageURL(weblog(), null, "about", null,
                "Travel", "20051110", List.of("apple"), 3, false);

        assertTrue(url.startsWith(ROOT + "page/about?"), "still a page url: " + url);
        assertTrue(url.contains("cat=Travel"), url);
        assertTrue(url.contains("date=20051110"), url);
        assertTrue(url.contains("tags=apple"), url);
        assertTrue(url.contains("page=3"), url);
    }

    // --- resources --------------------------------------------------------

    @Test
    void aResourcePathLosesItsLeadingSlash() {
        assertEquals(RESOURCE_ROOT + "logo.png?theme=journal",
                preview.getWeblogResourceURL(weblog(), "/logo.png", false));
        assertEquals(RESOURCE_ROOT + "logo.png?theme=journal",
                preview.getWeblogResourceURL(weblog(), "logo.png", false),
                "and a path without one is used as-is");
    }

    @Test
    void aResourcePathKeepsItsDirectorySeparators() {
        assertEquals(RESOURCE_ROOT + "img/sub+dir/logo.png?theme=journal",
                preview.getWeblogResourceURL(weblog(), "/img/sub dir/logo.png", false),
                "encodePath escapes each segment but leaves '/' alone, or the path would "
                        + "collapse into one flat segment");
    }

    @Test
    void aCustomThemeIsNotPassedAsAPreviewTheme() {
        assertEquals(RESOURCE_ROOT + "logo.png",
                new PreviewURLStrategy(WeblogTheme.CUSTOM)
                        .getWeblogResourceURL(weblog(), "/logo.png", false),
                "A weblog on its own custom theme has no shared theme to preview, so there "
                        + "is nothing to name in the query string");
    }

    @Test
    void withoutAThemeAResourceUrlHasNoQueryString() {
        assertEquals(RESOURCE_ROOT + "logo.png",
                noTheme.getWeblogResourceURL(weblog(), "/logo.png", false));
    }

    // --- the builders preview does NOT override ---------------------------

    /**
     * These three are inherited from MultiWeblogURLStrategy, which builds them
     * by using getWeblogURL() as a PREFIX. The preview override of that method
     * returns the root with its query string already attached, so the inherited
     * builders spliced a path segment onto the end of the theme parameter's
     * value:
     *
     * <pre>/roller-ui/authoring/preview/myblog/?theme=journalfeed/entries/atom</pre>
     *
     * Every feed, search and media url in a theme preview was malformed that
     * way -- the media one meaning images did not load while previewing a
     * theme. The fix is structural: the root is now a separate hook from the
     * url, so a prefix is always just a prefix.
     */
    @Test
    void anInheritedFeedUrlIsNotSplicedIntoTheThemeParameter() {
        String url = preview.getWeblogFeedURL(
                weblog(), null, "entries", "atom", null, null, List.of(), false, false);

        assertTrue(url.contains(ROOT + "feed/entries/atom"),
                "the feed path must follow the weblog root, not the query string: " + url);
        assertEquals(1, url.chars().filter(c -> c == '?').count(),
                "one query string: " + url);
        assertTrue(url.contains("theme=journal") && !url.contains("theme=journalfeed"),
                "the previewed theme must survive as its own value: " + url);
    }

    @Test
    void anInheritedSearchUrlIsWellFormed() {
        String url = preview.getWeblogSearchURL(weblog(), null, "hello", null, 0, false);

        assertTrue(url.contains(ROOT + "search"),
                "the search path must follow the weblog root: " + url);
        assertEquals(1, url.chars().filter(c -> c == '?').count(),
                "one query string, not two: " + url);
        assertTrue(url.contains("q=hello"), url);
        assertTrue(url.contains("theme=journal") && !url.contains("theme=journalsearch"), url);
    }

    /**
     * A media file carries no theme, and should not: it is a weblog upload
     * served by MediaResourceServlet, which has no interest in which theme is
     * being previewed. (Theme-shipped files are a different thing and go
     * through getWeblogResourceURL, which does carry it.) What matters here is
     * that the path is a path -- it used to be spliced onto the end of the
     * theme parameter's value, which is why images did not load in previews.
     */
    @Test
    void anInheritedMediaFileUrlIsWellFormed() {
        String url = preview.getMediaFileURL(weblog(), "photo.jpg", false);

        assertEquals(ROOT + "mediaresource/photo.jpg", url,
                "an image previewed with a theme has to resolve, or the preview shows "
                        + "broken images");
        assertEquals(0, url.chars().filter(c -> c == '?').count(),
                "and needs no query string at all: " + url);
    }
}
