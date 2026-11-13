package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.StaticThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.StrictReferences;
import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import jakarta.servlet.DispatcherType;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.SearchSpec;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.WeblogEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchuser");
        weblog = TestUtils.setupWeblog("searchblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        tearDownExtras();
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void searchPageRendersWithNoResults() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        String body = response.getContentAsString();
        assertTrue(body.contains("id=\"searchAgain\""),
                "search results template must render the search-again form:\n" + body);
        // a zero-hit search says so once, in the theme's empty state: no
        // "0 entries match" line above it (macro.weblog.searchhits)
        assertFalse(body.contains("class=\"search-hits\""),
                "a zero-hit search prints no hit line:\n" + body);
        assertTrue(body.contains("Test Weblog"),
                "weblog name must appear on the search page:\n" + body);
    }

    /**
     * A search that matched nothing used to render the search-again form and
     * then an empty entries div -- no sentence anywhere saying so. The
     * default fixture theme is journal, so this covers qj-*; the other two
     * themes' copies are pinned by the source scan below (their zero-hit path
     * is the same three lines of template with a different class prefix).
     */
    @Test
    void aSearchThatMatchesNothingSaysSo() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("qj-search-empty"),
                "a zero-hit search must carry the theme's no-results block:\n" + body);
        assertTrue(body.contains(NO_RESULTS_SENTENCE),
                "a zero-hit search must say so in words:\n" + body);
    }

    /**
     * {@code #showWeblogSearchAgainForm}'s category select compares against
     * {@code $model.weblogCategory.name} with no null guard, unlike its twin
     * in {@code #showWeblogSearchForm} a few lines above (the one the initial
     * search box uses) which already checks {@code $model.weblogCategory &&}
     * first before comparing. This app's Velocity engine runs with strict
     * references off (see CLAUDE.md's "Velocity is lenient"), so a chained
     * property read on a null intermediate degrades silently rather than
     * throwing -- confirmed by {@link #searchAgainFormRendersCleanlyWithNoCategoryChosen}
     * below, which is a characterisation test: it passes unmodified before
     * and after this fix, because the lenient engine already tolerates the
     * unguarded form. This scan is the one that actually discriminates: it
     * pins the source matching the established null-safe idiom, so a
     * template reference to a deleted member or a future strict-mode change
     * cannot silently reintroduce the gap this mirrors closed.
     */
    @Test
    void searchAgainFormCategorySelectMirrorsTheNullSafeGuardTheInitialFormUses() throws Exception {
        Path template = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");
        String source = Files.readString(template, StandardCharsets.UTF_8);

        // showWeblogSearchForm's own <option> (a few lines above, line ~1158)
        // already reads "#if($model.weblogCategory && $cat.name == ...)" --
        // this asserts on the DISTINCT unguarded prefix so a check against
        // "the guarded string exists somewhere in the file" cannot pass
        // trivially against that sibling occurrence.
        assertFalse(source.contains(
                        "#if($cat.name == $model.weblogCategory.name)selected=\"selected\"#end"),
                "showWeblogSearchAgainForm's category <option> must guard the comparison the same way "
                        + "showWeblogSearchForm's does a few lines above it, not compare "
                        + "$model.weblogCategory.name unguarded:\n" + source);
    }

    /**
     * Characterisation test: it passes unmodified both before and after the
     * null-guard fix above, because this app's lenient Velocity config (see
     * CLAUDE.md's "Velocity is lenient") already tolerates a chained property
     * read on a null intermediate by degrading silently rather than throwing.
     * It exists to pin the user-visible behaviour the guard protects, and to
     * make that lenience explicit rather than assumed: a search with no
     * {@code cat} parameter must render every category option unselected,
     * with no unresolved reference leaking into the page as literal text.
     */
    @Test
    void searchAgainFormRendersCleanlyWithNoCategoryChosen() throws Exception {
        TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Travel");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertFalse(body.contains("selected=\"selected\""),
                "no category was chosen, so no <option> may render pre-selected:\n" + body);
        assertFalse(body.contains("$model.weblogCategory"),
                "an unresolved reference must never print literally into the page:\n" + body);
    }

    /**
     * A search page is crawlable at every {@code ?q=} permutation, which is
     * an unbounded set of thin near-duplicate pages. {@code #showSeoHead}
     * now emits robots noindex whenever the model is a search result.
     */
    @Test
    void theSearchPageIsNoindex() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertTrue(response.getContentAsString()
                        .contains("<meta name=\"robots\" content=\"noindex\">"),
                "search result permutations must not be indexed:\n"
                        + response.getContentAsString());
    }

    // ------------------------------------------------- searchresults.vm scan

    private static final String NO_RESULTS_SENTENCE =
            "No entries matched that search.";
    private static final String SEARCH_ERROR_SENTENCE =
            "Search is temporarily unavailable.";

    /**
     * {@code SearchResultsModel.getErrorMessage()} carries a
     * {@code WebloggerException}'s message (Lucene down, a malformed query)
     * and was rendered by <em>nothing</em>: a broken search index looked
     * exactly like a search that found nothing. Every theme must now say so
     * -- with a fixed sentence, never the exception text, which is internal
     * detail on a public page for the same reason the Velocity error
     * templates may not print it (see {@code VelocityErrorTemplateTest}).
     */
    @Test
    void everyThemeReportsBothTheEmptyAndTheBrokenSearch() throws Exception {
        for (String theme : new String[]{"journal", "portfolio", "travel", "frontpage"}) {
            Path template = Path.of("src/main/webapp/themes/" + theme
                    + ("frontpage".equals(theme) ? "/weblog.vm" : "/searchresults.vm"));
            assertTrue(Files.exists(template), template + " must exist");
            String source = Files.readString(template, StandardCharsets.UTF_8);

            assertTrue(source.contains("frontpage".equals(theme) ? "No posts matched that search." : NO_RESULTS_SENTENCE),
                    theme + " must tell a reader when nothing matched:\n" + source);
            assertTrue(source.contains("$utils.isNotEmpty($model.errorMessage)"),
                    theme + " must branch on the search error:\n" + source);
            assertTrue(source.contains(SEARCH_ERROR_SENTENCE),
                    theme + " must report a broken search in fixed words:\n" + source);
            assertFalse(source.contains(">$model.errorMessage")
                            || source.contains(" $model.errorMessage<")
                            || source.contains("escapeHTML($model.errorMessage)"),
                    theme + " must never render the raw error message:\n" + source);
        }
    }

    @Test
    void unknownWeblogSearchIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/nosuchblog");
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        // A missing weblog is a 404, matching every sibling rendering
        // servlet (WebContainerConfig renders the 404 page body for this
        // status; a 400 here used to disagree with that body).
        assertEquals(404, response.getStatus());
    }

    // ------------------------------------------------------ results macro

    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");
    private static final String FRONTPAGE_HANDLE_PROP = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED_PROP = "site.frontpage.weblog.aggregated";

    private boolean siteSpaceCreated;
    private final List<Weblog> extraBlogs = new ArrayList<>();
    private final List<User> extraUsers = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    private void tearDownExtras() throws Exception {
        if (siteSpaceCreated) {
            var weblogs = TestUtils.weblogger().getWeblogManager();
            Weblog site = weblogs.getSiteSpace();
            if (site != null) {
                weblogs.removeWeblog(site);
            }
            TestUtils.endSession(true);
        }
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.forEach((name, value) -> config.get(name).setValue(value));
        if (!originalProperties.isEmpty()) {
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
        }
        for (Weblog extra : extraBlogs) {
            TestUtils.teardownWeblog(extra.getId());
        }
        for (User extra : extraUsers) {
            TestUtils.teardownUser(extra.getUserName());
        }
        TestUtils.endSession(true);
    }

    private Weblog newBlog(String handle, String name, String theme) throws Exception {
        User owner = TestUtils.setupUser(handle + "user");
        extraUsers.add(owner);
        Weblog blog = TestUtils.setupWeblog(handle, owner);
        extraBlogs.add(blog);
        Weblog managed = TestUtils.getManagedWebsite(blog);
        managed.setName(name);
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        return blog;
    }

    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void useTheme(String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /**
     * Publishes and indexes a post; the caller waits with awaitIndexed.
     * Reindexed (delete, then add), not added: newBlog's rename schedules a
     * rebuild of that blog's documents on another thread, and a rebuild that
     * ran between this save and a plain add left the post in the index twice
     * ("3 articles across 2 blogs" for two posts). Writes hold one lock, so
     * either order now leaves one document.
     */
    private WeblogEntry post(Weblog blog, String anchor, String title, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(user));
        entry.setTitle(title);
        entry.setText(text);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.weblogger().flush();
        TestUtils.weblogger().getIndexManager()
                .addEntryReIndexOperation(TestUtils.getManagedWeblogEntry(entry));
        TestUtils.endSession(true);
        return entry;
    }

    private static void awaitIndexed(Weblog blog, String word, int count) throws Exception {
        IndexManager index = TestUtils.weblogger().getIndexManager();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        int found = 0;
        while (System.nanoTime() < deadline) {
            found = index.find(new SearchSpec(word, SearchSpec.Scope.BLOG, blog.getHandle(), null, 0, 10,
                    "en_US"), TestUtils.weblogger().getUrlStrategy()).total();
            if (found >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(blog.getHandle() + ": expected " + count + " indexed for " + word
                + ", found " + found);
    }

    private String search(String handle, String query, String... more) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + handle);
        request.setParameter("q", query);
        for (int i = 0; i < more.length; i += 2) {
            request.setParameter(more[i], more[i + 1]);
        }
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertFalse(body.contains("$model.") || body.contains("$hit") || body.contains("$text."),
                "a Velocity reference leaked into the page:\n" + body);
        return body;
    }

    private interface ThemeCheck {
        void run(String theme) throws Exception;
    }

    private void inEveryTheme(ThemeCheck check) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                useTheme(theme);
                check.run(theme);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }
        assertTrue(failures.isEmpty(), "themes failed:\n  " + String.join("\n  ", failures));
    }

    private static void assertInOrder(String body, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = body.indexOf(needle, at + 1);
            assertTrue(next > at, "expected '" + needle + "' after position " + at + " in:\n" + body);
            at = next;
        }
    }

    @Test
    void ownHitsRenderInScoreOrderBeforeOtherBlogs() throws Exception {
        Weblog other = newBlog("searchother", "Other Notes", "journal");
        post(weblog, "own-body", "Plain notes", "a body that mentions zorbix once");
        post(weblog, "own-title", "Zorbix in the title", "nothing else");
        post(other, "other-zorbix", "Zorbix elsewhere", "zorbix");
        awaitIndexed(weblog, "zorbix", 2);
        awaitIndexed(other, "zorbix", 1);

        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            assertInOrder(body, "Zorbix in the title", "Plain notes",
                    "class=\"search-group search-group-other\"", "Zorbix elsewhere");
            assertTrue(body.contains("class=\"search-results\""), body);
            assertTrue(body.contains("From Test Weblog · 2 articles"), body);
            assertTrue(body.contains("From other blogs on the site"), body);
        });
    }

    @Test
    void otherBlogHitLinksAbsolutePermalinkAndNamesItsBlog() throws Exception {
        Weblog other = newBlog("searchother", "Harbor & Cove", "journal");
        post(weblog, "own-one", "Own one", "zorbix");
        post(other, "other-one", "Other one", "zorbix");
        awaitIndexed(weblog, "zorbix", 1);
        awaitIndexed(other, "zorbix", 1);

        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            java.util.regex.Matcher link = java.util.regex.Pattern.compile(
                    "<h3 class=\"search-title\"><a href=\"(https?://[^\"]*/searchother/entry/other-one)\"")
                    .matcher(body);
            assertTrue(link.find(), "an other-blog hit links the absolute permalink:\n" + body);
            assertTrue(body.contains("<a class=\"search-blog\" href=\"http"), body);
            assertTrue(body.contains(">Harbor &amp; Cove</a>"), body);
            assertFalse(body.contains("href=\"/searchblog/entry/other-one"), body);
            assertTrue(body.contains("/entry/own-one\""), body);
        });
    }

    @Test
    void snippetMarkupIsEmittedAndScriptInContentIsInert() throws Exception {
        post(weblog, "inert", "Inert", "<script>alert(1)</script> Fish & chips zorbix remodel");
        awaitIndexed(weblog, "zorbix", 1);

        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            // markup is stripped before indexing, and what remains is escaped once
            assertTrue(body.contains("Fish &amp; chips <mark>zorbix</mark>"), body);
            assertFalse(body.contains("&amp;amp;"), body);
            assertFalse(body.contains("<script>alert"), body);
        });
    }

    @Test
    void showAllLinkOnlyWhenMoreThanEight() throws Exception {
        for (int i = 0; i < 8; i++) {
            post(weblog, "many-" + i, "Many " + i, "zorbix");
        }
        awaitIndexed(weblog, "zorbix", 8);
        inEveryTheme(theme -> assertFalse(search("searchblog", "zorbix").contains("class=\"search-all\""),
                theme + ": eight is not more than eight"));

        post(weblog, "many-8", "Many 8", "zorbix");
        awaitIndexed(weblog, "zorbix", 9);
        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            assertTrue(body.contains("class=\"search-all\""), body);
            assertTrue(body.contains("Show all 9 from this blog"), body);
            assertTrue(body.contains("scope=blog"), body);
        });
    }

    @Test
    void noOtherBlogsSectionWhenNoOtherBlogHasAMatch() throws Exception {
        post(weblog, "alone", "Alone", "zorbix");
        awaitIndexed(weblog, "zorbix", 1);
        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            assertFalse(body.contains("search-group-other\""), body);
            assertFalse(body.contains("class=\"search-site\""), body);
            assertFalse(body.contains("class=\"search-all\""), body);
        });
    }

    @Test
    void ownSectionSaysNothingMatchedWhenOnlyOthersDo() throws Exception {
        Weblog other = newBlog("searchother", "Other Notes", "journal");
        siteSpaceCreated = true;
        TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        post(other, "only-other", "Only other", "zorbix");
        awaitIndexed(other, "zorbix", 1);
        inEveryTheme(theme -> {
            String body = search("searchblog", "zorbix");
            assertTrue(body.contains("Nothing in Test Weblog matched."), body);
            assertTrue(body.contains("class=\"search-group search-group-other\""), body);
            assertTrue(body.contains("class=\"search-site\""), body);
            assertFalse(body.contains(NO_RESULTS_SENTENCE), body);
        });
    }

    @Test
    void fallbackLineShownOnlyOnFallback() throws Exception {
        post(weblog, "fallback", "Fallback", "zorbix and nothing more");
        awaitIndexed(weblog, "zorbix", 1);
        inEveryTheme(theme -> {
            assertFalse(search("searchblog", "zorbix").contains("class=\"search-fallback\""));
            String body = search("searchblog", "zorbix absentword");
            assertTrue(body.contains("class=\"search-fallback\""), body);
            assertTrue(body.contains("Nothing matched all of those words."), body);
        });
    }

    @Test
    void resultsPageIsNoindex() throws Exception {
        post(weblog, "indexed", "Indexed", "zorbix");
        awaitIndexed(weblog, "zorbix", 1);
        inEveryTheme(theme -> assertTrue(search("searchblog", "zorbix")
                .contains("<meta name=\"robots\" content=\"noindex\">")));
    }

    @Test
    void siteViewNamesEachBlogAndShowsMoreFromBlog() throws Exception {
        Weblog site = newBlog("searchsite", "The Site", "frontpage");
        Weblog second = newBlog("searchsecond", "Second Blog", "journal");
        setProperty(FRONTPAGE_HANDLE_PROP, "searchsite");
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");
        post(weblog, "site-a1", "Zorbix A one", "zorbix zorbix zorbix");
        post(weblog, "site-a2", "A two", "zorbix");
        post(second, "site-b1", "Zorbix B one", "zorbix");
        awaitIndexed(weblog, "zorbix", 2);
        awaitIndexed(second, "zorbix", 1);
        RenderingTestSupport.clearRenderCaches();

        String body = search("searchsite", "zorbix");

        assertTrue(body.contains("3 articles across 2 blogs"), body);
        assertTrue(body.contains(">Test Weblog</a>"), body);
        assertTrue(body.contains(">Second Blog</a>"), body);
        assertTrue(body.contains("1 more from this blog"), body);
        assertTrue(body.contains("class=\"search-results\""), body);
        assertFalse(body.contains("class=\"search-all\""), body);
        assertTrue(site.getHandle().equals("searchsite"));
    }

    @Test
    void emptyAndErrorStatesStillRender() throws Exception {
        inEveryTheme(theme -> {
            String body = search("searchblog", "zzznope");
            assertTrue(body.contains(NO_RESULTS_SENTENCE), body);
            assertFalse(body.contains("class=\"search-results\"") && body.contains("search-hit\""), body);
        });
        Weblog site = newBlog("searchsite", "The Site", "frontpage");
        setProperty(FRONTPAGE_HANDLE_PROP, "searchsite");
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");
        RenderingTestSupport.clearRenderCaches();
        assertTrue(search(site.getHandle(), "zzznope").contains("No posts matched that search."));
    }

    // ------------------------------------- site-wide search: the site space at /search

    private static final String SITE_NAME = "Harbour & Hills";
    private static final String SITE_NAME_HTML = "Harbour &amp; Hills";

    private void givenSiteSpace(String theme) throws Exception {
        siteSpaceCreated = true;
        var weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog site = weblogs.getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName()));
        site.setEditorTheme(theme);
        weblogs.saveWeblog(site);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        if (!originalProperties.containsKey("site.name")) {
            setProperty("site.name", SITE_NAME);
        }
        RenderingTestSupport.clearRenderCaches();
    }

    /** {@code /search} as WeblogRequestMapper forwards it: the site space, by FORWARD. */
    private MockHttpServletResponse siteSearch(String query, String... more) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + Weblog.SITE_SPACE_HANDLE);
        request.setDispatcherType(DispatcherType.FORWARD);
        if (query != null) {
            request.setParameter("q", query);
        }
        for (int i = 0; i < more.length; i += 2) {
            request.setParameter(more[i], more[i + 1]);
        }
        return RenderingTestSupport.execute(RenderingTestSupport.searchServlet(), request);
    }

    private String siteSearchBody(String query, String... more) throws Exception {
        MockHttpServletResponse response = siteSearch(query, more);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), body);
        assertFalse(body.contains("$model.") || body.contains("$hit") || body.contains("$text.")
                        || body.contains("$url.") || body.contains("$config.") || body.contains("$utils."),
                "a Velocity reference leaked into the page:\n" + body);
        return body;
    }

    private static int count(String body, String needle) {
        int found = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    /**
     * The site's search renders the shared {@code templates/weblog/site-search.vm}
     * whatever theme the site space runs (no bundled theme ships its own), with
     * the site name as the one h1 and one ranked list naming each hit's blog.
     */
    @Test
    void siteSpaceSearchRendersTheSharedTemplateWithResultsOfSeveralBlogs() throws Exception {
        Weblog other = newBlog("searchother", "Other Notes", "journal");
        post(weblog, "site-own", "Zorbix at home", "zorbix");
        post(other, "site-other", "Zorbix abroad", "zorbix");
        awaitIndexed(weblog, "zorbix", 1);
        awaitIndexed(other, "zorbix", 1);

        for (String theme : List.of("frontpage", "journal", "travel", "portfolio")) {
            givenSiteSpace(theme);
            String body = siteSearchBody("zorbix");

            assertTrue(body.contains("<body class=\"site-search-page\">"), theme + ": the shared template:\n" + body);
            assertEquals(1, count(body, "<h1"), theme + ": one h1:\n" + body);
            assertTrue(body.contains(SITE_NAME_HTML + "</a></h1>"), theme + ": the h1 is the site name:\n" + body);
            assertTrue(body.contains("class=\"search-results\""), body);
            assertTrue(body.contains("2 articles across 2 blogs"), body);
            assertEquals(2, count(body, "<a class=\"search-blog\""), theme + ": each hit names its blog:\n" + body);
            assertTrue(body.contains(">Test Weblog</a>"), body);
            assertTrue(body.contains(">Other Notes</a>"), body);
            assertTrue(body.contains("Zorbix at home") && body.contains("Zorbix abroad"), body);
        }
    }

    /**
     * Noindex like every results page, and the query is reader input on an
     * anonymous page: it comes back escaped in the input and in the title.
     */
    @Test
    void siteSpaceSearchPageIsNoindexAndHasTheSearchFormPrefilled() throws Exception {
        givenSiteSpace("frontpage");

        String body = siteSearchBody("say \"hi\" <b>");

        assertTrue(body.contains("<meta name=\"robots\" content=\"noindex\">"), body);
        assertTrue(java.util.regex.Pattern.compile(
                "<form class=\"site-search\" role=\"search\" method=\"get\" action=\"[^\"]*/search\">")
                .matcher(body).find(), "the form posts to the site's search address:\n" + body);
        assertTrue(body.contains("<input type=\"search\" name=\"q\" maxlength=\"255\" required"
                + " value=\"say &quot;hi&quot; &lt;b&gt;\""), body);
        assertTrue(body.contains("<title>Search: say &quot;hi&quot; &lt;b&gt; — " + SITE_NAME_HTML + "</title>"),
                body);
        assertFalse(body.contains("\"hi\" <b>"), "the raw query must not reach the page:\n" + body);
        assertFalse(body.contains("site-space/search"), "the handle is never the address:\n" + body);
    }

    @Test
    void siteSpaceSearchWithNoQueryShowsTheFormAndNoResults() throws Exception {
        givenSiteSpace("frontpage");

        String body = siteSearchBody(null);

        assertTrue(body.contains("<form class=\"site-search\""), body);
        assertTrue(body.contains("value=\"\""), "an empty input:\n" + body);
        assertTrue(body.contains("<title>Search — " + SITE_NAME_HTML + "</title>"), body);
        assertFalse(body.contains("class=\"search-results\""), body);
        assertFalse(body.contains("class=\"site-search-empty\""), "no query is not an empty result:\n" + body);
        assertFalse(body.contains("class=\"site-search-error\""), body);
    }

    @Test
    void siteSpaceSearchEmptyAndFallbackStatesRender() throws Exception {
        post(weblog, "site-fallback", "Fallback", "zorbix and nothing more");
        awaitIndexed(weblog, "zorbix", 1);
        givenSiteSpace("frontpage");

        String empty = siteSearchBody("zzznope");
        assertTrue(empty.contains("<p class=\"site-search-empty\">No posts matched that search."), empty);
        assertFalse(empty.contains("class=\"search-results\""), empty);

        String fallback = siteSearchBody("zorbix absentword");
        assertTrue(fallback.contains("class=\"search-fallback\""), fallback);
        assertFalse(fallback.contains("class=\"site-search-empty\""), fallback);
    }

    /** The site view is paged by ten, and its pager links stay on the site's address. */
    @Test
    void siteSpaceSearchPagesByTenAtTheSiteAddress() throws Exception {
        for (int i = 0; i < 11; i++) {
            post(weblog, "site-many-" + i, "Many " + i, "zorbix");
        }
        awaitIndexed(weblog, "zorbix", 11);
        givenSiteSpace("frontpage");

        String body = siteSearchBody("zorbix");
        assertEquals(10, count(body, "<li class=\"search-hit\">"), body);
        assertTrue(java.util.regex.Pattern.compile("rel=\"next\" href=\"[^\"]*/search\\?[^\"]*page=1")
                .matcher(body).find(), "next goes to the site's search, page 1:\n" + body);
        assertFalse(body.contains("site-space/search"), body);

        String second = siteSearchBody("zorbix", "page", "1");
        assertEquals(1, count(second, "<li class=\"search-hit\">"), second);
        assertTrue(second.contains("rel=\"prev\""), second);
    }

    /** Like the page servlet, the search servlet serves the site space to the mapper's forward only. */
    @Test
    void theSiteSpacesSearchIsNotServedByItsHandle() throws Exception {
        givenSiteSpace("frontpage");
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + Weblog.SITE_SPACE_HANDLE);
        request.setParameter("q", "zorbix");

        assertEquals(404, RenderingTestSupport.execute(RenderingTestSupport.searchServlet(), request).getStatus());
    }

    /**
     * The template choice, as PageServlet's for the discovery page: the site
     * space's theme's own {@code site-search} template when it has one (a
     * forked theme), else the shared one, also when the lookup fails. A blog
     * keeps its theme's search template, never site-search.
     */
    @Test
    void aSiteSpaceThemeWithItsOwnSiteSearchTemplateIsUsed() throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        ThemeManager themes = mock(ThemeManager.class);
        WeblogTheme theme = mock(WeblogTheme.class);
        when(themes.getTheme(any(Weblog.class))).thenReturn(theme);

        ThemeTemplate own = mock(ThemeTemplate.class);
        when(theme.getTemplateByName("site-search")).thenReturn(own);
        assertSame(own, SearchServlet.selectTemplate(site, themes));

        when(theme.getTemplateByName("site-search")).thenReturn(null);
        ThemeTemplate shared = SearchServlet.selectTemplate(site, themes);
        assertInstanceOf(StaticThemeTemplate.class, shared);
        assertEquals("templates/weblog/site-search.vm", shared.getId());

        when(theme.getTemplateByName("site-search")).thenThrow(new WebloggerException("theme is broken"));
        assertEquals("templates/weblog/site-search.vm", SearchServlet.selectTemplate(site, themes).getId());

        ThemeTemplate blogSearch = mock(ThemeTemplate.class);
        when(theme.getTemplateByAction(ThemeTemplate.ComponentType.SEARCH)).thenReturn(blogSearch);
        assertSame(blogSearch, SearchServlet.selectTemplate(weblog, themes));
    }

    /**
     * What the shared site-search template, and the macros it calls, leave
     * unresolved, each with its reason (RenderedTemplateCoverageTest names
     * this class for {@code templates/weblog/site-search.vm}). Cleared and
     * asserted inside the one test, so the theme renders above do not count.
     */
    private static final java.util.Set<String> SITE_SEARCH_REFERENCES = java.util.Set.of(
            "weblog.vm: $model.canonicalUrl", // null on every results page (PageModel: search results have no canonical); assigned to $seoCanonical
            "weblog.vm: $seoCanonical", // that #set of a null canonical; tested with #if
            "weblog.vm: seoCanonical", // the same #set, as the recorder names its target
            "weblog.vm: $model.page", // null when the view is not a static page; tested with #if
            "weblog.vm: $analytics.websiteId()", // null unless the site space is registered with Umami; tested with $utils.isNotEmpty
            "weblog.vm: $trackerSiteId", // #set of a null website id; tested with $utils.isNotEmpty
            "weblog.vm: trackerSiteId", // the same #set, as the recorder names its target
            "weblog.vm: $colophonFacts.copyrightText", // null unless copyright text is set; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.privacyUrl", // null unless a privacy URL is configured; tested with $utils.isNotEmpty
            "weblog.vm: $pager.prevLink", // null on the first page of results; tested with #if in the next/prev macro
            "weblog.vm: $pager.nextLink", // null on the last page of results; tested with #if in the next/prev macro
            "weblog.vm: $srEntry.featuredImage" // null for a hit with no featured image; tested with #if in the hit macro
    );

    @Test
    void siteSearchLeavesOnlyTheExpectedReferencesUnresolved() throws Exception {
        Weblog other = newBlog("searchother", "Other Notes", "journal");
        post(weblog, "strict-own", "Zorbix at home", "zorbix");
        post(other, "strict-other", "Zorbix abroad", "zorbix");
        awaitIndexed(weblog, "zorbix", 1);
        awaitIndexed(other, "zorbix", 1);
        givenSiteSpace("frontpage");

        InvalidReferenceRecorder.clear();
        siteSearchBody("zorbix");
        siteSearchBody("zorbix absentword");
        siteSearchBody("zzznope");
        siteSearchBody(null);

        StrictReferences.assertRecordedExactly("site-search.vm", SITE_SEARCH_REFERENCES);
    }
}
