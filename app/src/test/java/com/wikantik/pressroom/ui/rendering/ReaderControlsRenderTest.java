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
package com.wikantik.pressroom.ui.rendering;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, Follow, the read beacon, the reader rows, read marks, the signed-in
 * Places and the "You" menu on the blog pages (0.18.0, Part E task E4),
 * rendered through the real page servlet. The pages are cached and shared
 * per visitor, so a signed-out page carries only neutral controls and the
 * same bytes for everyone; everything about a signed-in reader is filled in
 * the browser from {@code reader.rol} by the reader script. With reader
 * sign-in off nothing of this renders (SSO is switched on per block with
 * {@link StaticConfigOverride}, as {@code GoogleSignInRenderTest} does).
 */
class ReaderControlsRenderTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");
    private static final Pattern BAR = Pattern.compile("<nav class=\"site-bar\"[^>]*>(.*?)</nav>", Pattern.DOTALL);
    private static final String ISSUER = "https://issuer.test/reader-controls";

    private User admin;
    private User reader;
    private final List<Weblog> blogs = new ArrayList<>();
    private final List<String> readers = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();
    private Weblog current;
    private String permalink;
    private String home;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        admin = TestUtils.setupUser("rcrendadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        setProperty("site.name", "Reader Site");
        setProperty("site.frontpage.page", "");
        setProperty("site.frontpage.weblog.handle", "");
        int minutes = 10;
        for (String letter : List.of("a", "b")) {
            Weblog blog = TestUtils.setupWeblog("rcrend" + letter, admin);
            TestUtils.endSession(true);
            Weblog managed = TestUtils.getManagedWebsite(blog);
            managed.setName("Reader Blog " + letter);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
            blogs.add(managed);
            post(managed, "rcrend-" + letter + "1", minutes++);
            post(managed, "rcrend-" + letter + "2", minutes++);
        }
        current = blogs.get(0);
        permalink = "/" + current.getHandle() + "/entry/rcrend-a1";
        home = "/" + current.getHandle() + "/";
        reader = newReader("reader-rcrend1", "Ivy Quinn Reader");
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!originalProperties.isEmpty()) {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog site = weblogs.getSiteSpace();
        if (site != null) {
            weblogs.removeWeblog(site);
        }
        TestUtils.endSession(true);
        for (Weblog blog : blogs) {
            TestUtils.teardownWeblog(blog.getId());
        }
        TestUtils.endSession(true);
        for (String name : readers) {
            TestUtils.teardownUser(name);
        }
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    // ------------------------------------------------------------- signed out

    /**
     * Two anonymous requests for an entry give the same bytes, holding the
     * controls in their neutral state and nothing about any reader: no
     * signed-in flag, no beacon target, no rows, no read marks.
     */
    @Test
    void anonymousEntryHtmlIsIdentical() throws Exception {
        try (var _ = ssoOn()) {
            for (String theme : THEMES) {
                useTheme(current, theme);
                String first = page(permalink);
                assertEquals(first, page(permalink), theme);
                assertTrue(first.contains("data-reader-save data-entry=\""), theme + ": the Save control:\n" + first);
                assertTrue(first.contains("data-reader-follow data-weblog=\"" + current.getHandle() + "\""),
                        theme + ": the Follow control");
                assertTrue(first.contains("aria-pressed=\"false\""), theme + ": neutral");
                assertTrue(first.contains("<script data-reader-stub>"), theme + ": the signed-out stub");
                assertFalse(first.contains("<span hidden data-reader "), theme + ": no data element signed out");
                String markup = withoutReaderScript(first.substring(first.indexOf("</head>")));
                for (String personal : List.of("data-signed-in", "data-read-entry=", "data-reader-row=",
                        "data-reader-entry=", "data-places-source=", "aria-pressed=\"true\"", "<details class=\"you-menu")) {
                    assertFalse(markup.contains(personal), theme + ": no reader data (" + personal + ")");
                }
            }
        }
    }

    /** With reader sign-in off nothing of Part E renders: the pages are as in 0.17.1. */
    @Test
    void ssoOffShowsNoControls() throws Exception {
        useTheme(current, "journal");
        for (String body : List.of(page(permalink), page(home), pageAs(reader.getUserName(), permalink),
                pageAs(reader.getUserName(), home))) {
            for (String part : List.of("data-reader", "you-menu", "reader-save", "reader-follow",
                    "reader-row", "pressroom.intent")) {
                assertFalse(body.contains(part), part + ":\n" + body);
            }
        }
    }

    /**
     * Save sits in the article's share row; Follow sits in the masthead and
     * on a quiet line at the article's end, above "Keep reading". Each click
     * is counted for Umami.
     */
    @Test
    void saveIsInTheShareRowAndFollowInTheMastheadAndAboveKeepReading() throws Exception {
        try (var _ = ssoOn()) {
            for (String theme : THEMES) {
                useTheme(current, theme);
                String body = page(permalink);
                String share = between(body, "<nav class=\"entry-share\"", "</nav>");
                assertTrue(share.contains("data-reader-save"), theme + ": Save in the share row:\n" + share);
                assertTrue(share.contains("data-umami-event=\"save\""), theme);
                String header = between(body, "<header class=\"", "</header>");
                assertTrue(header.contains("data-reader-follow")
                        && header.contains("data-umami-event=\"follow\" data-umami-event-via=\"masthead\""),
                        theme + ": Follow in the masthead:\n" + header);
                int line = body.indexOf("<p class=\"reader-follow-line\">");
                assertTrue(line > body.indexOf("<nav class=\"entry-share\""), theme + ": after the share row");
                int keepReading = body.indexOf("class=\"entry-more entry-related\"");
                assertTrue(keepReading < 0 || line < keepReading, theme + ": above Keep reading");
                String endLine = between(body, "<p class=\"reader-follow-line\">", "</p>");
                assertTrue(endLine.contains("Follow Reader Blog a"), theme + ": " + endLine);
                assertTrue(endLine.contains("data-umami-event=\"follow\" data-umami-event-via=\"article-end\""),
                        theme + ": " + endLine);
            }
        }
    }

    /** A blog with the share row off still offers Save, on its own line. */
    @Test
    void withTheShareRowOffSaveStillRenders() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(current);
        managed.setShareRowEnabled(false);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = page(permalink);
            assertFalse(body.contains("<nav class=\"entry-share\""), body);
            assertTrue(body.contains("<p class=\"entry-save-row\">"), body);
            assertTrue(between(body, "<p class=\"entry-save-row\">", "</p>").contains("data-reader-save"), body);
        }
    }

    /**
     * A blog on a custom domain gets no reader controls: the reader endpoint
     * and the sign-in live on the site's host, whose session that host does
     * not carry (the comments slot says the same).
     */
    @Test
    void aCustomDomainBlogOffersNoControls() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(current);
        managed.setCustomDomain("reader-controls.example.com");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = page(permalink);
            for (String part : List.of("data-reader-save", "data-reader-follow", "<span hidden data-reader ",
                    "<script data-reader-stub>", "pressroom-reader.js")) {
                assertFalse(body.contains(part), part + ":\n" + body);
            }
        }
    }

    // ------------------------------------------------------------- signed in

    /**
     * Signed in, the header's way in becomes the "You" menu: initials and the
     * name, then links to the account page's sections and Sign out, in a
     * keyboard-operable disclosure; an author also gets the Main menu.
     */
    @Test
    void theYouMenuRendersInitialsAndLinksToTheAccountAnchors() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String bar = bar(pageAs(reader.getUserName(), permalink));
            String menu = between(bar, "<details class=\"you-menu", "</details>");
            assertTrue(menu.contains("<span class=\"you-menu-initials\" aria-hidden=\"true\">IR</span>"), menu);
            assertTrue(menu.contains("<span class=\"site-bar-who\">Ivy Quinn Reader</span>"), menu);
            assertTrue(menu.contains("<summary"), "a details/summary disclosure:\n" + menu);
            for (String anchor : List.of("saved", "following", "history", "comments", "email")) {
                assertTrue(Pattern.compile("href=\"[^\"]*/pressroom-ui/account\\.rol#" + anchor + "\"")
                        .matcher(menu).find(), anchor + ":\n" + menu);
            }
            assertTrue(menu.contains("/pressroom-ui/logout.rol"), "Sign out:\n" + menu);
            assertFalse(menu.contains("/pressroom-ui/app/"), "a reader gets no Main menu:\n" + menu);

            String author = between(bar(pageAs(admin.getUserName(), permalink)), "<details class=\"you-menu", "</details>");
            assertTrue(author.contains("/pressroom-ui/app/\">Main menu</a>"), "an author gets the Main menu:\n" + author);
        }
    }

    /** The frontpage theme's top bar and the front page's masthead carry the same menu. */
    @Test
    void theFrontpageTopBarAndTheFrontPageMastheadCarryTheMenu() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "frontpage");
            String body = pageAs(reader.getUserName(), home);
            String topbar = between(body, "<header class=\"fd-topbar\">", "</header>");
            assertTrue(topbar.contains("<details class=\"you-menu fd-topbar-you\">"), topbar);
            assertTrue(topbar.contains("account.rol#saved"), topbar);

            String mast = between(rootAs(reader.getUserName()), "<header class=\"fd-mast\">", "</header>");
            assertTrue(mast.contains("<details class=\"you-menu fd-you\">"), mast);
        }
    }

    /**
     * A signed-in reader's entry page carries what the script needs: the
     * signed-in flag, the beacon's entry, and the Places list from the server
     * in place of the browser's own (whose script and recording are left out,
     * so the browser's list is left untouched).
     */
    @Test
    void aSignedInEntryPageCarriesTheBeaconAndServerPlaces() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = pageAs(reader.getUserName(), permalink);
            String data = between(body, "<span hidden data-reader ", ">");
            assertTrue(data.contains("data-signed-in"), data);
            assertTrue(data.contains("data-read-entry=\""), data);
            assertTrue(Pattern.compile("data-endpoint=\"[^\"]*/pressroom-ui/rendering/reader\\.rol\"").matcher(data).find(),
                    data);
            assertTrue(body.contains("data-places-source=\"server\""), body);
            assertFalse(body.contains("data-places-record"), "no local recording while signed in");
            assertFalse(body.contains("'pressroom.places'"), "the browser's list is not touched while signed in");
        }
    }

    /**
     * The blog home carries "Continue reading" and "From blogs you follow"
     * (hidden until filled) and marks each list title for the "Read" label;
     * an entry page and a category view carry no rows.
     */
    @Test
    void theBlogHomeCarriesTheRowsAndReadMarkHooks() throws Exception {
        try (var _ = ssoOn()) {
            for (String theme : THEMES) {
                useTheme(current, theme);
                String body = pageAs(reader.getUserName(), home);
                assertTrue(body.contains("data-reader-row=\"continue\" hidden"), theme + ":\n" + body);
                assertTrue(body.contains("data-reader-row=\"followed\" hidden"), theme);
                assertTrue(body.contains("Continue reading") && body.contains("From blogs you follow"), theme);
                assertTrue(withoutReaderScript(body).contains("data-reader-entry=\""), theme + ": read-mark hooks on the list");
                assertFalse(withoutReaderScript(pageAs(reader.getUserName(), permalink)).contains("data-reader-row=\""),
                        theme + ": not on an entry page");
            }
        }
    }

    /**
     * The front page puts "Continue reading" and "From blogs you follow"
     * directly under the masthead, each its own section (the markers alone in
     * their class attributes), for a signed-in reader only.
     */
    @Test
    void theFrontPageCarriesTheRowsUnderTheMasthead() throws Exception {
        try (var _ = ssoOn()) {
            String body = rootAs(reader.getUserName());
            int mast = body.indexOf("</header>", body.indexOf("<header class=\"fd-mast\">"));
            int cont = body.indexOf("<section class=\"fd-continue\"");
            int followed = body.indexOf("<section class=\"fd-followed\"");
            int headline = body.indexOf("class=\"fd-discovery-headline\"");
            assertTrue(mast < cont && cont < followed && followed < headline,
                    "under the masthead, before the stories:\n" + body);
            assertTrue(between(body, "<section class=\"fd-continue\"", "</section>").contains("data-reader-row=\"continue\""));
            assertTrue(withoutReaderScript(body).contains("data-reader-entry=\""), "read-mark hooks on the front page's lists");
            String anonymous = rootAs(null);
            assertFalse(anonymous.contains("<section class=\"fd-continue\"") || anonymous.contains("data-reader-entry=\""),
                    anonymous);
        }
    }

    // ------------------------------------------------------------- the script

    /** The most inline reader script, style and data a signed-out page may carry (fix round 1: the anti-goal). */
    private static final int SIGNED_OUT_BUDGET = 2048;

    /**
     * Anonymous pages must not get slower (the spec's anti-goal): a signed-out
     * page carries only the inline stub, under {@link #SIGNED_OUT_BUDGET}
     * bytes with no inline reader style and no data element, and none of the
     * signed-in bundle's code; the bundle and its stylesheet are not even
     * linked. One stub, no hash in it (Velocity would read one).
     */
    @Test
    void theSignedOutReaderPayloadStaysUnderBudget() throws Exception {
        try (var _ = ssoOn()) {
            for (String theme : THEMES) {
                useTheme(current, theme);
                for (String path : List.of(permalink, home)) {
                    String body = page(path);
                    assertEquals(1, body.split("<script data-reader-stub>", -1).length - 1, theme + path);
                    String stub = between(body, "<script data-reader-stub>", "</script>") + "</script>";
                    int inline = stub.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    assertTrue(inline < SIGNED_OUT_BUDGET, theme + path + ": the stub is " + inline + " bytes");
                    assertFalse(stub.contains("#"), stub);
                    for (String bundleCode : List.of("reader.rol", "fetch(", "visibilitychange", "data-reader-row",
                            "readIds", "subscribe")) {
                        assertFalse(stub.contains(bundleCode), theme + path + ": bundle code in the stub: " + bundleCode);
                    }
                    for (String absent : List.of("pressroom-reader.js", "pressroom-reader.css", "<span hidden data-reader ",
                            ".you-menu-list", ".reader-row")) {
                        assertFalse(body.contains(absent), theme + path + ": " + absent);
                    }
                }
            }
        }
    }

    /**
     * A signed-in page (rendered per user) loads the versioned, deferred
     * bundle and the reader stylesheet from the site, and no stub; the
     * bundle loads even where the blog shows no reader state, for the "You"
     * menu.
     */
    @Test
    void aSignedInPageLoadsTheVersionedBundleAndStylesheetInsteadOfTheStub() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = pageAs(reader.getUserName(), permalink);
            assertTrue(Pattern.compile("<script defer src=\"[^\"]*/pressroom-ui/scripts/pressroom-reader\\.js\\?v=[^\"]+\"></script>")
                    .matcher(body).find(), body);
            assertTrue(Pattern.compile("<link rel=\"stylesheet\" href=\"[^\"]*/pressroom-ui/styles/pressroom-reader\\.css\\?v=[^\"]+\">")
                    .matcher(between(body, "<head>", "</head>")).find(), "in the head");
            assertFalse(body.contains("<script data-reader-stub>"), body);
            assertEquals(1, body.split("pressroom-reader.js", -1).length - 1, "once per page");
        }
    }

    // ------------------------------------------------------------- helpers

    private static AutoCloseable ssoOn() {
        StaticConfigOverride id = StaticConfigOverride.set("sso.oidc.client.id", "it-client");
        StaticConfigOverride secret = StaticConfigOverride.set("sso.oidc.client.secret", "it-secret");
        RenderingTestSupport.clearRenderCaches();
        return () -> {
            secret.close();
            id.close();
            RenderingTestSupport.clearRenderCaches();
        };
    }

    /** The page without the reader script, whose own text names the attributes it looks for. */
    private static String withoutReaderScript(String body) {
        int at = body.indexOf("<script data-reader-script>");
        return at < 0 ? body : body.substring(0, at) + body.substring(body.indexOf("</script>", at));
    }

    private static String between(String body, String start, String end) {
        int at = body.indexOf(start);
        assertTrue(at >= 0, "no " + start + ":\n" + body);
        int stop = body.indexOf(end, at + start.length());
        return body.substring(at, stop < 0 ? body.length() : stop);
    }

    private static String bar(String body) {
        Matcher bar = BAR.matcher(body);
        assertTrue(bar.find(), "no site bar:\n" + body);
        return bar.group(1);
    }

    private static String page(String pathInfo) throws Exception {
        return pageAs(null, pathInfo);
    }

    private static String pageAs(String userName, String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET, pathInfo);
        if (userName != null) {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
        }
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), pathInfo + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    /** {@code /} as index.jsp serves it: FrontDoor's choice, forwarded to the page servlet. */
    private static String rootAs(String userName) throws Exception {
        String path = FrontDoor.forwardPath(TestUtils.weblogger());
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET,
                path.substring(PAGE_SERVLET.length()));
        if (userName != null) {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
        }
        request.setDispatcherType(DispatcherType.FORWARD);
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), body);
        TestUtils.endSession(true);
        return body;
    }

    private static void useTheme(Weblog blog, String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(blog);
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private void post(Weblog blog, String anchor, int minutesAgo) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(blog);
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Post " + anchor);
        entry.setText("Body of " + anchor);
        entry.setAnchor(anchor);
        Timestamp when = new Timestamp(System.currentTimeMillis() - minutesAgo * 60_000L);
        entry.setPubTime(when);
        entry.setUpdateTime(when);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entry.setWebsite(managed);
        entry.setCreatorUserName(admin.getUserName());
        entry.setCategory(managed.getWeblogCategories().iterator().next());
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private User newReader(String userName, String screenName) throws Exception {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName(screenName);
        u.setFullName(screenName);
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject("sub-" + UUID.randomUUID());
        TestUtils.weblogger().getUserManager().addReader(u);
        TestUtils.weblogger().flush();
        readers.add(userName);
        TestUtils.endSession(true);
        return u;
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
}
