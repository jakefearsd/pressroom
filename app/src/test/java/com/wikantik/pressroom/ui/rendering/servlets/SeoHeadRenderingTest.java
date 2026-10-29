package com.wikantik.pressroom.ui.rendering.servlets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.JsonLdType;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.editor.EntryBean;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real themes through the real PageServlet and asserts on the
 * automatic SEO baseline that {@code #showSeoHead} emits into every theme
 * head: meta description, robots, rel=canonical, Open Graph, Twitter card
 * and JSON-LD. The JSON-LD blocks are extracted and actually JSON-parsed --
 * a stray comma or an unescaped quote makes crawlers drop the whole block,
 * and string-contains assertions would never notice.
 */
class SeoHeadRenderingTest {

    private static final String HANDLE = "seorenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    private static final Pattern LD_JSON = Pattern.compile(
            "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);

    private User user;
    private Weblog weblog;
    private Business business;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("seorenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager()
                .removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        if (business != null) {
            BusinessManager businesses = TestUtils.weblogger().getBusinessManager();
            businesses.removeBusiness(businesses.getBusiness(business.getId()));
            business = null;
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private void updateEntry(WeblogEntry entry, Consumer<WeblogEntry> mutation)
            throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        mutation.accept(managed);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    /**
     * Saves a published static page, applying {@code mutation} to set
     * whichever SEO fields a test needs -- the {@code WeblogPage} twin of
     * {@link #updateEntry}, but a page has no pre-existing row to fetch and
     * mutate, so this both creates and saves in one call.
     */
    private WeblogPage savePage(String slug, String title, Consumer<WeblogPage> mutation)
            throws Exception {
        WeblogPageManager mgr = TestUtils.weblogger().getWeblogPageManager();
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        if (mutation != null) {
            mutation.accept(page);
        }
        mgr.savePage(page);
        TestUtils.endSession(true);
        return page;
    }

    /**
     * The same blocks with the {@code dateModified} value blanked out.
     *
     * <p>Not a convenience: {@code saveWeblogEntry} restamps {@code updateTime}
     * on every save and a test cannot prevent it, so that one value differs
     * between two renders taken either side of a second boundary. Blanking it
     * keeps the byte-for-byte comparison meaningful for everything a test can
     * actually hold still.
     */
    private static List<String> withoutModificationStamp(List<String> blocks) {
        return blocks.stream()
                .map(block -> block.replaceAll("\"dateModified\": \"[^\"]*\"",
                        "\"dateModified\": \"\""))
                .toList();
    }

    /**
     * Waits until the wall clock's second has advanced, so the next save's
     * {@code updateTime} formats differently from the last one's.
     */
    private static void crossASecondBoundary() throws InterruptedException {
        long startedInSecond = System.currentTimeMillis() / 1000L;
        do {
            Thread.sleep(50);
        } while (System.currentTimeMillis() / 1000L == startedInSecond);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    /**
     * The RAW source of every ld+json block, whitespace and all -- what the
     * byte-stability guard compares, since a parsed tree would happily call two
     * differently formatted blocks equal.
     */
    private static List<String> ldJsonSources(String body) {
        List<String> sources = new ArrayList<>();
        Matcher matcher = LD_JSON.matcher(body);
        while (matcher.find()) {
            sources.add(matcher.group(1));
        }
        return sources;
    }

    /** Extract every ld+json block and JSON-parse it; parse failure fails the test. */
    private static List<JsonNode> ldJsonBlocks(String body) {
        List<JsonNode> nodes = new ArrayList<>();
        Matcher matcher = LD_JSON.matcher(body);
        ObjectMapper mapper = new ObjectMapper();
        while (matcher.find()) {
            nodes.add(mapper.readTree(matcher.group(1)));
        }
        return nodes;
    }

    private static JsonNode singleLdJson(String body, String expectedType) {
        List<JsonNode> nodes = ldJsonBlocks(body);
        assertEquals(1, nodes.size(),
                "expected exactly one JSON-LD block; body:\n" + body);
        JsonNode node = nodes.get(0);
        assertEquals(expectedType, node.path("@type").asString(),
                "wrong JSON-LD @type in: " + node);
        assertEquals("https://schema.org", node.path("@context").asString());
        return node;
    }

    /**
     * The typed block of a permalink that has one, having first asserted that
     * the BlogPosting block is still there and still first. A travel post is
     * still a blog post: the typed object is emitted ALONGSIDE BlogPosting,
     * never instead of it, or the entry silently loses its datePublished,
     * dateModified, author and headline.
     */
    private static JsonNode typedLdJson(String body, String expectedType) {
        List<JsonNode> nodes = ldJsonBlocks(body);
        assertEquals(2, nodes.size(),
                "a typed permalink emits BlogPosting AND the typed object; body:\n" + body);
        assertEquals("BlogPosting", nodes.get(0).path("@type").asString(),
                "BlogPosting must still come first: " + nodes);
        JsonNode typed = nodes.get(1);
        assertEquals(expectedType, typed.path("@type").asString(),
                "wrong JSON-LD @type in: " + typed);
        assertEquals("https://schema.org", typed.path("@context").asString());
        return typed;
    }

    // --------------------------------------------------------------- journal

    @Test
    void permalinkEmitsTheFullSeoBaseline() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("seo-entry", weblog, user);
        updateEntry(entry, e -> e.setSearchDescription("A concise entry description."));

        String body = render("/" + HANDLE + "/entry/seo-entry");
        String canonical = BASE + "/entry/seo-entry";

        assertTrue(body.contains(
                "<meta name=\"description\" content=\"A concise entry description.\">"),
                "permalink meta description must be the entry's search description:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + canonical + "\">"),
                "canonical must be the exact absolute entry URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"article\">"),
                "a permalink is an og article:\n" + body);
        assertTrue(body.contains("<meta property=\"og:title\" content=\"seo-entry\">"),
                "og:title must be the entry title:\n" + body);
        assertTrue(body.contains(
                "<meta property=\"og:description\" content=\"A concise entry description.\">"));
        assertTrue(body.contains("<meta property=\"og:url\" content=\"" + canonical + "\">"));
        assertTrue(body.contains("<meta property=\"og:site_name\" content="),
                "og:site_name must be present:\n" + body);
        assertTrue(body.contains("<meta name=\"twitter:card\" content=\"summary\">"),
                "with no image the twitter card is a plain summary:\n" + body);
        assertFalse(body.contains("<meta name=\"robots\""),
                "an ordinary entry must not carry a robots directive:\n" + body);

        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals("seo-entry", posting.path("headline").asString());
        assertEquals(canonical, posting.path("mainEntityOfPage").asString());
        assertEquals("A concise entry description.",
                posting.path("description").asString());
        assertFalse(posting.path("datePublished").asString().isEmpty(),
                "datePublished must be present: " + posting);
        assertFalse(posting.path("dateModified").asString().isEmpty(),
                "dateModified must be present: " + posting);
        assertEquals("Person", posting.path("author").path("@type").asString());
        assertEquals("Test User Screen Name",
                posting.path("author").path("name").asString(),
                "author must be the entry creator's screen name: " + posting);
    }

    @Test
    void frontPageEmitsWebsiteSeo() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setAbout("All about rendering tests");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("front-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + BASE + "/\">"),
                "the front page canonicalises to the weblog home URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"website\">"),
                "a collection page is an og website:\n" + body);
        assertTrue(body.contains(
                "<meta name=\"description\" content=\"All about rendering tests\">"),
                "the front page description is the weblog's about text:\n" + body);

        JsonNode blog = singleLdJson(body, "Blog");
        assertEquals("Test Weblog", blog.path("name").asString());
        assertEquals(BASE + "/", blog.path("url").asString());
        assertEquals("All about rendering tests", blog.path("description").asString());
    }

    @Test
    void noindexEntryGetsARobotsMeta() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("hidden-entry", weblog, user);
        updateEntry(entry, e -> e.setNoindex(true));

        String body = render("/" + HANDLE + "/entry/hidden-entry");

        assertTrue(body.contains("<meta name=\"robots\" content=\"noindex\">"),
                "a noindex entry must carry the robots directive:\n" + body);
    }

    @Test
    void perEntryCanonicalOverrideWinsOnThePermalink() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("syndicated-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("https://original.example.com/first-home"));

        String body = render("/" + HANDLE + "/entry/syndicated-entry");

        assertTrue(body.contains(
                "<link rel=\"canonical\" href=\"https://original.example.com/first-home\">"),
                "the per-entry canonical override must win:\n" + body);
        assertFalse(body.contains(
                "<link rel=\"canonical\" href=\"" + BASE + "/entry/syndicated-entry\">"),
                "the natural permalink must not also be claimed as canonical:\n" + body);
        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals("https://original.example.com/first-home",
                posting.path("mainEntityOfPage").asString());
    }

    // ------------------------------------------------------------- static pages

    @Test
    void staticPageEmitsItsOwnCanonicalNotTheHomepage() throws Exception {
        savePage("about-us", "About Us", null);

        String body = render("/" + HANDLE + "/about-us");
        String canonical = BASE + "/about-us";

        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + canonical + "\">"),
                "a static page must canonicalize to its own URL:\n" + body);
        assertFalse(body.contains("<link rel=\"canonical\" href=\"" + BASE + "/\">"),
                "a static page must NOT canonicalize to the weblog homepage:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"website\">"),
                "a static page is an og website, not an article:\n" + body);
        assertTrue(body.contains("<meta property=\"og:title\" content=\"About Us\">"),
                "og:title must fall back to the page title:\n" + body);
        assertTrue(body.contains("<meta property=\"og:url\" content=\"" + canonical + "\">"));

        JsonNode blog = singleLdJson(body, "Blog");
        assertEquals(canonical, blog.path("url").asString(),
                "the page's own JSON-LD url must match its canonical:\n" + body);
    }

    @Test
    void noindexPageGetsARobotsMeta() throws Exception {
        savePage("hidden-page", "Hidden Page", p -> p.setNoindex(true));

        String body = render("/" + HANDLE + "/hidden-page");

        assertTrue(body.contains("<meta name=\"robots\" content=\"noindex\">"),
                "a noindex page must carry the robots directive:\n" + body);
    }

    @Test
    void pageSearchDescriptionIsEmittedAsTheMetaDescription() throws Exception {
        savePage("described-page", "Described Page",
                p -> p.setSearchDescription("A concise page description."));

        String body = render("/" + HANDLE + "/described-page");

        assertTrue(body.contains(
                "<meta name=\"description\" content=\"A concise page description.\">"),
                "the page meta description must be the page's own search description:\n" + body);
        assertTrue(body.contains(
                "<meta property=\"og:description\" content=\"A concise page description.\">"));
    }

    @Test
    void pageMetaTitleOverridesTheTitleTagAndOgTitleButNotTheHeading() throws Exception {
        savePage("meta-page", "Real Page Title", p -> p.setMetaTitle("SEO Title Override"));

        String body = render("/" + HANDLE + "/meta-page");

        assertTrue(body.contains("<title>SEO Title Override</title>"),
                "the meta title must replace the whole <title>, as the editor's search "
                        + "preview shows it -- no weblog name appended:\n" + body);
        assertTrue(body.contains("<meta property=\"og:title\" content=\"SEO Title Override\">"));
        assertTrue(body.contains(">Real Page Title</h1>"),
                "the meta title must not leak into the page's own heading:\n" + body);
    }

    @Test
    void perPageCanonicalOverrideWinsOnTheStaticPage() throws Exception {
        savePage("syndicated-page", "Syndicated Page",
                p -> p.setCanonicalUrl("https://original.example.com/first-home"));

        String body = render("/" + HANDLE + "/syndicated-page");

        assertTrue(body.contains(
                "<link rel=\"canonical\" href=\"https://original.example.com/first-home\">"),
                "the per-page canonical override must win:\n" + body);
        assertFalse(body.contains(
                "<link rel=\"canonical\" href=\"" + BASE + "/syndicated-page\">"),
                "the natural page URL must not also be claimed as canonical:\n" + body);
    }

    @Test
    void aPageOgImageIsResolvedFromItsOwnOgImageId() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "page-og-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        savePage("pictured-page", "Pictured Page", p -> p.setOgImageId(imageId));

        String body = render("/" + HANDLE + "/pictured-page");

        String imageUrl = BASE + "/mediaresource/" + imageId;
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "og:image must point at the page's own og image:\n" + body);
        assertTrue(body.contains(
                "<meta name=\"twitter:card\" content=\"summary_large_image\">"),
                "with an image the twitter card upgrades to summary_large_image:\n" + body);
    }

    // ------------------------------------------------------ escaping (carry-forward)

    @Test
    void aCanonicalUrlContainingAQuoteFailsValidationAndFallsBackToTheNaturalPermalink()
            throws Exception {
        // Before the canonical-URL http(s) allowlist (see
        // aNonHttpCanonicalUrlIsIgnoredAtEmissionRatherThanShipped below) this
        // value reached the page verbatim, relying on template-level escaping
        // to keep the quote from breaking out of the attribute. A literal
        // quote is not a well-formed URI character -- UrlValidator, the same
        // one CtaShortcode's href check uses, rejects it (see
        // CtaShortcodeTest#aQuoteSmuggledIntoTheHrefCannotBreakOutOfTheAttribute) --
        // so PageModel#getCanonicalUrl's emission-time filter now catches this
        // exactly like a javascript: URL and falls back to the entry's
        // natural permalink instead of emitting anything derived from the
        // stored value.
        WeblogEntry entry = TestUtils.setupWeblogEntry("escape-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("https://example.com/pa\"th"));

        String body = render("/" + HANDLE + "/entry/escape-entry");

        assertFalse(body.contains("https://example.com/pa\"th"),
                "the raw quote must never reach the page, in any context:\n" + body);
        assertFalse(body.contains("pa&quot;th"),
                "the malformed URL must not be emitted at all, escaped or otherwise:\n" + body);
        String naturalUrl = BASE + "/entry/escape-entry";
        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + naturalUrl + "\">"),
                "a malformed canonical URL must fall back to the natural permalink:\n" + body);
        // and the JSON-LD block that carries the fallback URL must still parse
        singleLdJson(body, "BlogPosting");
    }

    @Test
    void aNonHttpCanonicalUrlIsIgnoredAtEmissionRatherThanShipped() throws Exception {
        // Save-time validation (EntryEditController) is the front door, but
        // this covers the back door: a row written before that validation
        // existed. PageModel#getCanonicalUrl must fall back to the entry's
        // natural permalink rather than emit the stored scheme anywhere.
        WeblogEntry entry = TestUtils.setupWeblogEntry("javascript-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("javascript:alert(1)"));

        String body = render("/" + HANDLE + "/entry/javascript-entry");

        assertFalse(body.contains("javascript:alert(1)"),
                "a javascript: canonical URL must never reach the rendered page, "
                        + "in any context:\n" + body);
        String naturalUrl = BASE + "/entry/javascript-entry";
        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + naturalUrl + "\">"),
                "the canonical link must fall back to the entry's natural permalink:\n" + body);
        assertTrue(body.contains("<meta property=\"og:url\" content=\"" + naturalUrl + "\">"),
                "og:url must fall back the same way:\n" + body);
        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals(naturalUrl, posting.path("mainEntityOfPage").asString(),
                "mainEntityOfPage must fall back the same way, not carry the javascript: URL");
    }

    @Test
    void aCanonicalUrlWithAnAmpersandIsEscapedInAttributesEverywhereItAppears() throws Exception {
        // Pin: UrlValidator(["http","https"]) ACCEPTS this URL (commons-validator
        // 1.11.0, harness-checked) -- unlike the quote and javascript: cases
        // above, this value survives PageModel#getCanonicalUrl's emission-time
        // filter and reaches the template unchanged. That makes this the only
        // test proving $utils.escapeHTML/escapeJson still run on whatever the
        // validator lets through, rather than the filter alone being what
        // keeps the page safe.
        WeblogEntry entry = TestUtils.setupWeblogEntry("ampersand-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("https://example.com/x?a=1&b=2"));

        String body = render("/" + HANDLE + "/entry/ampersand-entry");

        assertTrue(body.contains(
                "<link rel=\"canonical\" href=\"https://example.com/x?a=1&amp;b=2\">"),
                "the ampersand must be HTML-attribute-escaped in the canonical link:\n" + body);
        assertTrue(body.contains(
                "<meta property=\"og:url\" content=\"https://example.com/x?a=1&amp;b=2\">"),
                "the ampersand must be HTML-attribute-escaped in og:url:\n" + body);
        assertFalse(body.contains("href=\"https://example.com/x?a=1&b=2\""),
                "a raw unescaped ampersand must never reach an HTML attribute:\n" + body);

        // JSON has no need to escape '&', but commons-text's escapeJson does
        // escape '/' to "\/" -- the raw ld+json source must carry exactly
        // that: '&' untouched, '/' escaped.
        List<String> ldSources = ldJsonSources(body);
        assertTrue(ldSources.stream().anyMatch(src -> src.contains(
                "\"mainEntityOfPage\": \"https:\\/\\/example.com\\/x?a=1&b=2\",")),
                "mainEntityOfPage must carry the correctly JSON-escaped value:\n" + ldSources);
        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals("https://example.com/x?a=1&b=2",
                posting.path("mainEntityOfPage").asString(),
                "the JSON-LD value must still parse and round-trip to the exact original URL");
    }

    @Test
    void aMetaTitleContainingAQuoteIsEscapedEverywhereItAppears() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("titled-entry", weblog, user);
        updateEntry(entry, e -> e.setMetaTitle("Sneaky \"quoted\" title"));

        String body = render("/" + HANDLE + "/entry/titled-entry");

        assertTrue(body.contains(
                "<meta property=\"og:title\" content=\"Sneaky &quot;quoted&quot; title\">"),
                "og:title must carry the attribute-escaped meta title:\n" + body);
        assertTrue(body.contains(
                "<title>Sneaky &quot;quoted&quot; title</title>"),
                "the meta title must replace the whole <title>:\n" + body);
        assertFalse(body.contains("Sneaky \"quoted\" title"),
                "the raw quotes must never reach the page:\n" + body);
        assertEquals("Sneaky \"quoted\" title",
                singleLdJson(body, "BlogPosting").path("headline").asString(),
                "the JSON-LD headline must decode back to the original title");
    }

    /**
     * Roller stores UI-authored entry titles HTML-escaped ({@code EntryBean.copyTo}
     * runs them through escapeHtml4, with a symmetric unescape in copyFrom),
     * which is why themes emit {@code $entry.title} raw. #showSeoHead must
     * therefore unescape before its own emit-time escape, or "Fish & Chips"
     * ships to share cards as "Fish &amp;amp; Chips" and lands in the JSON-LD
     * headline as a literal entity.
     *
     * <p>The fixture deliberately goes through the bean rather than setting the
     * title directly: the escape-on-save step is exactly what the other tests
     * here (raw TestUtils titles) bypass, which is why they never caught this.
     */
    @Test
    void aUiAuthoredTitleIsEscapedExactlyOnceInTheSeoHead() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("ampersand-entry", weblog, user);
        saveTitleThroughTheEditorBean(entry, "Fish & \"Chips\"");

        String body = render("/" + HANDLE + "/entry/ampersand-entry");

        assertTrue(body.contains(
                "<meta property=\"og:title\" content=\"Fish &amp; &quot;Chips&quot;\">"),
                "og:title must be escaped exactly once -- and still attribute-safe:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "a stored-escaped title must not be escaped a second time:\n" + body);
        assertFalse(body.contains("Fish & \"Chips\""),
                "the raw quotes must never reach the page:\n" + body);
        assertEquals("Fish & \"Chips\"",
                singleLdJson(body, "BlogPosting").path("headline").asString(),
                "the JSON-LD headline must decode back to the author's title");
    }

    /**
     * Saves {@code title} the way the editor does: through {@link EntryBean},
     * whose copyTo HTML-escapes the title on the way into the database.
     */
    private void saveTitleThroughTheEditorBean(WeblogEntry entry, String title) throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        EntryBean bean = new EntryBean();
        bean.copyFrom(managed, Locale.getDefault());
        bean.setTitle(title);
        // The bean is pure: the controller resolves the category it posted, so
        // hand it the one copyFrom just read off the entry.
        bean.copyTo(managed, managed.getCategory());
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    // ----------------------------------------------------------------- og:image

    @Test
    void theFeaturedImageBecomesTheOgImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "seo-og-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = TestUtils.setupWeblogEntry("pictured-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/pictured-entry");

        // hawk.jpg is 500x373: narrower than 1600, so og:image is the
        // original's URL untouched -- no ?w= rendition parameter.
        String imageUrl = BASE + "/mediaresource/" + imageId;
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "og:image must point at the media file:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"500\">"),
                "og:image:width must be the stored pixel width:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"373\">"),
                "og:image:height must be the stored pixel height:\n" + body);
        assertTrue(body.contains(
                "<meta name=\"twitter:card\" content=\"summary_large_image\">"),
                "with an image the twitter card upgrades to summary_large_image:\n" + body);

        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals(imageUrl, posting.path("image").asString(),
                "the JSON-LD image must match og:image: " + posting);
    }

    /**
     * Persists a content-backed media file from a generated blank image of
     * the given pixel size and format. setupImageMediaFile always uploads
     * the 500px hawk.jpg, which can never exercise the >1600px og:image
     * branch in either direction.
     */
    private MediaFile setupGeneratedImage(String name, String contentType,
            String format, int width, int height) throws Exception {
        java.util.Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory root = mgr.getDefaultMediaFileDirectory(managed);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes),
                "no ImageIO writer for format " + format);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(root);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new PressroomMessages());
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    @Test
    void aWideJpegOgImageUsesThe1600RenditionAndScaledDimensions() throws Exception {
        MediaFile image = setupGeneratedImage("wide.jpg", "image/jpeg", "jpg", 2000, 1000);
        String imageId = image.getId();
        WeblogEntry entry = TestUtils.setupWeblogEntry("wide-jpg-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/wide-jpg-entry");

        // jpeg is rendition-eligible, so the _1600 file exists and ?w=1600
        // really serves a 1600px image: the metadata may claim it.
        String imageUrl = BASE + "/mediaresource/" + imageId + "?w=1600";
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "a wide jpeg must use the 1600px rendition URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"1600\">"),
                "the declared width must match the served rendition:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"800\">"),
                "the declared height must be scaled to the rendition (2000x1000 -> 1600x800):\n" + body);
        assertEquals(imageUrl,
                singleLdJson(body, "BlogPosting").path("image").asString());
    }

    @Test
    void aWideGifOgImageKeepsTheOriginalUrlAndDimensions() throws Exception {
        // RenditionSupport only generates renditions for jpeg/png. For a
        // wide GIF the ?w=1600 URL would silently serve the full-resolution
        // original, so the macro must not claim 1600px dimensions for it.
        MediaFile image = setupGeneratedImage("wide.gif", "image/gif", "gif", 2000, 1000);
        String imageId = image.getId();
        WeblogEntry entry = TestUtils.setupWeblogEntry("wide-gif-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/wide-gif-entry");

        String imageUrl = BASE + "/mediaresource/" + imageId;
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "a wide gif must use the plain original URL:\n" + body);
        assertFalse(body.contains("?w=1600"),
                "no rendition URL may be emitted for a format with no renditions:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"2000\">"),
                "the declared width must be the original's real width:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"1000\">"),
                "the declared height must be the original's real height:\n" + body);
        assertEquals(imageUrl,
                singleLdJson(body, "BlogPosting").path("image").asString());
    }

    // ------------------------------------------------- typed travel JSON-LD

    @Test
    void aTouristAttractionEntryAddsItsTypeAlongsideBlogPosting() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("attraction-entry", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.TOURIST_ATTRACTION);
            e.setSearchDescription("The iron lady of Paris.");
            e.setGeoLatitude(48.8584);
            e.setGeoLongitude(2.2945);
        });

        String body = render("/" + HANDLE + "/entry/attraction-entry");

        String canonical = BASE + "/entry/attraction-entry";
        JsonNode node = typedLdJson(body, "TouristAttraction");
        assertEquals("attraction-entry", node.path("name").asString());
        assertEquals("The iron lady of Paris.", node.path("description").asString());
        assertEquals(canonical, node.path("url").asString());
        assertEquals("GeoCoordinates", node.path("geo").path("@type").asString());
        assertEquals(48.8584, node.path("geo").path("latitude").asDouble());
        assertEquals(2.2945, node.path("geo").path("longitude").asDouble());

        // The two sibling objects are associated by claiming the same page.
        assertEquals(canonical, node.path("mainEntityOfPage").asString());
        assertEquals(canonical,
                ldJsonBlocks(body).get(0).path("mainEntityOfPage").asString(),
                "BlogPosting and the typed object must point at the same URL:\n" + body);
    }

    @Test
    void aTechArticleEntryRendersOneTechArticleBlockAndNoBlogPosting() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("tech-entry", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.TECH_ARTICLE);
            e.setSearchDescription("How the cache expires.");
        });

        String body = render("/" + HANDLE + "/entry/tech-entry");

        JsonNode node = singleLdJson(body, "TechArticle");
        assertEquals("tech-entry", node.path("headline").asString());
        assertEquals("How the cache expires.", node.path("description").asString());
        assertEquals(BASE + "/entry/tech-entry", node.path("mainEntityOfPage").asString());
        assertTrue(!body.contains("\"BlogPosting\""), "no BlogPosting anywhere:\n" + body);
    }

    @Test
    void aTouristTripEntryTurnsItsMapPinsIntoAnOrderedItinerary() throws Exception {
        // The head renders BEFORE entry content, so the itinerary can only come
        // from re-parsing the raw text -- through the very resolver the [map]
        // shortcode uses, so the list and the map can never disagree.
        WeblogEntry entry = TestUtils.setupWeblogEntry("trip-entry", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.TOURIST_TRIP);
            e.setText("Three days in Paris.\n"
                    + "[map][pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]"
                    + "[pin lat=\"48.8606\" lng=\"2.3376\" label=\"Louvre\"][/map]");
        });

        String body = render("/" + HANDLE + "/entry/trip-entry");

        JsonNode itinerary = typedLdJson(body, "TouristTrip").path("itinerary");
        assertEquals("ItemList", itinerary.path("@type").asString());
        JsonNode items = itinerary.path("itemListElement");
        assertEquals(2, items.size(), "one Place per pin: " + itinerary);
        assertEquals("Eiffel Tower", items.get(0).path("name").asString());
        assertEquals("Louvre", items.get(1).path("name").asString(),
                "the itinerary must follow entry-source order: " + itinerary);
        assertEquals(48.8606, items.get(1).path("geo").path("latitude").asDouble());
        assertTrue(body.contains("<div class=\"travel-map\""),
                "the same pins must still reach the reader as a map:\n" + body);
    }

    @Test
    void anEventEntryEmitsIso8601DatesAndItsVenue() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("event-entry", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.EVENT);
            e.setEventStart(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 19, 30)));
            e.setEventEnd(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 22, 0)));
            e.setEventLocation("Champ de Mars");
        });

        String body = render("/" + HANDLE + "/entry/event-entry");

        JsonNode node = typedLdJson(body, "Event");
        String start = node.path("startDate").asString();
        assertTrue(start.startsWith("2026-08-02T19:30:00"),
                "startDate must be ISO-8601, got: " + start);
        assertTrue(start.matches(".*([+-]\\d{2}:?\\d{2}|Z)$"),
                "startDate must carry a zone designator, got: " + start);
        assertTrue(node.path("endDate").asString().startsWith("2026-08-02T22:00:00"));
        assertEquals("Place", node.path("location").path("@type").asString());
        assertEquals("Champ de Mars", node.path("location").path("name").asString());
    }

    @Test
    void anEventWithNoStartDateKeepsItsBlogPosting() throws Exception {
        // An Event without startDate cannot validate; falling back beats
        // shipping structured data no crawler will accept.
        WeblogEntry entry = TestUtils.setupWeblogEntry("undated-event", weblog, user);
        updateEntry(entry, e -> e.setJsonLdType(JsonLdType.EVENT));

        String body = render("/" + HANDLE + "/entry/undated-event");

        singleLdJson(body, "BlogPosting");
    }

    @Test
    void anFaqEntryEmitsTheQuestionsItsBodyRenders() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("faq-entry", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.FAQ_PAGE);
            e.setText("Planning notes.\n"
                    + "[faq][q]When should I go?[/q][a]Spring or autumn.[/a]"
                    + "[q]Is it free?[/q][a]No, tickets are 18 euros.[/a][/faq]");
        });

        String body = render("/" + HANDLE + "/entry/faq-entry");

        JsonNode questions = typedLdJson(body, "FAQPage").path("mainEntity");
        assertEquals(2, questions.size(), "one Question per [q]/[a] pair: " + questions);
        assertEquals("When should I go?", questions.get(0).path("name").asString());
        assertEquals("Spring or autumn.",
                questions.get(0).path("acceptedAnswer").path("text").asString());
        assertEquals("Is it free?", questions.get(1).path("name").asString());
        assertTrue(body.contains("<dl class=\"faq\">"),
                "the same pairs must still reach the reader as a definition list:\n" + body);
    }

    @Test
    void aTypedEntryTitleWithAQuoteStaysInsideItsJsonString() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("quoted-attraction", weblog, user);
        updateEntry(entry, e -> {
            e.setJsonLdType(JsonLdType.TOURIST_ATTRACTION);
            e.setMetaTitle("Chez \"Nous\" </script>");
        });

        String body = render("/" + HANDLE + "/entry/quoted-attraction");

        // Parsing at all is the real assertion: an unescaped quote would make
        // the block unparseable and an unescaped "</script>" would end it early.
        JsonNode node = typedLdJson(body, "TouristAttraction");
        assertEquals("Chez \"Nous\" </script>", node.path("name").asString(),
                "the title must survive exactly one escape pass: " + node);
    }

    @Test
    void theBlogPostingBlockIsByteIdenticalWhateverTheEntrysType() throws Exception {
        // Two guards in one. (a) The double-emit guard: EntryBean.copyTo
        // normalizes a blank dropdown to BLOG_POSTING, so merely re-saving an
        // entry written before this feature existed flips null to BLOG_POSTING
        // in the column, and that must change nothing. (b) The no-regression
        // guard: picking a travel type ADDS a block, it never edits or drops
        // the BlogPosting one, so the entry keeps its datePublished,
        // dateModified, author and headline.
        //
        // Every save restamps updateTime, and that stamp feeds dateModified.
        // A test cannot pin it: saveWeblogEntry sets it AFTER the caller's own
        // setter, which is why the earlier attempt to freeze it here did
        // nothing and this test passed on a laptop while failing in CI. The
        // three saves below therefore legitimately change those bytes whenever
        // they straddle a second boundary. Normalising the modification stamp
        // away is what makes the entry's TYPE the only variable being
        // measured; every other byte of the block still has to match exactly.
        WeblogEntry entry = TestUtils.setupWeblogEntry("stable-entry", weblog, user);
        updateEntry(entry, e -> e.setSearchDescription("Unchanged by the new field."));

        String untypedBody = render("/" + HANDLE + "/entry/stable-entry");
        List<String> untyped = withoutModificationStamp(ldJsonSources(untypedBody));
        assertEquals(1, untyped.size(),
                "an untyped permalink emits exactly one block:\n" + untypedBody);
        assertTrue(untyped.get(0).contains("\"@type\": \"BlogPosting\","),
                "and it must be the hand-written BlogPosting block: " + untyped.get(0));
        assertTrue(untyped.get(0).contains("\"headline\": \"stable-entry\""),
                "including its headline property: " + untyped.get(0));

        // Deliberately cross a second boundary before each re-save. Without
        // it the three renders usually share a dateModified and this test
        // passes even with the normalisation removed, which is precisely how
        // it came to pass locally and fail in CI.
        crossASecondBoundary();
        updateEntry(entry, e -> e.setJsonLdType(JsonLdType.BLOG_POSTING));
        RenderingTestSupport.clearRenderCaches();
        assertEquals(untyped,
                withoutModificationStamp(ldJsonSources(render("/" + HANDLE + "/entry/stable-entry"))),
                "an explicit BLOG_POSTING must render byte for byte like a null type");

        crossASecondBoundary();
        updateEntry(entry, e -> e.setJsonLdType(JsonLdType.TOURIST_ATTRACTION));
        RenderingTestSupport.clearRenderCaches();
        List<String> typed = withoutModificationStamp(
                ldJsonSources(render("/" + HANDLE + "/entry/stable-entry")));
        assertEquals(2, typed.size(), "a travel type adds a block, it does not swap one");
        assertEquals(untyped.get(0), typed.get(0),
                "and the BlogPosting block must survive it byte for byte");
    }

    @Test
    void aTypedEntryChangesNothingOnTheWeblogsCollectionPages() throws Exception {
        // The type describes ONE entry; the front page is still a Blog.
        WeblogEntry entry = TestUtils.setupWeblogEntry("front-attraction", weblog, user);
        updateEntry(entry, e -> e.setJsonLdType(JsonLdType.TOURIST_ATTRACTION));

        String body = render("/" + HANDLE);

        singleLdJson(body, "Blog");
    }

    // ---------------------------------------------------------- other themes

    @Test
    void frontpageThemeEmitsTheSeoBaseline() throws Exception {
        switchTheme("frontpage");

        String body = render("/" + HANDLE);

        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + BASE + "/\">"),
                "the frontpage theme head must carry the canonical link:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"website\">"));
        assertTrue(body.contains("<meta property=\"og:site_name\" content="));
        singleLdJson(body, "Blog");
    }

    @Test
    void searchResultsPageClaimsNoCanonicalUrl() throws Exception {
        TestUtils.setupWeblogEntry("searchable-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "searchable");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertFalse(body.contains("rel=\"canonical\""),
                "search pages have no canonical form:\n" + body);
        assertFalse(body.contains("$model"),
                "no unresolved Velocity references may leak into the page:\n" + body);
        assertNotNull(body);
        assertTrue(ldJsonBlocks(body).isEmpty(),
                "search pages emit no JSON-LD:\n" + body);
    }

    /**
     * Characterisation test, written BEFORE the business/publisher change to
     * {@code #showSeoHead} and expected to pass against the old template: a
     * blog with no business renders the BlogPosting block byte for byte as it
     * did, so declaring businesses cannot disturb the blogs that have none.
     * The two timestamps are normalised because they are the wall clock.
     */
    @Test
    void aBlogWithoutABusinessKeepsItsBlogPostingBlockByteForByte() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("fixture-entry", weblog, user);
        updateEntry(entry, e -> e.setSearchDescription("Fixture description."));

        List<String> sources = ldJsonSources(render("/" + HANDLE + "/entry/fixture-entry"));

        assertEquals(1, sources.size());
        String normalised = sources.get(0)
                .replaceAll("\"datePublished\": \"[^\"]*\"", "\"datePublished\": \"T\"")
                .replaceAll("\"dateModified\": \"[^\"]*\"", "\"dateModified\": \"T\"");
        assertEquals("\n    {\n"
                + "        \"@context\": \"https://schema.org\",\n"
                + "        \"@type\": \"BlogPosting\",\n"
                + "\"datePublished\": \"T\",\"dateModified\": \"T\",\"description\": \"Fixture description.\","
                + "\"author\": { \"@type\": \"Person\", \"name\": \"Test User Screen Name\" },"
                + "        \"mainEntityOfPage\": \"http:\\/\\/localhost:8080\\/roller\\/seorenderblog"
                + "\\/entry\\/fixture-entry\",\n"
                + "        \"headline\": \"fixture-entry\"\n    }\n    ", normalised);
    }

    // ------------------------------------------------------ business / place

    /** Gives the blog a ProfessionalService business, optionally a lodging place. */
    private void giveBlogABusiness(boolean withPlace, String customDomain) throws Exception {
        business = new Business();
        business.setName("Maiia Photography");
        business.setBusinessType(Business.BusinessType.ProfessionalService);
        business.setWebsiteUrl("https://maiia.example");
        TestUtils.weblogger().getBusinessManager().saveBusiness(business);
        TestUtils.endSession(true);

        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setBusiness(TestUtils.weblogger().getBusinessManager().getBusiness(business.getId()));
        if (withPlace) {
            managed.setPlaceType("LodgingBusiness");
            managed.setPlaceLocality("Ponta Delgada");
            managed.setPlaceCountry("PT");
        }
        if (customDomain != null) {
            managed.setCustomDomain(customDomain);
        }
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void aBlogWithABusinessEmitsASecondBlockOnItsHomePage() throws Exception {
        giveBlogABusiness(false, null);

        List<JsonNode> blocks = ldJsonBlocks(render("/" + HANDLE));

        assertEquals(2, blocks.size(), blocks.toString());
        assertEquals("Blog", blocks.get(0).path("@type").asString());
        assertEquals("ProfessionalService", blocks.get(1).path("@type").asString());
        assertEquals("Maiia Photography", blocks.get(1).path("name").asString());
    }

    @Test
    void aPermalinkPublisherIsTheBusiness() throws Exception {
        giveBlogABusiness(false, null);
        TestUtils.setupWeblogEntry("pub-entry", weblog, user);

        List<JsonNode> blocks = ldJsonBlocks(render("/" + HANDLE + "/entry/pub-entry"));

        assertEquals(1, blocks.size(), blocks.toString());
        assertEquals("BlogPosting", blocks.get(0).path("@type").asString());
        assertEquals("Maiia Photography", blocks.get(0).path("publisher").path("name").asString());
        assertFalse(blocks.get(0).path("publisher").has("@context"));
    }

    @Test
    void aBlogWithoutABusinessEmitsNoPublisherAndNoSecondBlock() throws Exception {
        TestUtils.setupWeblogEntry("plain-entry", weblog, user);

        singleLdJson(render("/" + HANDLE), "Blog");
        assertFalse(singleLdJson(render("/" + HANDLE + "/entry/plain-entry"), "BlogPosting")
                .has("publisher"));
    }

    @Test
    void aPlaceOnACustomDomainUsesThatDomainAsItsUrl() throws Exception {
        giveBlogABusiness(true, "guide.example.com");

        List<JsonNode> blocks = ldJsonBlocks(render("/" + HANDLE));

        assertEquals(2, blocks.size(), blocks.toString());
        JsonNode place = blocks.get(1);
        assertEquals("LodgingBusiness", place.path("@type").asString());
        assertEquals("guide.example.com",
                java.net.URI.create(place.path("url").asString()).getHost());
        assertEquals("Maiia Photography", place.path("parentOrganization").path("name").asString());
    }
}
