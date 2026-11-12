/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.rendering.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a post page says about the post in the three article themes: who wrote
 * it and when, when it was last changed, and where it sits in the site (spec
 * {@code docs/superpowers/specs/2026-11-12-patch-0-9-1-design.md}, section C).
 * Rendered through the real PageServlet, because Velocity prints a wrong
 * reference as text instead of failing.
 */
class EntryMetaRenderingTest {

    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");
    private static final long DAY = 86_400_000L;
    private static final String AUTHOR = "By Test User Screen Name";
    private static final String BODY = "BODYMARK of the post";

    private static final Pattern ARTICLE = Pattern.compile("(?s)<article.*?</article>");
    private static final Pattern TIME = Pattern.compile("<time[^>]*datetime=\"([^\"]*)\"[^>]*>([^<]*)</time>");
    private static final Pattern UPDATED = Pattern.compile(
            "<time class=\"entry-updated\" datetime=\"([^\"]*)\">([^<]*)</time>");
    private static final Pattern LD_JSON = Pattern.compile(
            "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String handle;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "entrymeta" + suffix;
        user = TestUtils.setupUser("entrymetauser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager().removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A post published {@code daysAgo} days ago. Saving stamps the update time
     * with the present moment and a test cannot prevent it, so a post from
     * days ago is one updated on a later day and a post from now is not.
     */
    private WeblogEntry post(String anchor, int daysAgo, String storedTitle) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis() - DAY * daysAgo));
        managed.setTitle(storedTitle);
        managed.setText(BODY);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return mgr.getWeblogEntry(entry.getId());
    }

    private void theme(String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setLocale("en_US");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        String body = response.getContentAsString();
        for (String leak : List.of("$model.", "$utils.", "$text.", "$url.", "$entry", "$seo", "$em",
                "$config.", "#showEntry", "entryMeta.")) {
            assertFalse(body.contains(leak), "'" + leak + "' leaked into the page:\n" + body);
        }
        return body;
    }

    private String renderPost(String anchor) throws Exception {
        return render("/" + handle + "/entry/" + anchor);
    }

    private static String article(String body) {
        Matcher m = ARTICLE.matcher(body);
        assertTrue(m.find(), "no article in:\n" + body);
        return m.group();
    }

    private static int count(String haystack, String needle) {
        return StringUtils.countMatches(haystack, needle);
    }

    private String day(Date date, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(weblog.getTimeZoneInstance());
        return format.format(date);
    }

    private static List<JsonNode> breadcrumbs(String body) {
        List<JsonNode> found = new ArrayList<>();
        Matcher m = LD_JSON.matcher(body);
        while (m.find()) {
            JsonNode node = MAPPER.readTree(m.group(1));
            if ("BreadcrumbList".equals(node.path("@type").asString())) {
                found.add(node);
            }
        }
        return found;
    }

    private void inEveryTheme(ThemeCheck check) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                theme(theme);
                check.run(theme);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }
        assertTrue(failures.isEmpty(), "themes failed:\n  " + String.join("\n  ", failures));
    }

    private interface ThemeCheck {
        void run(String theme) throws Exception;
    }

    // ------------------------------------------------------------------ byline

    /** Criterion 10. */
    @Test
    void aPostNamesItsAuthorAndItsDayOnceEachBetweenTheTitleAndTheText() throws Exception {
        WeblogEntry entry = post("by-whom", 0, "By whom");
        String pubDay = day(entry.getPubTime(), "yyyy-MM-dd");

        inEveryTheme(theme -> {
            String article = article(renderPost("by-whom"));

            assertEquals(1, count(article, AUTHOR), article);
            assertEquals(1, count(article, "class=\"entry-author\""), article);
            List<String> days = new ArrayList<>();
            Matcher times = TIME.matcher(article);
            int dateAt = -1;
            while (times.find()) {
                days.add(times.group(1));
                dateAt = times.start();
            }
            assertEquals(List.of(pubDay), days, "one publication date and no other: " + article);
            assertTrue(article.contains(">" + day(entry.getPubTime(), "MMMM d, yyyy") + "</time>"), article);

            int title = article.indexOf("</h1>");
            int text = article.indexOf(BODY);
            assertTrue(title > 0 && text > title, article);
            assertTrue(article.indexOf(AUTHOR) > title && article.indexOf(AUTHOR) < text,
                    "the author belongs between the title and the text: " + article);
            assertTrue(dateAt > title && dateAt < text,
                    "the date belongs between the title and the text: " + article);
        });
    }

    // ----------------------------------------------------------------- updated

    /** Criterion 11. */
    @Test
    void aPostChangedOnALaterDaySaysWhen() throws Exception {
        WeblogEntry entry = post("revised", 30, "Revised");
        String updatedDay = day(entry.getUpdateTime(), "yyyy-MM-dd");
        assertFalse(updatedDay.equals(day(entry.getPubTime(), "yyyy-MM-dd")), "fixture: a later day");

        inEveryTheme(theme -> {
            String article = article(renderPost("revised"));

            Matcher updated = UPDATED.matcher(article);
            assertTrue(updated.find(), "no updated line in: " + article);
            assertEquals(updatedDay, updated.group(1));
            assertEquals("Updated " + day(entry.getUpdateTime(), "MMMM d, yyyy"), updated.group(2));
            assertFalse(updated.find(), "one updated line, not two: " + article);
            assertEquals(1, count(article, "datetime=\"" + day(entry.getPubTime(), "yyyy-MM-dd") + "\""),
                    "the publication date is still there, once: " + article);
            assertTrue(article.indexOf("entry-updated") < article.indexOf(BODY), article);
        });
    }

    /** Criterion 11, the other half. */
    @Test
    void aPostLastSavedOnTheDayItWasPublishedHasNoUpdatedLine() throws Exception {
        WeblogEntry entry = post("untouched", 0, "Untouched");
        // Only wrong when the save above straddled the blog's midnight.
        assertEquals(day(entry.getPubTime(), "yyyy-MM-dd"), day(entry.getUpdateTime(), "yyyy-MM-dd"),
                "fixture: published and saved on one day");

        inEveryTheme(theme -> {
            String body = renderPost("untouched");
            assertFalse(body.contains("entry-updated"), body);
            assertFalse(body.contains("Updated "), body);
        });
    }

    // ------------------------------------------------------------- breadcrumbs

    /** Criterion 12. */
    @Test
    void aPostPageCarriesOneBreadcrumbTrailFromTheSiteToThePost() throws Exception {
        WeblogEntry entry = post("trail", 0, "The trail");
        String category = entry.getCategory().getName();
        String site = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String siteName = StringUtils.defaultIfBlank(WebloggerRuntimeConfig.getProperty("site.name"), "Front page");

        inEveryTheme(theme -> {
            List<JsonNode> trails = breadcrumbs(renderPost("trail"));
            assertEquals(1, trails.size(), "exactly one BreadcrumbList");
            JsonNode trail = trails.get(0);
            assertEquals("https://schema.org", trail.path("@context").asString());
            JsonNode items = trail.path("itemListElement");
            assertEquals(4, items.size(), trail.toString());
            for (int i = 0; i < items.size(); i++) {
                assertEquals("ListItem", items.get(i).path("@type").asString());
                assertEquals(i + 1, items.get(i).path("position").asInt());
                assertTrue(items.get(i).path("item").asString().startsWith("http"), "absolute: " + trail);
            }
            assertEquals(siteName, items.get(0).path("name").asString());
            assertEquals(site + "/", items.get(0).path("item").asString());
            assertEquals(weblog.getName(), items.get(1).path("name").asString());
            assertEquals(site + "/" + handle + "/", items.get(1).path("item").asString());
            assertEquals(category, items.get(2).path("name").asString());
            assertTrue(items.get(2).path("item").asString().startsWith(site + "/" + handle + "/category/"),
                    trail.toString());
            assertEquals("The trail", items.get(3).path("name").asString());
            assertEquals(site + "/" + handle + "/entry/trail", items.get(3).path("item").asString());
        });
    }

    /** Criterion 12: titles are stored HTML-escaped, and the trail names the post in plain text. */
    @Test
    void aTitleWithAQuoteOrAClosingScriptTagStaysInsideItsString() throws Exception {
        post("hostile", 0, "Tom &amp; &quot;Jerry&quot; &lt;/script&gt;&lt;b&gt;");

        inEveryTheme(theme -> {
            String body = renderPost("hostile");
            // a block cut short by the title would not parse, or would not be found at all
            List<JsonNode> trails = breadcrumbs(body);
            assertEquals(1, trails.size());
            JsonNode items = trails.get(0).path("itemListElement");
            assertEquals("Tom & \"Jerry\" </script><b>", items.get(items.size() - 1).path("name").asString());
            assertFalse(body.contains("</script><b>"), "the title closed a script element:\n" + body);
        });
    }

    // --------------------------------------------------------------- elsewhere

    /** Criterion 13. */
    @Test
    void listPagesAndStaticPagesCarryNoneOfIt() throws Exception {
        post("listed", 30, "Listed");
        WeblogPageManager pages = TestUtils.weblogger().getWeblogPageManager();
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug("about-us");
        page.setTitle("About us");
        page.setContent("Body of the page");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        pages.savePage(page);
        TestUtils.endSession(true);

        inEveryTheme(theme -> {
            String list = render("/" + handle);
            assertTrue(list.contains("Listed"), "fixture: the post is on the list page:\n" + list);
            String staticPage = render("/" + handle + "/about-us");
            assertTrue(staticPage.contains("Body of the page"), "fixture: the static page rendered");
            for (String body : List.of(list, staticPage)) {
                assertTrue(breadcrumbs(body).isEmpty(), body);
                assertFalse(body.contains("entry-updated"), body);
                assertFalse(body.contains("entry-author"), body);
                assertFalse(body.contains(AUTHOR), body);
            }
        });
    }
}
