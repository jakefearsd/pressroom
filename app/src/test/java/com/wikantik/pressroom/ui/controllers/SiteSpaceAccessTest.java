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
package com.wikantik.pressroom.ui.controllers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.auth.PasswordCallerDetails;
import com.wikantik.pressroom.ui.restapi.v1.PagesApi;
import com.wikantik.pressroom.ui.restapi.v1.WeblogSettingsApi;
import com.wikantik.pressroom.ui.restapi.v1.WeblogsApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The site space's access boundary (spec AC7, AC13, Review Focus 4): only a
 * global admin reaches it, only through the Theme screen and, in React, the Pages and Media screens
 * and, for its traffic numbers, one admin-only API route (ruling R25: Global ›
 * Traffic links the site pages' row there), and never through the rest of the
 * automation API.
 *
 * <p>Dispatched through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig}, so {@link PressroomHandlerInterceptor} runs
 * as it does in production, against the real controllers and the database
 * (a global admin's implied weblog permissions are the database's answer,
 * not a stub's). The refusals are compared against what a handle naming no
 * weblog at all gets today, which is the contract: the site space is, to
 * anyone it is not offered to, a weblog that does not exist.
 */
class SiteSpaceAccessTest {

    private static final String SITE = Weblog.SITE_SPACE_HANDLE;
    private static final String AUTHORING = "/pressroom-ui/authoring/";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    private User admin;
    private User author;
    private Weblog authorBlog;

    @Configuration
    @EnableWebMvc
    static class TestConfig {

        /** The suite's tier through a view that is not itself a bean to dispose (see AccountControllerTest). */
        @Bean
        WebloggerProvider webloggerProvider() {
            return new WebloggerProvider() {
                @Override
                public boolean isBootstrapped() {
                    return TestUtils.provider().isBootstrapped();
                }

                @Override
                public void bootstrap() throws com.wikantik.pressroom.business.BootstrapException {
                    TestUtils.provider().bootstrap();
                }

                @Override
                public Weblogger getWeblogger() {
                    return TestUtils.weblogger();
                }
            };
        }

        @Bean(destroyMethod = "")
        Weblogger weblogger() {
            return TestUtils.weblogger();
        }

        @Bean
        CommentService commentService() {
            return new CommentService(TestUtils.weblogger(), comment -> { });
        }

        @Bean
        PressroomHandlerInterceptor pressroomHandlerInterceptor(WebloggerProvider provider) {
            return new PressroomHandlerInterceptor(provider, TestUtils.weblogger());
        }

        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor interceptor) {
            return new WebMvcConfig(interceptor);
        }

        // --- blog screens it does not
        @Bean
        com.wikantik.pressroom.ui.restapi.v1.MetaApi metaApi() {
            return new com.wikantik.pressroom.ui.restapi.v1.MetaApi();
        }

        /** Ruling R25: the site pages' traffic, which Global › Traffic links to, is an admin-only API route. */
        @Bean
        com.wikantik.pressroom.ui.restapi.v1.AdminTrafficApi adminTrafficApi() {
            return new com.wikantik.pressroom.ui.restapi.v1.AdminTrafficApi();
        }

        @Bean
        com.wikantik.pressroom.ui.restapi.v1.TrafficApi trafficApi() {
            return new com.wikantik.pressroom.ui.restapi.v1.TrafficApi();
        }

        // --- the automation API
        @Bean
        PagesApi pagesApi() {
            return new PagesApi();
        }

        @Bean
        com.wikantik.pressroom.ui.restapi.v1.MediaReadApi mediaReadApi() {
            return new com.wikantik.pressroom.ui.restapi.v1.MediaReadApi();
        }

        @Bean
        com.wikantik.pressroom.business.fetch.RemoteImageFetcher remoteImageFetcher() {
            return org.mockito.Mockito.mock(com.wikantik.pressroom.business.fetch.RemoteImageFetcher.class);
        }

        @Bean
        com.wikantik.pressroom.ui.restapi.v1.MediaApi mediaApi() {
            return new com.wikantik.pressroom.ui.restapi.v1.MediaApi();
        }

        @Bean
        WeblogsApi weblogsApi() {
            return new WeblogsApi();
        }

        @Bean
        WeblogSettingsApi weblogSettingsApi() {
            return new WeblogSettingsApi();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() throws Exception {
        TestUtils.setupWeblogger();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = webAppContextSetup(context).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
        assertNotNull(WebloggerRuntimeConfig.getProperty("uploads.file.maxsize"),
                "closing this test's context must leave the suite's runtime config attached");
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = TestUtils.setupUser("ssaadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        author = TestUtils.setupUser("ssaauthor");
        authorBlog = TestUtils.setupWeblog("ssaauthorblog", author);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        TestUtils.endSession(false);
        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog site = weblogs.getSiteSpace();
        if (site != null) {
            weblogs.removeWeblog(site);
        }
        TestUtils.teardownWeblog(authorBlog.getId());
        TestUtils.teardownUser(author.getUserName());
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------ the screens it does not

    // ------------------------------------------------------------ the React admin's session

    @Test
    void anAdminsSessionReadsTheSitePagesAndMediaThroughTheApiButNothingElse() throws Exception {
        siteSpace();
        signInWithSession(admin);
        assertEquals(200, perform(api(get("/api/v1/weblogs/" + SITE + "/pages"))).getResponse().getStatus(),
                "the site pages for the admin's session");
        assertEquals(200, perform(api(get("/api/v1/weblogs/" + SITE + "/media"))).getResponse().getStatus(),
                "the site media for the admin's session");
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE + "/entries"))).getResponse().getStatus(),
                "a route that does not offer the site space still withholds it");
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE))).getResponse().getStatus(),
                "and so do its settings");
    }

    @Test
    void aBlogAuthorsSessionNeverReachesTheSitePages() throws Exception {
        siteSpace();
        signInWithSession(author);
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE + "/pages"))).getResponse().getStatus());
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE + "/media"))).getResponse().getStatus());
    }

    /**
     * Characterisation (the boundary was built without these pins): an admin's session WRITES to the site
     * pages and media, not only reads them. Create, update and trash a page; create and delete a folder.
     */
    @Test
    void anAdminsSessionWritesTheSitePagesAndMedia() throws Exception {
        siteSpace();
        signInWithSession(admin);

        MvcResult created = perform(api(post("/api/v1/weblogs/" + SITE + "/pages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Site about\",\"text\":\"Hello\"}")));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        String id = jsonField(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "id");

        assertEquals(200, perform(api(patch("/api/v1/weblogs/" + SITE + "/pages/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Site about us\"}")))
                .getResponse().getStatus(), "a page update");
        assertEquals(204, perform(api(delete("/api/v1/weblogs/" + SITE + "/pages/" + id)))
                .getResponse().getStatus(), "a page trashed");

        MvcResult folder = perform(api(post("/api/v1/weblogs/" + SITE + "/media/directories")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ssa-folder\"}")));
        assertEquals(201, folder.getResponse().getStatus(), folder.getResponse().getContentAsString());
        String folderId = jsonField(folder.getResponse().getContentAsString(StandardCharsets.UTF_8), "id");
        assertEquals(204, perform(api(delete("/api/v1/weblogs/" + SITE + "/media/directories/" + folderId)))
                .getResponse().getStatus(), "a folder deleted");
    }

    @Test
    void aTokenNeverWritesTheSitePagesOrMediaHoweverPrivileged() throws Exception {
        siteSpace();
        signInWithToken(admin, ApiToken.Role.ADMIN);

        assertEquals(404, perform(api(post("/api/v1/weblogs/" + SITE + "/pages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Nope\",\"text\":\"x\"}")))
                .getResponse().getStatus());
        assertEquals(404, perform(api(patch("/api/v1/weblogs/" + SITE + "/pages/none")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Nope\"}"))).getResponse().getStatus());
        assertEquals(404, perform(api(delete("/api/v1/weblogs/" + SITE + "/pages/none"))).getResponse().getStatus());
        assertEquals(404, perform(api(post("/api/v1/weblogs/" + SITE + "/media/directories")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"nope\"}"))).getResponse().getStatus());
    }

    /**
     * Only a recognised interactive session is offered the site space: a password over HTTP Basic, an
     * unrecognised mechanism and nobody at all are withheld it, reads and writes alike. Nobody at all is
     * refused as unauthenticated (401) before the site space comes into it; the others get the 404 of a weblog
     * that does not exist.
     */
    @Test
    void aPasswordAnUnrecognisedAndAnAnonymousCallerAreNotOfferedTheSiteSpace() throws Exception {
        siteSpace();

        var password = new UsernamePasswordAuthenticationToken(admin.getUserName(), null, List.of());
        password.setDetails(PasswordCallerDetails.SOURCE.buildDetails(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(password);
        assertSiteSpaceWithheld("a password caller", 404);

        var unrecognised = new UsernamePasswordAuthenticationToken(admin.getUserName(), null, List.of());
        unrecognised.setDetails("some future login mechanism");
        SecurityContextHolder.getContext().setAuthentication(unrecognised);
        assertSiteSpaceWithheld("an unrecognised caller", 404);

        SecurityContextHolder.clearContext();
        assertSiteSpaceWithheld("an anonymous caller", 401);
    }

    /** A global admin signed in through the provider (reader SSO) holds a session like a form login does. */
    @Test
    void anSsoSignedInGlobalAdminCountsAsASession() throws Exception {
        siteSpace();
        Instant now = Instant.now();
        var delegate = new DefaultOidcUser(List.of(),
                new OidcIdToken("token", now, now.plusSeconds(3600), Map.of("sub", "google-subject")));
        var principal = new PressroomOidcUser(delegate, admin.getUserName(), List.of(), false);
        var sso = new OAuth2AuthenticationToken(principal, List.of(), "google");
        sso.setDetails(new WebAuthenticationDetails(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(sso);

        assertEquals(200, perform(api(get("/api/v1/weblogs/" + SITE + "/pages"))).getResponse().getStatus());
        MvcResult created = perform(api(post("/api/v1/weblogs/" + SITE + "/pages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Via SSO\",\"text\":\"x\"}")));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
    }

    private void assertSiteSpaceWithheld(String who, int status) throws Exception {
        assertEquals(status, perform(api(get("/api/v1/weblogs/" + SITE + "/pages"))).getResponse().getStatus(),
                who + " reads the site pages");
        assertEquals(status, perform(api(post("/api/v1/weblogs/" + SITE + "/pages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Nope\",\"text\":\"x\"}")))
                .getResponse().getStatus(), who + " writes a site page");
        assertEquals(status, perform(api(get("/api/v1/weblogs/" + SITE + "/media"))).getResponse().getStatus(),
                who + " reads the site media");
    }

    private static String jsonField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), field + " in " + json);
        return m.group(1);
    }

    // ------------------------------------------------------------ the automation API

    @Test
    void theApiAnswersNotFoundForTheSiteSpaceWhateverTheToken() throws Exception {
        siteSpace();

        for (User who : List.of(admin, author)) {
            signInWithToken(who, ApiToken.Role.ADMIN);
            assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE + "/pages"))).getResponse().getStatus(),
                    who.getUserName() + ": the site pages through the API");
        }

        // ... a token never opens it, however privileged; only an admin's own session does, and only on the
        // routes that offer it (below).
        signInWithToken(admin, ApiToken.Role.ADMIN);
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE + "/media"))).getResponse().getStatus(),
                "the site media through the API with a token");

        // WeblogsApi once served these routes requiring no action weblog, so a rule that only ran when
        // one was required would have let an admin token read and rewrite the site space's settings
        // here. WeblogSettingsApi requires one; the withholding still decides.
        signInWithToken(admin, ApiToken.Role.ADMIN);
        assertEquals(404, perform(api(get("/api/v1/weblogs/" + SITE))).getResponse().getStatus());
        assertEquals(404, perform(api(patch("/api/v1/weblogs/" + SITE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Taken over\"}")))
                .getResponse().getStatus());
        assertEquals("Site", TestUtils.weblogger().getWeblogManager().getSiteSpace().getName());
    }

    @Test
    void theApiWeblogListNeverShowsTheSiteSpace() throws Exception {
        siteSpace();
        signInWithToken(admin, ApiToken.Role.ADMIN);

        MvcResult result = perform(api(get("/api/v1/weblogs").param("limit", "200")));

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("ssaauthorblog"), "the list is the real one: " + body);
        assertFalse(body.contains(SITE), body);
    }

    // ------------------------------------------------------------ the main menu and traffic

    @Test
    void theMenuRowsHandAGlobalAdminTheSiteSpaceCreatingItIfAbsent() throws Exception {
        assertNull(TestUtils.weblogger().getWeblogManager().getSiteSpace());
        signInWithToken(admin, ApiToken.Role.ADMIN);

        MvcResult result = perform(api(get("/api/v1/me/weblogs")));

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"siteSpace\":\"" + SITE + "\""), "a global admin's menu carries the Site section: " + body);
        assertNotNull(TestUtils.weblogger().getWeblogManager().getSiteSpace(), "and the site space now exists");
        assertEquals(List.of(), menuHandles(body, SITE), "it is not one of the admin's blogs");
    }

    @Test
    void theMenuRowsShowABlogAuthorNoSiteSpaceAndCreateNothing() throws Exception {
        signInWithToken(author, ApiToken.Role.ADMIN);

        MvcResult result = perform(api(get("/api/v1/me/weblogs")));

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("siteSpace"), body);
        assertNull(TestUtils.weblogger().getWeblogManager().getSiteSpace(),
                "an author's visit creates no site space");
        assertTrue(body.contains("\"handle\":\"ssaauthorblog\""), body);
    }

    /** The weblog route withholds the site space as an unknown weblog; the admin route reads it, for an admin only. */
    @Test
    void theSiteSpacesTrafficIsReadOnlyThroughTheAdminRouteByAGlobalAdmin() throws Exception {
        siteSpace();

        signInWithToken(admin, ApiToken.Role.ADMIN);
        MvcResult unknown = perform(api(get("/api/v1/weblogs/ssanosuchblog/traffic")));
        MvcResult withheld = perform(api(get("/api/v1/weblogs/" + SITE + "/traffic")));
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals(404, withheld.getResponse().getStatus(), "never offered on the weblog route");
        MvcResult read = perform(api(get("/api/v1/admin/traffic/" + SITE).param("period", "7d")));
        assertEquals(200, read.getResponse().getStatus());
        assertTrue(read.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"period\":\"7d\""));

        signInWithToken(author, ApiToken.Role.ADMIN);
        assertEquals(403, perform(api(get("/api/v1/admin/traffic/" + SITE))).getResponse().getStatus(),
                "a blog author is refused the admin route");
    }

    // ------------------------------------------------------------ helpers

    private Weblog siteSpace() throws Exception {
        Weblog site = TestUtils.weblogger().getWeblogManager()
                .getOrCreateSiteSpace(TestUtils.getManagedUser(admin));
        TestUtils.endSession(true);
        return site;
    }

    private static void signIn(User who) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(who.getUserName(), null, List.of()));
    }

    /** A form-login session, as the React admin's calls arrive: the details are the web authentication's. */
    private static void signInWithSession(User who) {
        var auth = new UsernamePasswordAuthenticationToken(who.getUserName(), null, List.of());
        auth.setDetails(new WebAuthenticationDetails(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void signInWithToken(User who, ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken(who.getUserName(), null, List.of());
        auth.setDetails(new ApiPrincipal(who.getUserName(), null, role, "token-ssa"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** The {@code /api/*} servlet mapping strips {@code /api} before Spring routes (WebMvcConfig). */
    private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
        return request.servletPath("/api");
    }

    private static MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn();
        } finally {
            TestUtils.endSession(true);
        }
    }

    /** The handles in the menu rows' body other than {@code except}: none of the admin's own blogs is the site space. */
    private static List<String> menuHandles(String body, String except) {
        return java.util.regex.Pattern.compile("\"handle\":\"([^\"]+)\"").matcher(body).results()
                .map(m -> m.group(1)).filter(h -> h.equals(except)).toList();
    }
}
