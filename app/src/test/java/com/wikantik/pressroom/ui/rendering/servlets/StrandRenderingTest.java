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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
                "#showStrand"}) {
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

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void aSinglePartIsOneOfOne(String theme) throws Exception {
        page("tale", "The Tale", true, WeblogPage.PubStatus.PUBLISHED);
        post("only", "ONLYPART", 1, PubStatus.PUBLISHED, true);
        theme(theme);

        String body = get("/entry/only");

        assertTrue(body.contains("Part 1 of 1 in"), body);
        assertFalse(body.contains("Previous part"));
        assertTrue(body.contains("All parts"));
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
