/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.rendering;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.eclipse.persistence.jpa.JpaEntityManager;
import org.eclipse.persistence.queries.DatabaseQuery;
import org.eclipse.persistence.queries.ObjectLevelReadQuery;
import org.eclipse.persistence.sessions.Session;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comments section of an entry page (the showComments macro), rendered
 * through the real page servlet against stored comments: spec criteria 14,
 * 15, 17, 19 and 22 (render half), and Review Focus 3 on the page.
 *
 * <p>Assertions run on the extracted {@code <section class="comments">}, never
 * on a bare substring of the page, because Velocity prints an unresolved
 * reference literally and a loose match could pass on that. The comments are
 * stored through {@link CommentManager} directly: what is tested is what the
 * page shows for a given stored state, not the posting rules.
 */
class CommentsSectionRenderingTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String ANCHOR = "cs-entry";
    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");

    private static final Pattern SECTION = Pattern.compile(
            "<section class=\"comments\".*?</section>", Pattern.DOTALL);

    /** As ThemeReferenceLeakTest: a Velocity reference or directive left as literal text. */
    private static final Pattern LEAK = Pattern.compile(
            "\\$!?\\{?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"
                    + "|#(show[A-Za-z]+\\(|foreach\\b|if\\b|elseif\\b|else\\b|end\\b|set\\b|macro\\b)");

    private String handle;
    private User owner;
    private User ana;
    private User bo;
    private User reader;
    private Weblog weblog;
    private WeblogEntry entry;
    private long clock = Instant.parse("2026-03-04T12:00:00Z").toEpochMilli();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "csblog" + suffix;
        owner = TestUtils.setupUser("csowner" + suffix);
        ana = TestUtils.setupUser("csana" + suffix);
        bo = TestUtils.setupUser("csbo" + suffix);
        reader = TestUtils.setupUser("csreader" + suffix);
        TestUtils.weblogger().getUserManager().revokeRole("editor", reader);
        TestUtils.weblogger().getUserManager().grantRole("reader", reader);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        rename(owner, "Olga Owner");
        rename(ana, "Ana");
        rename(bo, "Bo");
        rename(reader, "Rey");
        weblog = TestUtils.setupWeblog(handle, owner);
        TestUtils.endSession(true);
        configure("journal", true, null);
        entry = TestUtils.setupWeblogEntry(ANCHOR, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        for (User u : List.of(owner, ana, bo, reader)) {
            TestUtils.teardownUser(u.getUserName());
        }
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- fixtures

    private static void rename(User user, String screenName) throws Exception {
        User managed = TestUtils.getManagedUser(user);
        managed.setScreenName(screenName);
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void configure(String theme, boolean commentsEnabled, String customDomain) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setCommentsEnabled(commentsEnabled);
        managed.setCustomDomain(customDomain);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private void closeEntry() throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setCommentsClosed(Boolean.TRUE);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private CommentManager comments() {
        return TestUtils.weblogger().getCommentManager();
    }

    /** A stored comment whose body is "body-" + {@code tag}, a second after the previous one. */
    private ReaderComment comment(String tag, User author, Status status, ReaderComment replyTo)
            throws Exception {
        ReaderComment c = new ReaderComment();
        c.setEntry(TestUtils.getManagedWeblogEntry(entry));
        c.setAuthor(TestUtils.getManagedUser(author));
        c.setReplyTo(replyTo == null ? null : comments().getComment(replyTo.getId()));
        c.setBody("body-" + tag);
        c.setStatus(status);
        c.setAuthorIsOwner(author == owner);
        c.setCreated(new Date(clock));
        c.setStatusChanged(new Date(clock));
        clock += 1000;
        comments().saveComment(c);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return c;
    }

    // --------------------------------------------------------------- rendering

    private MockHttpServletRequest entryRequest(OAuth2AuthenticationToken principal) {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet(PAGE_SERVLET, "/" + handle + "/entry/" + ANCHOR);
        if (principal != null) {
            request.setUserPrincipal(principal);
        }
        return request;
    }

    private String render(OAuth2AuthenticationToken principal) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = entryRequest(principal);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    /** The one comments section, or null when the page has none. */
    private static String section(String page) {
        Matcher m = SECTION.matcher(page);
        if (!m.find()) {
            return null;
        }
        String found = m.group();
        assertFalse(m.find(), "a page carries at most one comments section");
        return found;
    }

    private String renderedSection() throws Exception {
        String page = render(null);
        String s = section(page);
        assertNotNull(s, "the entry page must carry a comments section:\n" + page);
        return s;
    }

    /** The {@code <article>} of one top-level comment. */
    private static String article(String section, ReaderComment c) {
        int at = section.indexOf("id=\"comment-" + c.getId() + "\"");
        assertTrue(at >= 0, "comment " + c.getId() + " is not on the page:\n" + section);
        int open = section.lastIndexOf("<article", at);
        int close = section.indexOf("</article>", at);
        return section.substring(open, close + "</article>".length());
    }

    /** As ReaderCacheKeyTest builds it: a reader signed in with Google. */
    private static OAuth2AuthenticationToken readerToken(String userName) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject("google-sub-cs")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(), idToken);
        List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority("ROLE_reader"));
        PressroomOidcUser principal = new PressroomOidcUser(delegate, userName, roles, false);
        return new OAuth2AuthenticationToken(principal, roles, "oidc");
    }

    // ------------------------------------------------------------ criterion 14

    @Test
    void withCommentsOffTheEntryPageHasNoCommentsSection() throws Exception {
        comment("c1", ana, Status.PUBLISHED, null);
        configure("journal", false, null);

        String page = render(null);
        assertFalse(page.contains("class=\"comments\""), page);
        // The page script names the class on every page; the slot itself must be absent.
        assertFalse(page.contains("class=\"comments-slot\""), page);
        assertFalse(page.contains("body-c1"), page);
    }

    // ------------------------------------------------------------ criterion 15

    @Test
    void onlyPublishedTopLevelCommentsShowOldestFirstEachWithItsPublishedReply() throws Exception {
        ReaderComment c1 = comment("c1", ana, Status.PUBLISHED, null);
        ReaderComment c2 = comment("c2", bo, Status.PUBLISHED, null);
        ReaderComment r2 = comment("r2", owner, Status.PUBLISHED, c2);
        comment("c3", ana, Status.PENDING, null);
        ReaderComment c4 = comment("c4", bo, Status.HIDDEN, null);
        comment("r4", owner, Status.PUBLISHED, c4);
        ReaderComment c5 = comment("c5", ana, Status.PUBLISHED, null);
        comment("r5", owner, Status.PENDING, c5);

        // The manager's contract, which the page renders from: PUBLISHED
        // top-level only, so a hidden parent's reply never reaches the page.
        assertEquals(List.of(c1.getId(), c2.getId(), c5.getId()),
                comments().getPublishedForEntry(TestUtils.getManagedWeblogEntry(entry)).stream()
                        .map(ReaderComment::getId).toList());

        String s = renderedSection();
        assertTrue(s.contains("<h2 id=\"comments-heading\">3 comments</h2>"), s);
        int p1 = s.indexOf("body-c1");
        int p2 = s.indexOf("body-c2");
        int pr2 = s.indexOf("body-r2");
        int p5 = s.indexOf("body-c5");
        assertTrue(p1 >= 0 && p1 < p2 && p2 < pr2 && pr2 < p5, "oldest first, reply under its parent:\n" + s);
        for (String absent : List.of("body-c3", "body-c4", "body-r4", "body-r5")) {
            assertFalse(s.contains(absent), absent + " must not appear:\n" + s);
        }

        String second = article(s, c2);
        assertTrue(second.contains("<div class=\"comment-reply comment-author\" id=\"comment-" + r2.getId() + "\">"),
                second);
        assertTrue(second.contains("<span class=\"comment-name\">Olga Owner</span> "
                + "<span class=\"comment-badge\">Author</span>"), second);
        assertFalse(article(s, c5).contains("comment-reply"), "a pending reply is not shown");
    }

    @Test
    void eachCommentShowsItsNameDateAndBody() throws Exception {
        ReaderComment c1 = comment("c1", ana, Status.PUBLISHED, null);

        String a = article(renderedSection(), c1);
        assertTrue(a.startsWith("<article class=\"comment\" id=\"comment-" + c1.getId() + "\">"), a);
        assertTrue(a.contains("<span class=\"comment-name\">Ana</span>"), a);
        assertFalse(a.contains("comment-badge"), "a reader's comment carries no Author badge:\n" + a);
        // in the weblog's zone (Los Angeles): 12:00 UTC is the morning of the
        // 4th; the same "MMMM d, yyyy" the themes' bylines use
        assertTrue(Pattern.compile("<time datetime=\"2026-03-0[45]T[^\"]+\">March 4, 2026</time>")
                .matcher(a).find(), a);
        assertTrue(a.contains("<div class=\"comment-body\"><p>body-c1</p></div>"), a);
    }

    @Test
    void anOwnersCommentCarriesTheAuthorBadge() throws Exception {
        ReaderComment c1 = comment("c1", owner, Status.PUBLISHED, null);

        String a = article(renderedSection(), c1);
        assertTrue(a.startsWith("<article class=\"comment comment-author\" id=\"comment-" + c1.getId() + "\""), a);
        assertTrue(a.contains("<span class=\"comment-name\">Olga Owner</span> "
                + "<span class=\"comment-badge\">Author</span>"), a);
    }

    @Test
    void theBodyIsTextWithItsLinksMadeSafe() throws Exception {
        ReaderComment c1 = comment("c1", ana, Status.PUBLISHED, null);
        ReaderComment managed = comments().getComment(c1.getId());
        managed.setBody("<b>hi</b> see https://ex.com/a\n\nnext");
        comments().saveComment(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        String a = article(renderedSection(), c1);
        assertTrue(a.contains("<div class=\"comment-body\"><p>&lt;b&gt;hi&lt;/b&gt; see "
                + "<a href=\"https://ex.com/a\" rel=\"nofollow ugc noopener\">https://ex.com/a</a></p>"
                + "<p>next</p></div>"), a);
    }

    @Test
    void theHeadingCountsTheComments() throws Exception {
        assertTrue(renderedSection().contains("<h2 id=\"comments-heading\">Comments</h2>"));
        comment("c1", ana, Status.PUBLISHED, null);
        assertTrue(renderedSection().contains("<h2 id=\"comments-heading\">1 comment</h2>"));
    }

    // -------------------------------------------------------- Review Focus 2

    /** The queries one anonymous render of the entry page runs: [all, against ReaderComment]. */
    private int[] queriesForOneRender() throws Exception {
        Field f = comments().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) f.get(comments());
        // The page servlet runs on this thread, so it uses this same entity manager.
        Session session = strategy.getEntityManager(false).unwrap(JpaEntityManager.class).getActiveSession();
        AtomicInteger all = new AtomicInteger();
        AtomicInteger onComments = new AtomicInteger();
        SessionEventAdapter counter = new SessionEventAdapter() {
            @Override
            public void preExecuteQuery(SessionEvent event) {
                all.incrementAndGet();
                DatabaseQuery query = event.getQuery();
                if (query instanceof ObjectLevelReadQuery read && read.getReferenceClass() == ReaderComment.class) {
                    onComments.incrementAndGet();
                }
            }
        };
        session.getEventManager().addListener(counter);
        try {
            assertNotNull(section(render(null)));
        } finally {
            session.getEventManager().removeListener(counter);
        }
        return new int[] {all.get(), onComments.get()};
    }

    @Test
    void manyCommentsAndRepliesRenderInTheSameNumberOfQueriesAsTwo() throws Exception {
        ReaderComment first = comment("c0", ana, Status.PUBLISHED, null);
        comment("r0", owner, Status.PUBLISHED, first);
        render(null); // warm the theme and template caches
        int[] few = queriesForOneRender();

        for (int i = 1; i <= 30; i++) {
            ReaderComment c = comment("c" + i, i % 2 == 0 ? ana : bo, Status.PUBLISHED, null);
            comment("r" + i, owner, Status.PUBLISHED, c);
        }
        int[] many = queriesForOneRender();

        assertEquals(1, few[1], "the section reads its comments in one query");
        assertEquals(1, many[1], "31 comments and their replies are still one query");
        assertEquals(few[0], many[0], "nothing on the page runs a query per comment, reply or author");
    }

    // ------------------------------------------------------------ criterion 17

    @Test
    void theSectionIsByteForByteTheSameForAnAnonymousVisitorAndASignedInReader() throws Exception {
        comment("c1", ana, Status.PUBLISHED, null);
        ReaderComment mine = comment("mine", reader, Status.PUBLISHED, null);
        comment("r-mine", owner, Status.PUBLISHED, mine);
        comment("pending-mine", reader, Status.PENDING, null);

        OAuth2AuthenticationToken token = readerToken(reader.getUserName());
        assertEquals(reader.getUserName(),
                new WeblogPageRequest(TestUtils.weblogger(), entryRequest(token)).getAuthenticUser(),
                "the second render really is the reader's");
        TestUtils.endSession(false);

        String anonymous = section(render(null));
        String signedIn = section(render(token));

        assertNotNull(anonymous);
        assertEquals(anonymous, signedIn, "nothing in the section may depend on who is looking");
        assertFalse(signedIn.contains("body-pending-mine"),
                "a reader's own pending comment comes from the session endpoint, never the cached page");
    }

    /**
     * Ruling R7: the cached public page carries no usernames. A reader's is
     * random, but an owner's is their login name, and the page is shared and
     * cached. The page script matches the caller's own comments by the ids
     * the session endpoint returns, against {@code id="comment-<id>"}.
     */
    @Test
    void theSectionCarriesNoUsernameOfAnyCommenter() throws Exception {
        ReaderComment c1 = comment("c1", ana, Status.PUBLISHED, null);
        comment("r1", owner, Status.PUBLISHED, c1);
        comment("c2", bo, Status.PUBLISHED, null);
        comment("c3", reader, Status.PUBLISHED, null);
        comment("c4", owner, Status.PUBLISHED, null);

        String s = renderedSection();
        assertTrue(s.contains("body-c1") && s.contains("body-r1") && s.contains("body-c4"), s);
        assertFalse(s.contains("data-author"), s);
        for (User u : List.of(owner, ana, bo, reader)) {
            assertFalse(s.contains(u.getUserName()), u.getUserName() + " is on the public page:\n" + s);
        }
        assertTrue(s.contains("id=\"comment-" + c1.getId() + "\""), "the script matches on the comment id:\n" + s);
    }

    // ------------------------------------------------------------ criterion 19

    @Test
    void aClosedEntryShowsItsCommentsAndSaysCommentsAreClosed() throws Exception {
        comment("c1", ana, Status.PUBLISHED, null);
        closeEntry();

        String s = renderedSection();
        assertTrue(s.contains("body-c1"), s);
        assertTrue(s.contains("<p class=\"comments-closed\">Comments are closed.</p>"), s);
        assertFalse(s.contains("comments-slot"), s);
    }

    // --------------------------------------------------- the slot, criterion 22

    @Test
    void anOpenEntryCarriesTheSlotWithServerBuiltEndpoints() throws Exception {
        String s = renderedSection();
        String slot = s.substring(s.indexOf("<div class=\"comments-slot\""), s.indexOf("</div>",
                s.indexOf("<div class=\"comments-slot\"")));
        assertTrue(slot.contains(" data-entry=\"" + entry.getId() + "\""), slot);
        assertTrue(slot.contains(" data-permalink=\"/roller/" + handle + "/entry/" + ANCHOR + "\""), slot);
        assertTrue(slot.contains(" data-session=\"/roller/pressroom-ui/rendering/comments/session.rol\""), slot);
        assertTrue(slot.contains(" data-post=\"/roller/pressroom-ui/rendering/comments.rol\""), slot);
        assertTrue(slot.contains(" data-delete=\"/roller/pressroom-ui/rendering/comments/\""), slot);
        assertTrue(slot.contains(" data-msg-sign-in=\"Sign in with Google to comment\""), slot);
        assertFalse(slot.contains("data-unavailable"), slot);
        assertFalse(s.contains("comments-closed"), s);
    }

    /**
     * Every word the page script shows comes from the slot, rendered from the
     * bundle and escaped for the attribute, so the script carries no English.
     * {@code comments.signedInAs} keeps its placeholder: the script fills it.
     */
    @Test
    void theSlotCarriesEveryMessageTheScriptShows() throws Exception {
        String s = renderedSection();
        String slot = s.substring(s.indexOf("<div class=\"comments-slot\""), s.indexOf("</div>",
                s.indexOf("<div class=\"comments-slot\"")));
        for (String attribute : List.of(
                " data-msg-unavailable=\"Commenting is not available on this address yet.\"",
                " data-msg-pending=\"Thanks &mdash; your comment will appear once it is approved.\"",
                " data-msg-awaiting=\"Awaiting approval\"",
                " data-msg-published=\"Published.\"",
                " data-msg-error=\"Your comment could not be posted. Please try again.\"",
                " data-msg-rate=\"You are commenting too fast. Please wait a while.\"",
                " data-msg-signed-in-as=\"Signed in as {0}\"",
                " data-msg-account=\"Your account\"",
                " data-msg-delete=\"Delete\"",
                " data-msg-delete-confirm=\"Delete your comment?\"",
                " data-msg-delete-failed=\"Your comment could not be deleted. Please try again.\"",
                " data-msg-post=\"Post comment\"",
                " data-msg-closed=\"Comments are closed.\"",
                " data-msg-not-allowed=\"This account cannot comment here.\"")) {
            assertTrue(slot.contains(attribute), attribute + " in " + slot);
        }
    }

    @Test
    void aCustomDomainWeblogShowsItsCommentsAndAnUnavailableSlot() throws Exception {
        comment("c1", ana, Status.PUBLISHED, null);
        configure("journal", true, "blog.example.test");

        String s = renderedSection();
        assertTrue(s.contains("body-c1"), s);
        assertTrue(s.contains("<div class=\"comments-slot\" data-unavailable=\"custom-domain\" "
                + "data-msg-unavailable=\"Commenting is not available on this address yet.\"></div>"), s);
        assertFalse(s.contains("data-session"), s);
        assertFalse(s.contains("data-post"), s);
    }

    // -------------------------------------------------------- Review Focus 3

    @Test
    void displayNameIsEscapedOnThePage() throws Exception {
        // weblogAdminsUntrusted=false stores and loads a screen name as typed;
        // the page's escaping is then the only thing between it and the DOM.
        Boolean previous = HTMLSanitizer.xssEnabled;
        HTMLSanitizer.xssEnabled = Boolean.FALSE;
        try {
            rename(ana, "<script>x</script>");
            rename(bo, "\"><img src=x onerror=alert(1)>");
            comment("c1", ana, Status.PUBLISHED, null);
            comment("c2", bo, Status.PUBLISHED, null);

            String s = renderedSection();
            assertTrue(s.contains("<span class=\"comment-name\">&lt;script&gt;x&lt;/script&gt;</span>"), s);
            assertTrue(s.contains("<span class=\"comment-name\">&quot;&gt;&lt;img src=x onerror=alert(1)&gt;</span>"),
                    s);
            assertFalse(s.contains("<script>x"), s);
            assertFalse(s.contains("<img"), s);
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    // ------------------------------------------------------------- the themes

    /**
     * An actual invocation: the macro call alone on its line, as the themes
     * write it. A commented-out call or a mention in prose does not count.
     */
    private static final Pattern MACRO_CALL = Pattern.compile(
            "(?m)^[ \\t]*#showComments\\(\\$model\\.weblog \\$model\\.weblogEntry\\)[ \\t]*$");

    /** Velocity block comments and "##" line comments. */
    private static final Pattern VELOCITY_COMMENTS = Pattern.compile("(?s)#\\*.*?\\*#|##[^\\r\\n]*");

    @Test
    void everyThemesPermalinkCallsTheMacro() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String theme : THEMES) {
            String live = VELOCITY_COMMENTS.matcher(
                    Files.readString(Path.of("src/main/webapp/themes", theme, "permalink.vm"))).replaceAll("");
            if (!MACRO_CALL.matcher(live).find()) {
                missing.add(theme);
            }
        }
        assertTrue(missing.isEmpty(), "permalink.vm without the comments section: " + missing);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void everyThemeRendersTheSectionWithNoLiteralReference(String theme) throws Exception {
        configure(theme, true, null);
        ReaderComment c1 = comment("c1", ana, Status.PUBLISHED, null);
        comment("r1", owner, Status.PUBLISHED, c1);

        String s = renderedSection();
        assertTrue(s.contains("body-c1") && s.contains("body-r1") && s.contains("comments-slot"), s);
        Matcher leak = LEAK.matcher(s);
        if (leak.find()) {
            throw new AssertionError(theme + " left `" + leak.group() + "` in the section:\n" + s);
        }
    }

    /**
     * The comment form script reaches the page exactly as written: Velocity
     * prints what it cannot resolve without complaint, so a stray reference or
     * directive in the script would ship as broken JavaScript, not fail here.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void theCommentFormScriptRendersVerbatim(String theme) throws Exception {
        configure(theme, true, null);
        String page = render(null);
        String start = "// --- comments ---";
        String end = "// --- end comments ---";
        int from = page.indexOf(start);
        int to = page.indexOf(end);
        assertTrue(from >= 0 && to > from, theme + " renders the comment form script");
        assertEquals(CommentsScriptSourceTest.script(), page.substring(from + start.length(), to));
    }

    /**
     * D3: a display name or body carrying a bidi control (U+202E) must not
     * reorder the text around it: each is its own bidi island.
     */
    @Test
    void namesAndBodiesAreBidiIsolated() throws Exception {
        configure("journal", true, null);
        String page = render(null);
        for (String selector : new String[] {".comments .comment-name", ".comments .comment-body"}) {
            java.util.regex.Matcher rule = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(selector) + " \\{([^}]*)\\}").matcher(page);
            assertTrue(rule.find(), "no rule for " + selector);
            assertTrue(rule.group(1).contains("unicode-bidi: isolate"), selector + " is not isolated: " + rule.group(1));
        }
    }

    @Test
    void theWeblogHomePageRendersNoSection() throws Exception {
        comment("c1", ana, Status.PUBLISHED, null);
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET, "/" + handle + "/");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus());
        assertNull(section(response.getContentAsString()), "the home page lists entries; comments are on the entry");
    }
}
