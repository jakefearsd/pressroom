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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * What a post page offers after the article in the three article themes: the
 * share row, "Nearby" and "Keep reading", and the eager hero (spec
 * {@code docs/superpowers/specs/2026-11-11-post-page-discovery-design.md}).
 * Rendered through the real PageServlet, because Velocity prints a wrong
 * reference as text instead of failing.
 */
class AfterEntryRenderingTest {

    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");

    private static final Pattern SHARE = Pattern.compile("(?s)<nav class=\"entry-share\".*?</nav>");
    private static final Pattern NEARBY = Pattern.compile(
            "(?s)<section class=\"entry-more entry-nearby\".*?</section>");
    private static final Pattern RELATED = Pattern.compile(
            "(?s)<section class=\"entry-more entry-related\".*?</section>");
    private static final Pattern ITEM_LINK = Pattern.compile(
            "<a class=\"entry-more-title\" href=\"[^\"]*/entry/([^\"]+)\"");

    private String handle;
    private User user;
    private Weblog weblog;
    private final List<Weblog> otherBlogs = new ArrayList<>();
    private long clock = System.currentTimeMillis() - 86_400_000L * 30;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "afterentry" + suffix;
        user = TestUtils.setupUser("afterentryuser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Weblog other : otherBlogs) {
            TestUtils.teardownWeblog(other.getId());
        }
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- fixtures

    private interface Edit {
        void apply(WeblogEntry entry) throws Exception;
    }

    /** A post, each one a day newer than the one before it. */
    private WeblogEntry post(String anchor, PubStatus status, Edit edit) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user), status);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        clock += 86_400_000L;
        managed.setPubTime(new Timestamp(clock));
        managed.setUpdateTime(new Timestamp(clock));
        edit.apply(managed);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    private WeblogEntry post(String anchor, Edit edit) throws Exception {
        return post(anchor, PubStatus.PUBLISHED, edit);
    }

    private WeblogEntry post(String anchor) throws Exception {
        return post(anchor, entry -> { });
    }

    private static Edit place(double lat, double lng) {
        return entry -> {
            entry.setGeoLatitude(lat);
            entry.setGeoLongitude(lng);
        };
    }

    private void configure(String theme, String locale) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setLocale(locale);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String anchor) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + handle + "/entry/" + anchor);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "post page must render for " + anchor);
        String body = response.getContentAsString();
        assertFalse(body.contains("$model.") || body.contains("$near") || body.contains("$more")
                || body.contains("$ae") || body.contains("$text."),
                "a Velocity reference leaked into the page:\n" + body);
        return body;
    }

    private static String only(Pattern section, String body) {
        Matcher m = section.matcher(body);
        assertTrue(m.find(), "expected " + section.pattern() + " in:\n" + body);
        String found = m.group();
        assertFalse(m.find(), "expected exactly one " + section.pattern());
        return found;
    }

    private static List<String> anchors(String section) {
        List<String> anchors = new ArrayList<>();
        Matcher m = ITEM_LINK.matcher(section);
        while (m.find()) {
            anchors.add(m.group(1));
        }
        return anchors;
    }

    /** Runs the check under each article theme and reports every theme that fails. */
    private void inEveryTheme(String locale, ThemeCheck check) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                configure(theme, locale);
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

    // ------------------------------------------------------------------- share

    /** Criteria 1, 3 and 4. */
    @Test
    void shareRowCarriesTheEncodedPermalinkAndNoThirdPartyCode() throws Exception {
        post("share-me", entry -> entry.setTitle("Tom &amp; Jerry&#39;s guide"));

        inEveryTheme("en_US", theme -> {
            String share = only(SHARE, render("share-me"));
            String url = "%2F" + handle + "%2Fentry%2Fshare-me";

            assertTrue(share.contains("href=\"mailto:?subject=Tom%20%26%20Jerry%27s%20guide&amp;body="), share);
            assertTrue(Pattern.compile("href=\"https://www\\.facebook\\.com/sharer/sharer\\.php\\?u=[^\"]*"
                    + url + "\" target=\"_blank\" rel=\"noopener\"").matcher(share).find(), share);
            assertTrue(Pattern.compile("href=\"https://twitter\\.com/intent/tweet\\?url=[^\"]*" + url
                    + "&amp;text=Tom%20%26%20Jerry%27s%20guide\" target=\"_blank\" rel=\"noopener\"")
                    .matcher(share).find(), share);
            assertTrue(Pattern.compile("href=\"https://www\\.linkedin\\.com/sharing/share-offsite/\\?url=[^\"]*"
                    + url + "\" target=\"_blank\" rel=\"noopener\"").matcher(share).find(), share);
            assertFalse(share.contains("amp%3B"), "the title is encoded from its plain text:\n" + share);
            assertFalse(share.contains("pinterest"), "no picture, no Pinterest link:\n" + share);
            assertTrue(Pattern.compile("<button type=\"button\" class=\"entry-share-native\" hidden")
                    .matcher(share).find(), share);
            assertTrue(Pattern.compile("<button type=\"button\" class=\"entry-share-copy\" hidden")
                    .matcher(share).find(), share);
            assertFalse(Pattern.compile("<(script|img|iframe)[^>]+(facebook|twitter|linkedin|pinterest)")
                    .matcher(render("share-me")).find(), "sharing loads nothing from a network");
        });
    }

    /** Criterion 2. */
    @Test
    void pinterestLinkAppearsWithAFeaturedImageAndCarriesIt() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(TestUtils.getManagedWebsite(weblog), "pin-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        post("pin-me", entry -> entry.setFeaturedImageId(imageId));

        inEveryTheme("en_US", theme -> {
            String share = only(SHARE, render("pin-me"));
            assertTrue(Pattern.compile("href=\"https://www\\.pinterest\\.com/pin/create/button/\\?url=[^\"]*"
                    + "%2Fentry%2Fpin-me&amp;media=[^\"]*mediaresource%2F" + imageId
                    + "&amp;description=pin-me\" target=\"_blank\" rel=\"noopener\"")
                    .matcher(share).find(), share);
        });
    }

    /** 0.9.1 criterion 19: off means no row and none of its style or script; the lists are untouched. */
    @Test
    void aBlogWithTheShareRowOffShowsNoRowButKeepsNearbyAndKeepReading() throws Exception {
        post("auburn", place(38.8966, -121.0769));
        post("no-place");
        post("newcastle", place(38.874, -121.133));

        inEveryTheme("en_US", theme -> {
            String on = render("newcastle");
            only(SHARE, on);
            assertTrue(on.contains(".entry-share {") && on.contains("querySelector('.entry-share')"),
                    "on by default, with its style and script:\n" + on);

            setShareRow(false);
            try {
                String off = render("newcastle");
                assertFalse(off.contains("entry-share"),
                        "no share row, share style or share script:\n" + off);
                assertFalse(off.contains("navigator.share") || off.contains("navigator.clipboard"), off);
                assertEquals(List.of("auburn"), anchors(only(NEARBY, off)), off);
                assertEquals(List.of("no-place"), anchors(only(RELATED, off)), off);
                assertTrue(off.contains(".entry-more .entry-more-list {") && off.contains(".entry-more-label {"),
                        "the lists keep their style:\n" + off);
            } finally {
                setShareRow(true);
            }
        });
    }

    /** Saved through the manager, as the settings form does: the save moves lastModified, which expires the page. */
    private void setShareRow(boolean enabled) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setShareRowEnabled(enabled);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------ keep reading

    /** Criteria 5 and 6. */
    @Test
    void keepReadingRanksTagsAndCategoryThenFillsWithTheNewest() throws Exception {
        WeblogCategory farms = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Farms");
        String farmsId = farms.getId();
        TestUtils.endSession(true);
        Edit inFarms = entry -> entry.setCategory(
                TestUtils.weblogger().getWeblogEntryManager().getWeblogCategory(farmsId));

        post("plain-old");
        post("same-category", inFarms);
        post("two-tags", entry -> entry.setTagsAsString("citrus ranching"));
        post("plain-new");
        post("a-draft", PubStatus.DRAFT, entry -> {
            inFarms.apply(entry);
            entry.setTagsAsString("citrus ranching");
        });
        post("current", entry -> {
            inFarms.apply(entry);
            entry.setTagsAsString("citrus ranching food");
        });

        inEveryTheme("en_US", theme -> {
            String body = render("current");
            String related = only(RELATED, body);
            // two-tags scores 4, same-category 3, then the newest of the rest.
            assertEquals(List.of("two-tags", "same-category", "plain-new"), anchors(related), related);
            assertTrue(related.contains(">Keep reading</h2>"), related);
            assertFalse(NEARBY.matcher(body).find(), "no coordinates, no Nearby:\n" + body);
        });
    }

    /** Criterion 7. */
    @Test
    void aBlogWithOnePostShowsNoKeepReading() throws Exception {
        post("alone");

        inEveryTheme("en_US", theme -> {
            String body = render("alone");
            assertFalse(body.contains("entry-related"), body);
            assertFalse(body.contains("entry-nearby"), body);
            only(SHARE, body);
        });
    }

    // ------------------------------------------------------------------ nearby

    /** Criteria 8, 9 (miles) and 10. */
    @Test
    void nearbyListsTheNearestPostsInMilesAndKeepReadingDoesNotRepeatThem() throws Exception {
        post("san-diego", place(32.7157, -117.1611));
        post("grass-valley", place(39.2191, -121.0611));
        post("auburn", place(38.8966, -121.0769));
        post("next-door", place(38.8745, -121.1335));
        post("no-place");
        post("newcastle", place(38.874, -121.133));

        inEveryTheme("en_US", theme -> {
            String body = render("newcastle");
            String nearby = only(NEARBY, body);
            assertEquals(List.of("next-door", "auburn", "grass-valley"), anchors(nearby), nearby);
            assertTrue(nearby.contains(">Nearby</h2>"), nearby);
            assertTrue(nearby.contains(">Under 1 mi away<"), nearby);
            assertTrue(nearby.contains(">3 mi away<"), nearby);
            assertTrue(nearby.contains(">24 mi away<"), nearby);
            assertEquals(List.of("no-place", "san-diego"), anchors(only(RELATED, body)), body);
        });
    }

    /** Criterion 9 (kilometres) and 11. */
    @Test
    void nearbyUsesKilometresOutsideMileCountriesAndVanishesOutOfRange() throws Exception {
        post("far-away", place(48.137, 11.575));
        post("museum-island", place(52.5169, 13.4019));
        post("brandenburg-gate", place(52.5163, 13.3777));

        inEveryTheme("de_DE", theme -> {
            String nearby = only(NEARBY, render("brandenburg-gate"));
            assertEquals(List.of("museum-island"), anchors(nearby), nearby);
            assertTrue(nearby.contains(">2 km"), nearby);

            assertFalse(render("far-away").contains("entry-nearby"),
                    "Munich has no post within range");
        });
    }

    // ------------------------------------------------- nearby, other blogs

    private interface BlogEdit {
        void apply(Weblog blog);
    }

    /** Another blog of the site, removed with the test. */
    private Weblog otherBlog(String handlePrefix, BlogEdit edit) throws Exception {
        Weblog other = TestUtils.setupWeblog(handlePrefix + handle.substring("afterentry".length()), user);
        otherBlogs.add(other);
        Weblog managed = TestUtils.getManagedWebsite(other);
        edit.apply(managed);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        return other;
    }

    private void postOn(Weblog blog, String anchor, PubStatus status, Edit edit) throws Exception {
        Weblog mine = weblog;
        weblog = blog;
        try {
            post(anchor, status, edit);
        } finally {
            weblog = mine;
        }
    }

    /** 0.9.1 criteria 4, 5, 6, 8 and 9. */
    @Test
    void nearbyFillsFromOtherBlogsWithTheirNameAndAbsoluteLink() throws Exception {
        Weblog neighbour = otherBlog("aeneighbour", blog -> blog.setName("Tom & Jerry \"Farms\""));
        Weblog hidden = otherBlog("aehidden", blog -> blog.setVisible(Boolean.FALSE));
        Weblog inactive = otherBlog("aeinactive", blog -> blog.setActive(Boolean.FALSE));
        long tomorrow = System.currentTimeMillis() + 86_400_000L;

        postOn(neighbour, "other-grass-valley", PubStatus.PUBLISHED, place(39.2191, -121.0611));
        postOn(neighbour, "other-next-door", PubStatus.PUBLISHED, place(38.8745, -121.1335));
        postOn(neighbour, "other-san-diego", PubStatus.PUBLISHED, place(32.7157, -117.1611));
        postOn(neighbour, "other-draft", PubStatus.DRAFT, place(38.8741, -121.1331));
        postOn(neighbour, "other-scheduled", PubStatus.SCHEDULED, entry -> {
            place(38.8741, -121.1331).apply(entry);
            entry.setPubTime(new Timestamp(tomorrow));
        });
        postOn(hidden, "other-hidden", PubStatus.PUBLISHED, place(38.8741, -121.1331));
        postOn(inactive, "other-inactive", PubStatus.PUBLISHED, place(38.8741, -121.1331));
        post("auburn", place(38.8966, -121.0769));
        post("no-place");
        post("newcastle", place(38.874, -121.133));

        inEveryTheme("en_US", theme -> {
            String body = render("newcastle");
            String nearby = only(NEARBY, body);
            // the blog's own post first although the neighbour's is nearer
            assertEquals(List.of("auburn", "other-next-door", "other-grass-valley"), anchors(nearby), nearby);
            assertFalse(nearby.contains("$"), "a Velocity reference leaked:\n" + nearby);

            String blogName = "<span class=\"entry-more-blog\">Tom &amp; Jerry &quot;Farms&quot;</span>";
            assertEquals(2, nearby.split(Pattern.quote(blogName), -1).length - 1,
                    "each other-blog item names its blog, escaped once:\n" + nearby);
            assertEquals(2, nearby.split("entry-more-blog", -1).length - 1,
                    "the blog's own item names no blog:\n" + nearby);
            assertTrue(nearby.contains(">3 mi away</span>"), nearby);
            assertTrue(nearby.contains(">Under 1 mi away &middot; " + blogName + "</span>"), nearby);
            assertTrue(nearby.contains(">24 mi away &middot; " + blogName + "</span>"), nearby);

            String otherLink = "href=\"https?://[^\"/]+(/[^\"]*)?/" + neighbour.getHandle() + "/entry/";
            assertTrue(Pattern.compile("<a class=\"entry-more-title\" " + otherLink + "other-next-door\"")
                    .matcher(nearby).find(), "the other blog's absolute permalink:\n" + nearby);
            assertFalse(nearby.contains("/" + handle + "/entry/other-"),
                    "never the other post's anchor under this blog's address:\n" + nearby);

            assertEquals(List.of("no-place"), anchors(only(RELATED, body)),
                    "Keep reading stays same-blog and repeats nothing from Nearby:\n" + body);
        });
    }

    /** 0.9.1 criterion 4: other blogs only fill; a full own blog shows none of them. */
    @Test
    void aBlogWithThreeNearbyPostsOfItsOwnShowsNoOtherBlog() throws Exception {
        Weblog neighbour = otherBlog("aeneighbour", blog -> blog.setName("Neighbour"));
        postOn(neighbour, "other-next-door", PubStatus.PUBLISHED, place(38.8741, -121.1331));
        post("grass-valley", place(39.2191, -121.0611));
        post("auburn", place(38.8966, -121.0769));
        post("next-door", place(38.8745, -121.1335));
        post("newcastle", place(38.874, -121.133));

        inEveryTheme("en_US", theme -> {
            String nearby = only(NEARBY, render("newcastle"));
            assertEquals(List.of("next-door", "auburn", "grass-valley"), anchors(nearby), nearby);
            assertFalse(nearby.contains("entry-more-blog"), nearby);
        });
    }

    // -------------------------------------------------------------------- hero

    /** Criterion 12: journal has no hero; travel and portfolio do. */
    @Test
    void heroLoadsEagerlyOnThePostPageAndCardsStayLazy() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(TestUtils.getManagedWebsite(weblog), "hero-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        post("with-hero", entry -> entry.setFeaturedImageId(imageId));

        for (String theme : List.of("travel", "portfolio")) {
            configure(theme, "en_US");
            String body = render("with-hero");
            Matcher hero = Pattern.compile("<img[^>]*class=\"(tg|pf)-hero-img\"[^>]*>").matcher(body);
            assertTrue(hero.find(), theme + ": expected a hero image:\n" + body);
            assertTrue(hero.group().contains("loading=\"eager\" fetchpriority=\"high\""),
                    theme + ": the hero is the page's largest paint: " + hero.group());

            MockHttpServletResponse home = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                    RenderingTestSupport.anonymousGet("/pressroom-ui/rendering/page", "/" + handle));
            assertFalse(home.getContentAsString().contains("loading=\"eager\""),
                    theme + ": list pictures stay lazy");
        }
    }
}
