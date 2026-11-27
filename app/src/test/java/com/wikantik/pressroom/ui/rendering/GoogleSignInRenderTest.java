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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.core.security.sso.SignInMark;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
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
 * The reader sign-in surfaces of the blog pages (0.18.0, Part D), rendered
 * through the real page servlet: the shared Google button (the
 * showGoogleSignIn macro) in the header, the comments slot's words, the
 * places nudge, the colophon's author sign-in, and the client-side helper
 * Part E builds its Save and Follow buttons with.
 *
 * <p>With reader sign-in off every surface is as before; the 0.17.1 markup is
 * pinned by {@code SiteBarRenderingTest}, {@code FrontpageHeaderRenderingTest},
 * {@code DiscoveryFrontPageRenderingTest} and {@code CommentsSectionRenderingTest},
 * which run with the suite's SSO-off configuration. SSO is switched on here
 * for one block at a time through {@link StaticConfigOverride}.
 */
class GoogleSignInRenderTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");
    private static final Pattern BAR = Pattern.compile("<nav class=\"site-bar\"[^>]*>(.*?)</nav>", Pattern.DOTALL);
    private static final Pattern HEADER_BUTTON = Pattern.compile(
            "<a class=\"g-signin\" data-umami-event=\"sign-in\" data-umami-event-via=\"header\" data-sign-in-return"
            + " href=\"[^\"]*/pressroom-ui/sso/start\">");
    private static final String KEYCLOAK_ISSUER = "http://localhost:8180/realms/pressroom-it";

    private User admin;
    private final List<Weblog> blogs = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();
    private Weblog current;
    private String permalink;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        admin = TestUtils.setupUser("gsigninadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        setProperty("site.name", "Sign-in Site");
        setProperty("site.frontpage.page", "");
        setProperty("site.frontpage.weblog.handle", "");
        int minutes = 10;
        for (String letter : List.of("a", "b")) {
            Weblog blog = TestUtils.setupWeblog("gsignin" + letter, admin);
            TestUtils.endSession(true);
            Weblog managed = TestUtils.getManagedWebsite(blog);
            managed.setName("Sign-in Blog " + letter);
            managed.setCommentsEnabled(Boolean.TRUE);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
            blogs.add(managed);
            post(managed, "gsignin-" + letter, minutes++);
        }
        current = blogs.get(0);
        permalink = "/" + current.getHandle() + "/entry/gsignin-a";
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
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    // ------------------------------------------------------------- header

    /**
     * SSO on, signed out: the header's way in is the compact Google button,
     * first in the account links, counted as a sign-in via the header, on
     * every blog theme. The password Sign in link is gone from the header.
     */
    @Test
    void aSignedOutVisitorGetsTheGoogleButtonInTheHeader() throws Exception {
        try (var _ = ssoOn()) {
            for (String theme : THEMES) {
                useTheme(current, theme);
                String bar = bar(page(permalink));
                Matcher button = HEADER_BUTTON.matcher(bar);
                assertTrue(button.find(), theme + ": the Google button:\n" + bar);
                String account = bar.substring(bar.indexOf("<span class=\"site-bar-account\">"));
                assertTrue(account.indexOf("g-signin") < account.indexOf("</a>"),
                        theme + ": first in the account links:\n" + account);
                assertTrue(bar.contains(SignInMark.forProvider(google()) + "<span>Sign in with Google</span></a>"),
                        theme + ": the G and the label:\n" + bar);
                assertFalse(bar.contains("site-bar-sign-in"), theme + ": no password Sign in in the header:\n" + bar);
                assertFalse(bar.contains("<script"), theme + ": no script per button:\n" + bar);
            }
        }
    }

    /**
     * One return-path script per page, in the head, for every server-rendered
     * button wherever it sits (the header, the places nudge, Part E's), run
     * once the page has parsed; not one script per button.
     */
    @Test
    void oneReturnPathScriptServesEveryButton() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = page(permalink);
            String selector = "document.querySelectorAll('a.g-signin[data-sign-in-return]')";
            assertEquals(1, body.split(java.util.regex.Pattern.quote(selector), -1).length - 1, body);
            assertTrue(head(body).contains(selector), "in the head:\n" + head(body));
            assertTrue(head(body).contains("document.addEventListener('DOMContentLoaded', addReturn)"), head(body));
            assertTrue(body.split("data-sign-in-return href=", -1).length - 1 >= 2,
                    "the header and the places nudge both carry a button:\n" + body);
        }
    }

    /** The provider's own name, and a neutral mark, for anything but Google. */
    @Test
    void anotherProviderGetsItsNameAndANeutralMark() throws Exception {
        try (var _ = ssoOn();
             var _ = StaticConfigOverride.set("sso.oidc.discovery.uri", KEYCLOAK_ISSUER);
             var _ = StaticConfigOverride.set("sso.provider.label", "Keycloak & Co")) {
            useTheme(current, "journal");
            String bar = bar(page(permalink));
            SsoSettings keycloak = new SsoSettings("id", "s", KEYCLOAK_ISSUER, "Keycloak");
            assertTrue(bar.contains(SignInMark.forProvider(keycloak) + "<span>Sign in with Keycloak &amp; Co</span>"),
                    "the label, escaped once, beside the neutral mark:\n" + bar);
            assertFalse(bar.contains("#4285F4"), "no Google colours:\n" + bar);
        }
    }

    @Test
    void aSignedInAuthorGetsNoGoogleButton() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String body = pageAs(admin.getUserName(), permalink);
            assertFalse(body.contains("class=\"g-signin\""), body);
            assertTrue(bar(body).contains("<span class=\"site-bar-who\">"), body);
        }
    }

    /** The frontpage theme's own top bar carries the same button. */
    @Test
    void theFrontpageThemesTopBarCarriesTheButton() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "frontpage");
            String body = page("/" + current.getHandle() + "/");
            String topbar = body.substring(body.indexOf("<header class=\"fd-topbar\">"),
                    body.indexOf("</header>", body.indexOf("<header class=\"fd-topbar\">")));
            assertTrue(HEADER_BUTTON.matcher(topbar).find(), topbar);
            assertFalse(topbar.contains("fd-sign-in"), topbar);
        }
    }

    /**
     * Signed-out HTML stays identical for every visitor: the return path is
     * added in the browser, never in the page.
     */
    @Test
    void twoAnonymousRendersAreByteIdentical() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String first = page(permalink);
            assertTrue(first.contains("class=\"g-signin\""), first);
            assertFalse(first.contains("sso/start?return="), "no per-request return in the HTML:\n" + first);
            assertEquals(first, page(permalink));
        }
    }

    // ------------------------------------------------- colophon and helper

    /**
     * The colophon's Sign in stays a plain link to the sign-in page, the
     * authors' way in; with the header now Google's, it carries the page as
     * its return path in the browser, as the header's link did.
     */
    @Test
    void theColophonSignInStillLinksToTheSignInPage() throws Exception {
        try (var _ = ssoOn()) {
            useTheme(current, "journal");
            String colophon = colophon(page(permalink));
            assertTrue(Pattern.compile("<a class=\"colophon-sign-in\" href=\"[^\"]*/pressroom-ui/login\\.rol\">Sign in</a>")
                    .matcher(colophon).find(), colophon);
            assertTrue(colophon.contains("document.querySelectorAll('a.colophon-sign-in')"), colophon);
        }
        String off = colophon(page(permalink));
        assertTrue(off.contains("<span class=\"colophon-account\"><a href=\""), "SSO off: as before:\n" + off);
        assertFalse(off.contains("colophon-sign-in"), off);
    }

    /**
     * The helper Part E builds its signed-out Save and Follow buttons with,
     * and the button's rules, ride the head; SSO off, neither is there.
     */
    @Test
    void theHelperAndItsStylesAreInTheHeadOnlyWithSsoOn() throws Exception {
        useTheme(current, "journal");
        try (var _ = ssoOn()) {
            String head = head(page(permalink));
            assertTrue(head.contains("window.pressroomGoogleSignIn = function (via, label) {"), head);
            assertTrue(head.contains("var start = '"), head);
            assertTrue(head.contains("\\/pressroom-ui\\/sso\\/start';"), "escaped for the script: " + head);
            assertTrue(head.contains("var mark = '" + SignInMark.forProvider(google()) + "';"), head);
            assertTrue(head.contains("html body a.g-signin[data-umami-event] {"), "the button's rules:\n" + head);
        }
        // (the comments script names the helper, and falls back to a plain link without it)
        String head = head(page(permalink));
        assertFalse(head.contains("window.pressroomGoogleSignIn = function"), head);
        assertFalse(head.contains("a.g-signin[data-umami-event] {"), head);
    }

    // ------------------------------------------------------------ comments

    /** The comments sign-in names the provider: "Sign in with {provider} to comment". */
    @Test
    void theCommentsSignInNamesTheProvider() throws Exception {
        useTheme(current, "journal");
        try (var _ = ssoOn()) {
            assertTrue(page(permalink).contains(" data-msg-sign-in=\"Sign in with Google to comment\""));
            try (var _ = StaticConfigOverride.set("sso.provider.label", "Keycloak")) {
                assertTrue(page(permalink).contains(" data-msg-sign-in=\"Sign in with Keycloak to comment\""));
            }
        }
    }

    // -------------------------------------------------------------- places

    /** "Sign in to keep this on all your devices", under Places you've been, signed out with SSO on only. */
    @Test
    void thePlacesNudgeIsOnlyForASignedOutVisitorWithSsoOn() throws Exception {
        useTheme(current, "journal");
        try (var _ = ssoOn()) {
            String places = places(page(permalink));
            assertTrue(places.contains("<p class=\"places-been-nudge\"><a class=\"g-signin\" data-umami-event=\"sign-in\""
                    + " data-umami-event-via=\"places\" data-sign-in-return"), places);
            assertTrue(places.contains("<span>Sign in with Google to keep this on all your devices</span>"), places);
            assertFalse(places(pageAs(admin.getUserName(), permalink)).contains("places-been-nudge"),
                    "nothing to nudge a signed-in visitor about");
        }
        assertFalse(places(page(permalink)).contains("places-been-nudge"), "SSO off: no nudge");
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

    private static SsoSettings google() {
        return new SsoSettings("id", "s", "", "");
    }

    private static String bar(String body) {
        Matcher bar = BAR.matcher(body);
        assertTrue(bar.find(), "no site bar:\n" + body);
        return bar.group(1);
    }

    private static String colophon(String body) {
        int at = body.indexOf("<div class=\"colophon\">");
        assertTrue(at >= 0, "no colophon:\n" + body);
        return body.substring(at, body.indexOf("</div>", at));
    }

    private static String places(String body) {
        int at = body.indexOf("<nav class=\"places-been\"");
        assertTrue(at >= 0, "no places container:\n" + body);
        return body.substring(at, body.indexOf("</nav>", at));
    }

    private static String head(String body) {
        return body.substring(0, body.indexOf("</head>"));
    }

    private static String page(String pathInfo) throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet(PAGE_SERVLET, pathInfo));
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), pathInfo + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    private static String pageAs(String userName, String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET, pathInfo);
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), pathInfo + ":\n" + body);
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
