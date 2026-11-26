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
import java.util.List;
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
import com.wikantik.pressroom.ui.controllers.admin.UserWeblogsController;
import com.wikantik.pressroom.ui.controllers.editor.CategoriesController;
import com.wikantik.pressroom.ui.controllers.editor.CommentsModerationController;
import com.wikantik.pressroom.ui.controllers.editor.EntriesController;
import com.wikantik.pressroom.ui.controllers.editor.MediaFileAddController;
import com.wikantik.pressroom.ui.controllers.editor.MediaFileEditController;
import com.wikantik.pressroom.ui.controllers.editor.MediaFileImageChooserController;
import com.wikantik.pressroom.ui.controllers.editor.MediaFileViewController;
import com.wikantik.pressroom.ui.controllers.editor.PageEditController;
import com.wikantik.pressroom.ui.controllers.editor.PagesController;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The site space's access boundary (spec AC7, AC13, Review Focus 4): only a
 * global admin reaches it, only through the Pages, Media and Theme screens
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

        // --- the screens the site space offers that are still JSP (its theme moved to the React admin: AdminSiteThemeApi)
        @Bean
        PagesController pagesController() {
            return new PagesController();
        }

        @Bean
        PageEditController pageEditController() {
            return new PageEditController();
        }

        @Bean
        MediaFileViewController mediaFileViewController() {
            return new MediaFileViewController();
        }

        @Bean
        MediaFileAddController mediaFileAddController() {
            return new MediaFileAddController();
        }

        @Bean
        MediaFileEditController mediaFileEditController() {
            return new MediaFileEditController();
        }

        @Bean
        MediaFileImageChooserController mediaFileImageChooserController() {
            return new MediaFileImageChooserController();
        }

        // --- blog screens it does not
        @Bean
        EntriesController entriesController() {
            return new EntriesController();
        }

        @Bean
        CommentsModerationController commentsModerationController(CommentService comments) {
            return new CommentsModerationController(comments);
        }

        @Bean
        CategoriesController categoriesController() {
            return new CategoriesController();
        }

        @Bean
        UserWeblogsController userWeblogsController() {
            return new UserWeblogsController();
        }

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

    // ------------------------------------------------------------ the screens it offers

    @Test
    void aGlobalAdminReachesTheSitePagesAndMedia() throws Exception {
        siteSpace();
        signIn(admin);

        for (String screen : List.of("pages.rol", "pageEdit.rol", "mediaFileView.rol",
                "mediaFileAdd.rol", "mediaFileEdit.rol", "overlay/mediaFileImageChooser.rol")) {
            MvcResult result = perform(get(AUTHORING + screen).param("weblog", SITE));
            assertEquals(200, result.getResponse().getStatus(), screen + " is offered on the site space");
            Weblog acting = (Weblog) result.getRequest().getAttribute("actionWeblog");
            assertNotNull(acting, screen + " acts on the site space");
            assertTrue(acting.isSiteSpace(), screen);
        }
    }

    // ------------------------------------------------------------ the screens it does not

    @Test
    void aGlobalAdminIsRefusedEveryOtherBlogScreenAsForAWeblogThatDoesNotExist() throws Exception {
        siteSpace();
        signIn(admin);

        for (Function<String, MockHttpServletRequestBuilder> screen : List.<Function<String, MockHttpServletRequestBuilder>>of(
                h -> get(AUTHORING + "entries.rol").param("weblog", h),
                h -> get(AUTHORING + "comments.rol").param("weblog", h),
                h -> get(AUTHORING + "categories.rol").param("weblog", h))) {
            MvcResult unknown = perform(screen.apply("ssanosuchblog"));
            MvcResult refused = perform(screen.apply(SITE));
            String uri = refused.getRequest().getRequestURI();

            assertEquals(302, unknown.getResponse().getStatus(), uri + ": today's unknown-weblog answer");
            assertEquals(unknown.getResponse().getStatus(), refused.getResponse().getStatus(), uri);
            assertEquals(unknown.getResponse().getRedirectedUrl(), refused.getResponse().getRedirectedUrl(), uri);
            assertNull(refused.getRequest().getAttribute("actionWeblog"),
                    uri + " never sees the site space");
        }
    }

    @Test
    void aBlogAuthorIsRefusedTheSitePages() throws Exception {
        siteSpace();
        signIn(author);

        for (String screen : List.of("pages.rol", "pageEdit.rol", "mediaFileView.rol")) {
            MvcResult result = perform(get(AUTHORING + screen).param("weblog", SITE));
            assertEquals(302, result.getResponse().getStatus(), screen);
            assertEquals("/pressroom-ui/access-denied.rol", result.getResponse().getRedirectedUrl(), screen);
        }
    }

    /**
     * Global Admin's per-person weblog grants name the weblog by a handle in
     * the form, outside the interceptor. A row on the site space would list it
     * among that person's blogs, so its handle is refused there exactly as one
     * naming no weblog, and no row is written.
     */
    @Test
    void noOneIsGrantedARoleOnTheSiteSpace() throws Exception {
        Weblog site = siteSpace();
        signIn(admin);
        Function<String, MockHttpServletRequestBuilder> grant = h -> post("/pressroom-ui/admin/userWeblogs!grant.rol")
                .param("userName", author.getUserName()).param("handle", h).param("level", "admin");

        MvcResult unknown = perform(grant.apply("ssanosuchblog"));
        MvcResult refused = perform(grant.apply(SITE));

        assertEquals(302, refused.getResponse().getStatus());
        assertEquals(unknown.getResponse().getRedirectedUrl(), refused.getResponse().getRedirectedUrl());
        assertEquals(unknown.getFlashMap().get("errors"), refused.getFlashMap().get("errors"));
        // This context registers no bundle, so a message reads as its code (BaseController.getText).
        assertEquals(List.of("userWeblogs.error.noSuchWeblog"), refused.getFlashMap().get("errors"),
                "refused as a weblog that does not exist");
        assertTrue(TestUtils.weblogger().getUserManager()
                .getWeblogPermissions(TestUtils.weblogger().getWeblogManager().getWeblog(site.getId())).isEmpty(),
                "no permission row on the site space");
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
