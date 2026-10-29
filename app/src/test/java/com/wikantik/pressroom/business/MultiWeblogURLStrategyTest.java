package com.wikantik.pressroom.business;

import java.util.List;

import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Url generation for a weblog that owns a hostname.
 *
 * <p>These are pure-function tests on the strategy: they build a detached
 * Weblog rather than persisting one, because getWeblogURL reads only the
 * handle and the custom domain.
 */
class MultiWeblogURLStrategyTest {

    private final MultiWeblogURLStrategy strategy = new MultiWeblogURLStrategy();

    private String previousRelativeContextURL;

    /**
     * WebloggerRuntimeConfig's relative/absolute context url fields are
     * per-JVM statics that other test classes set and don't reliably restore
     * (the "CI order dependency" noted in project docs) -- so the
     * non-custom-domain branch below would otherwise see whatever an
     * unrelated test left behind, depending on run order. Pinning it here
     * (the same save/restore shape URLModelTest already uses for this same
     * static state) is what makes the characterisation assertion below
     * deterministic rather than order-dependent.
     */
    @BeforeEach
    void pinRelativeContextURL() {
        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
    }

    @AfterEach
    void restoreRelativeContextURL() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
    }

    private static Weblog weblog(String handle, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setCustomDomain(customDomain);
        return weblog;
    }

    @Test
    void anAbsoluteUrlUsesTheCustomDomainAndDropsTheHandle() {
        assertEquals("https://vhost.example.com/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, true));
    }

    /**
     * Root-relative with no handle segment. The page is only ever served on its
     * own domain (any other host 301s), so a relative link needs no host -- and
     * it must not carry the handle, which does not exist in that url space.
     */
    @Test
    void aRelativeUrlDropsTheHandleToo() {
        assertEquals("/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, false));
    }

    @Test
    void aLocaleStillFollowsTheWeblogRoot() {
        assertEquals("https://vhost.example.com/de/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), "de", true));
    }

    @Test
    void anEntryUrlIsBuiltOnTheCustomDomainRoot() {
        assertEquals("https://vhost.example.com/entry/my-post",
                strategy.getWeblogEntryURL(
                        weblog("vhostblog", "vhost.example.com"), null, "my-post", true));
    }

    /**
     * CHARACTERISATION: a weblog with no custom domain keeps today's shape
     * exactly. Expected to pass on arrival.
     */
    @Test
    void aWeblogWithoutACustomDomainIsUnchanged() {
        assertEquals("/plainblog/",
                strategy.getWeblogURL(weblog("plainblog", null), null, false));
    }

    /**
     * CHARACTERISATION: at the root context ("", the pinned default above),
     * a custom-domain weblog's urls are exactly what they were before this
     * class existed -- no context path segment to add. Expected to pass on
     * arrival.
     */
    @Test
    void atTheRootContextTheCustomDomainUrlIsUnchanged() {
        assertEquals("https://vhost.example.com/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, true));
        assertEquals("/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, false));
    }

    /**
     * Under a servlet-container prefix, a custom-domain weblog's root on
     * that hostname is "/pressroom/", not "/" -- the host supplies the handle,
     * not the context path. The prefix must appear exactly once.
     */
    @Test
    void underAContextPathTheCustomDomainUrlCarriesThePrefixExactlyOnce() {
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");

        assertEquals("https://vhost.example.com/pressroom/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, true));
        assertEquals("/pressroom/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, false));
    }

    /**
     * A derived url (entry, not just the weblog root) must carry the prefix
     * too, since it is built on top of getWeblogURL.
     */
    @Test
    void underAContextPathADerivedUrlCarriesThePrefixToo() {
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");

        assertEquals("https://vhost.example.com/pressroom/entry/my-post",
                strategy.getWeblogEntryURL(
                        weblog("vhostblog", "vhost.example.com"), null, "my-post", true));
    }

    // --- the shared builders, now that both strategies run through them ----

    /**
     * These cover MultiWeblogURLStrategy directly rather than through a custom
     * domain, which is what the tests above are about. It carries the logic for
     * BOTH strategies since PreviewURLStrategy was reduced to two hooks, so a
     * gap here is a gap in the preview too.
     */
    @Test
    void everyBuilderDeclinesAMissingWeblog() {
        assertNull(strategy.getWeblogURL(null, null, false));
        assertNull(strategy.getWeblogEntryURL(null, null, "anchor", false));
        assertNull(strategy.getWeblogFeedURL(null, null, "entries", "atom", null, null,
                List.of(), false, false));
        assertNull(strategy.getWeblogSearchURL(null, null, "q", null, 0, false));
        assertNull(strategy.getWeblogResourceURL(null, "f.png", false));
        assertNull(strategy.getWeblogSearchPageURLTemplate(null));
    }

    @Test
    void anEntryUrlNeedsAnAnchor() {
        assertNull(strategy.getWeblogEntryURL(weblog("blog", null), null, null, false),
                "there is no url for \"some entry, unspecified\"");
    }

    @Test
    void aResourceUrlNeedsAPath() {
        assertNull(strategy.getWeblogResourceURL(weblog("blog", null), null, false));
        assertNull(strategy.getWeblogResourceURL(weblog("blog", null), "", false),
                "an empty path names no resource");
    }

    @Test
    void aFeedCarriesOnlyTheNarrowingItWasGiven() {
        String bare = strategy.getWeblogFeedURL(weblog("blog", null), null, "entries", "atom",
                null, null, List.of(), false, false);

        assertEquals("/blog/feed/entries/atom", bare,
                "no category, no tags, no term, no excerpts: no query string at all");
    }

    @Test
    void blankNarrowingIsTreatedAsAbsent() {
        String url = strategy.getWeblogFeedURL(weblog("blog", null), null, "entries", "atom",
                "   ", "  ", List.of(), false, false);

        assertEquals("/blog/feed/entries/atom", url,
                "a blank category or term is not a filter, and must not become an empty "
                        + "query parameter: " + url);
    }

    @Test
    void aFeedCarriesTheNarrowingItWasGiven() {
        String url = strategy.getWeblogFeedURL(weblog("blog", null), null, "entries", "atom",
                "Travel", "spain", List.of("hiking"), true, false);

        assertTrue(url.startsWith("/blog/feed/entries/atom?"), url);
        assertTrue(url.contains("cat=Travel"), url);
        assertTrue(url.contains("q=spain"), url);
        assertTrue(url.contains("tags=hiking"), url);
        assertTrue(url.contains("excerpts=true"), url);
    }

    @Test
    void aSearchUrlCarriesItsQueryAndCategory() {
        String url = strategy.getWeblogSearchURL(weblog("blog", null), null, "spain",
                "Travel", 2, false);

        assertTrue(url.startsWith("/blog/search?"), url);
        assertTrue(url.contains("q=spain"), url);
        assertTrue(url.contains("cat=Travel"), url);
        assertTrue(url.contains("page=2"), url);
    }

    /**
     * Characterises an inconsistency rather than endorsing it. The feed builder
     * two methods up treats a blank term as absent
     * ({@code term != null && !term.isBlank()}); the search builder checks only
     * for null, so a blank query becomes a real parameter and the url searches
     * for whitespace.
     *
     * <p>Left alone deliberately: the result is a valid url that finds nothing,
     * not a malformed one, so changing it is a behaviour decision rather than a
     * fix. Pinned so that whoever makes that decision knows it was noticed.
     */
    @Test
    void aBlankSearchQueryStillBecomesAParameter() {
        String url = strategy.getWeblogSearchURL(weblog("blog", null), null, "  ", null, 0, false);

        assertEquals("/blog/search?q=++", url,
                "blank is not treated as absent here, unlike on the feed builder");
    }

    @Test
    void aNullSearchQueryDropsTheNarrowingThatDependsOnIt() {
        String url = strategy.getWeblogSearchURL(weblog("blog", null), null, null,
                "Travel", 2, false);

        assertEquals("/blog/search", url,
                "a category and a page number only mean something alongside a query, so "
                        + "with no query they are dropped rather than emitted alone");
    }

    @Test
    void theOpenSearchTemplateKeepsItsPlaceholders() {
        String url = strategy.getWeblogSearchPageURLTemplate(weblog("blog", null));

        assertTrue(url.contains("{searchTerms}"),
                "the placeholders are what make this a template rather than a url: " + url);
        assertTrue(url.contains("{startPage}"), url);
    }

    /** A published Page is served at /handle/slug, NOT /handle/page/slug (the CUSTOM-template route). */
    @Test
    void aStaticPageUrlIsHandleThenSlug() {
        assertEquals("/plainblog/privacy",
                strategy.getWeblogStaticPageURL(weblog("plainblog", null), "privacy", false));
    }

    @Test
    void aStaticPageUrlIsAbsoluteOnTheCustomDomainAndEncodesTheSlug() {
        assertEquals("https://vhost.example.com/a%26b",
                strategy.getWeblogStaticPageURL(weblog("vhostblog", "vhost.example.com"), "a&b", true));
    }

    @Test
    void aStaticPageUrlNeedsAWeblogAndASlug() {
        assertNull(strategy.getWeblogStaticPageURL(null, "privacy", false));
        assertNull(strategy.getWeblogStaticPageURL(weblog("b", null), null, false));
    }
}
