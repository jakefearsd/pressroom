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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * A strand page lists its reader-visible parts beneath its text; a post in a
 * strand shows "Part N of M in" the strand and steps through the parts instead
 * of the blog's Older/Newer. Rendered through the real servlets, because
 * Velocity prints a wrong reference as text instead of failing.
 */
class StrandRenderingTest {

    private static final long DAY = 86_400_000L;

    private String handle;
    private User user;
    private Weblog weblog;
    private String strandId;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "strandrender" + suffix;
        user = TestUtils.setupUser("strandrenderuser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void theme(String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setLocale("en_US");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private void page(String slug, String title, boolean strand, WeblogPage.PubStatus status) throws Exception {
        WeblogPage p = new WeblogPage();
        p.setSlug(slug);
        p.setTitle(title);
        p.setContent("INTROTEXT of " + title);
        p.setStatus(status);
        p.setStrand(strand);
        p.setWeblog(TestUtils.getManagedWebsite(weblog));
        TestUtils.weblogger().getWeblogPageManager().savePage(p);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        strandId = p.getId();
    }

    /** Creates a post at {@code daysAgo} and appends it to the strand when {@code inStrand}. */
    private void post(String anchor, String title, int daysAgo, PubStatus status, boolean inStrand)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis() - DAY * daysAgo));
        managed.setTitle(title);
        managed.setText("Body of " + title + ".");
        managed.setStatus(status);
        if (inStrand) {
            mgr.appendToStrand(managed, TestUtils.weblogger().getWeblogPageManager().getPage(strandId));
        }
        mgr.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private String get(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + handle + path);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), path);
        String body = response.getContentAsString();
        for (String leak : new String[] {"$pager", "$text.", "entryMeta.", "$part", "$strand", "#set(",
                "#showStrand", "$url.", "$sbNav"}) {
            assertFalse(body.contains(leak), "'" + leak + "' leaked into the page:\n" + body);
        }
        return body;
    }

    private void threeParts() throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("two", "SECONDPART", 2, PubStatus.PUBLISHED, true);
        post("three", "THIRDPART", 1, PubStatus.PUBLISHED, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aStrandPageListsItsPartsInOrderBeneathItsText(String theme) throws Exception {
        threeParts();
        theme(theme);

        String body = get("/tale");

        int intro = body.indexOf("INTROTEXT");
        int a = body.indexOf("FIRSTPART");
        int b = body.indexOf("SECONDPART");
        int c = body.indexOf("THIRDPART");
        assertTrue(intro > 0 && intro < a, "text before the list");
        assertTrue(a < b && b < c, "parts in position order");
        assertTrue(body.contains("<ol class=\"strand-parts\""), body);
        assertEquals(3, body.split("<li class=\"strand-part\"", -1).length - 1);
        assertTrue(body.contains("/entry/one\"") && body.contains("/entry/three\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aDraftPartIsNotListedOrCounted(String theme) throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("two", "DRAFTPART", 2, PubStatus.DRAFT, true);
        post("three", "THIRDPART", 1, PubStatus.PUBLISHED, true);
        theme(theme);

        assertFalse(get("/tale").contains("DRAFTPART"));
        String first = get("/entry/one");
        assertTrue(first.contains("Part 1 of 2 in"), first);
        assertTrue(get("/entry/three").contains("Part 2 of 2 in"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aPostInAStrandShowsItsPlaceAndPartNavigation(String theme) throws Exception {
        threeParts();
        theme(theme);

        String body = get("/entry/two");

        assertTrue(body.contains("Part 2 of 3 in"), body);
        assertTrue(body.contains("/tale\"") && body.contains(">The Tale<"), "linked strand title");
        assertTrue(body.contains("Previous part") && body.contains("Next part"), body);
        int prev = body.indexOf("Previous part");
        int next = body.indexOf("Next part");
        assertTrue(body.indexOf("FIRSTPART", prev) > 0 && body.indexOf("THIRDPART", next) > 0);
        assertTrue(body.contains("/entry/one\"") && body.contains("/entry/three\""));
        assertFalse(body.contains(">Older<") || body.contains(">Newer<"), "no Older/Newer in a strand");
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void theLastPartLinksBackToTheStrand(String theme) throws Exception {
        threeParts();
        theme(theme);

        String body = get("/entry/three");

        assertTrue(body.contains("Part 3 of 3 in"), body);
        assertTrue(body.contains("Previous part"));
        assertFalse(body.contains("Next part"));
        int all = body.indexOf("All parts");
        assertTrue(all > 0, body);
        String before = body.substring(0, all);
        assertTrue(before.substring(before.lastIndexOf("<a ")).contains("/tale\""), body);
    }

    /**
     * A strand page that is its blog's front door lives at the blog root, so a
     * part's strand bar and the last part's "All parts" step link the root,
     * never the slug: one address for the "start here" page.
     */
    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aStrandThatIsTheFrontDoorIsLinkedAtTheBlogRoot(String theme) throws Exception {
        threeParts();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setFrontPageId(strandId);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        theme(theme);
        String root = TestUtils.weblogger().getUrlStrategy()
                .getWeblogURL(TestUtils.getManagedWebsite(weblog), null, false);

        String middle = get("/entry/two");
        // the bar itself: the nav also links the page (at the root) by the same title
        int bar = middle.indexOf("<p class=\"strand-bar\">");
        assertTrue(bar > 0, middle);
        assertTrue(middle.substring(bar, middle.indexOf("</p>", bar)).contains("<a href=\"" + root + "\">The Tale</a>"),
                "the strand bar links the root:\n" + middle);
        String last = get("/entry/three");
        int all = last.indexOf("All parts");
        assertTrue(all > 0, last);
        String before = last.substring(0, all);
        assertTrue(before.substring(before.lastIndexOf("<a ")).contains("href=\"" + root + "\""),
                "All parts links the root:\n" + last);
        assertFalse(middle.contains("/tale\"") || last.contains("/tale\""), "no link to the slug");
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aSinglePartIsOneOfOne(String theme) throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("only", "ONLYPART", 1, PubStatus.PUBLISHED, true);
        // A neighbour outside the strand, so the blog would have an Older link.
        post("loose", "LOOSEPOST", 2, PubStatus.PUBLISHED, false);
        theme(theme);

        String body = get("/entry/only");

        assertTrue(body.contains("Part 1 of 1 in"), body);
        assertFalse(body.contains("Previous part"));
        assertFalse(body.contains("Next part"), body);
        assertFalse(body.contains(">Older<") || body.contains(">Newer<"), "no Older/Newer in a strand");
        assertTrue(body.contains("All parts"));
    }

    /**
     * "At once" through the render cache: the strand page and a part are
     * rendered, the parts are reordered, and both are rendered again without
     * clearing a cache in between. setStrandParts bumps weblog.lastModified,
     * the only thing that expires a rendered page.
     *
     * <p>A proof of existing behaviour, not a test written before its code:
     * it (and the publishing one below) passed on its first run.
     */
    @Test
    void aReorderShowsAtOnceOnTheStrandPageAndItsParts() throws Exception {
        threeParts();
        theme("journal");
        String before = get("/tale");
        assertTrue(before.indexOf("FIRSTPART") < before.indexOf("THIRDPART"), before);
        assertTrue(get("/entry/three").contains("Part 3 of 3 in"));

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        mgr.setStrandParts(TestUtils.weblogger().getWeblogPageManager().getPage(strandId), List.of(
                mgr.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "three"),
                mgr.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "one"),
                mgr.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "two")));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        String after = get("/tale");
        assertTrue(after.indexOf("THIRDPART") < after.indexOf("FIRSTPART"), after);
        String part = get("/entry/three");
        assertTrue(part.contains("Part 1 of 3 in"), part);
    }

    /** The same through the cache when a draft part is published: it is listed and counted at once. */
    @Test
    void publishingADraftPartShowsAtOnceOnTheStrandPageAndItsParts() throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("two", "DRAFTPART", 2, PubStatus.DRAFT, true);
        theme("journal");
        assertFalse(get("/tale").contains("DRAFTPART"));
        assertTrue(get("/entry/one").contains("Part 1 of 1 in"));

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry draft = mgr.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "two");
        draft.setStatus(PubStatus.PUBLISHED);
        mgr.saveWeblogEntry(draft);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(get("/tale").contains("DRAFTPART"));
        String part = get("/entry/one");
        assertTrue(part.contains("Part 1 of 2 in"), part);
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aPostOfADraftStrandShowsNoBarAndKeepsOlderNewer(String theme) throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.DRAFT);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("two", "SECONDPART", 2, PubStatus.PUBLISHED, true);
        theme(theme);

        String body = get("/entry/one");

        assertFalse(body.contains("Part 1 of"), body);
        assertFalse(body.contains("/tale"), "no link to an unpublished page");
        assertTrue(body.contains(">Newer<") && body.contains("SECONDPART"), body);
    }

    /**
     * A trashed strand keeps its parts: no bar while it is in the trash, and
     * the same "Part N of M" once it is restored and published again.
     */
    @Test
    void aTrashedStrandShowsNoBarUntilRestoredAndPublished() throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("two", "SECONDPART", 2, PubStatus.PUBLISHED, true);
        theme("journal");
        assertTrue(get("/entry/one").contains("Part 1 of 2 in"));
        var pages = TestUtils.weblogger().getWeblogPageManager();

        pages.trashPage(pages.getPage(strandId));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        String trashed = get("/entry/one");
        assertFalse(trashed.contains("Part 1 of"), trashed);
        assertFalse(trashed.contains("/tale"), "no link to a trashed page");

        pages.restorePage(pages.getPage(strandId));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertFalse(get("/entry/one").contains("Part 1 of"), "restored as a draft: still no bar");

        WeblogPage restored = pages.getPage(strandId);
        restored.setStatus(WeblogPage.PubStatus.PUBLISHED);
        pages.savePage(restored);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertTrue(get("/entry/one").contains("Part 1 of 2 in"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aPartScheduledForLaterIsNotListed(String theme) throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("one", "FIRSTPART", 3, PubStatus.PUBLISHED, true);
        post("later", "LATERPART", -2, PubStatus.PUBLISHED, true);
        theme(theme);

        assertFalse(get("/tale").contains("LATERPART"));
        String body = get("/entry/one");
        assertTrue(body.contains("Part 1 of 1 in"), body);
        assertFalse(body.contains("LATERPART"), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aPageThatIsNotAStrandRendersAsBefore(String theme) throws Exception {
        page("plain", "Plain", false, WeblogPage.PubStatus.PUBLISHED);
        theme(theme);

        String body = get("/plain");

        assertTrue(body.contains("INTROTEXT of Plain"));
        assertFalse(body.contains("strand-parts"));
    }
}
