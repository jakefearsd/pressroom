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
package com.wikantik.pressroom.ui.controllers.core;

import java.lang.reflect.Field;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a custom domain, {@code robots()} and {@code sitemapIndex()} describe
 * THAT weblog rather than the whole site -- see {@link SeoControllerTest} for
 * the site-wide coverage this complements.
 *
 * <p>The fixture carries two weblogs: {@code vhostblog}, which owns
 * {@code vhost.example.com}, and {@code plainblog}, which has no custom
 * domain. {@link VirtualHostRegistry} is a JVM-wide static cache, so it is
 * invalidated both after the domain is set and again in {@code @AfterEach} --
 * a domain set here must not leak into another test class.
 */
class SeoControllerVirtualHostTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/pressroom";

    private SeoController controller;
    private User user;
    private Weblog vhostBlog;
    private Weblog plainBlog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");

        user = TestUtils.setupUser("seoVhostUser");
        vhostBlog = TestUtils.setupWeblog("vhostblog", user);
        plainBlog = TestUtils.setupWeblog("plainblog", user);
        TestUtils.endSession(true);

        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog managed = TestUtils.getManagedWebsite(vhostBlog);
        managed.setCustomDomain("vhost.example.com");
        mgr.saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();

        controller = new SeoController();
        inject(controller, "weblogger", TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(vhostBlog.getId());
        TestUtils.teardownWeblog(plainBlog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        // VirtualHostRegistry is a JVM-wide static cache -- the domain set
        // here must not leak into another test class.
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
    }

    private MockHttpServletRequest onHost(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/robots.txt");
        request.addHeader("Host", host);
        return request;
    }

    /**
     * On a custom domain, robots.txt points at THAT weblog's sitemap -- and,
     * since this is one Tomcat context reached under many hostnames (this
     * fixture's setUp() pins the context to "/pressroom"), the sitemap url must
     * still carry the context path. The real {@code /sitemap.xml} route is
     * mapped under the same prefix; omitting it here would advertise a url
     * that 404s.
     */
    @Test
    void robotsOnACustomDomainAdvertisesTheWeblogSitemap() {
        String body = controller.robots(onHost("vhost.example.com")).getBody();
        assertTrue(body.contains("Sitemap: https://vhost.example.com/pressroom/sitemap.xml"),
                "robots.txt was: " + body);
    }

    /**
     * CHARACTERISATION: at the root context, robots.txt on a custom domain is
     * unchanged -- there is no prefix to add. The class's own setUp() pins a
     * non-root context by default, so this overrides it locally and restores
     * it afterward.
     */
    @Test
    void robotsOnACustomDomainAtTheRootContextHasNoPrefix() {
        WebloggerRuntimeConfig.setRelativeContextURL("");
        try {
            String body = controller.robots(onHost("vhost.example.com")).getBody();
            assertTrue(body.contains("Sitemap: https://vhost.example.com/sitemap.xml"),
                    "robots.txt was: " + body);
        } finally {
            WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        }
    }

    /** On a custom domain, /sitemap.xml IS the weblog's sitemap, not the index. */
    @Test
    void sitemapOnACustomDomainIsTheWeblogsOwnSitemap() {
        String xml = controller.sitemapIndex(onHost("vhost.example.com")).getBody();
        assertTrue(xml.contains("<urlset"), "expected a urlset, got: " + xml);
        assertFalse(xml.contains("<sitemapindex"));
        assertTrue(xml.contains("https://vhost.example.com/"));
    }

    /**
     * A sitemap index may only reference sitemaps on its own host, so a
     * custom-domain weblog must be dropped from the site index -- leaving it in
     * produces an index that is invalid for exactly the entries most wanted in
     * the crawl. Each such weblog is discovered through its own robots.txt.
     */
    @Test
    void theSiteIndexOmitsCustomDomainWeblogs() {
        String xml = controller.sitemapIndex(onHost("blog.example.com")).getBody();
        assertTrue(xml.contains("<sitemapindex"));
        assertFalse(xml.contains("sitemap-vhostblog.xml"),
                "a weblog with its own hostname must not appear in the site index");
    }

    /**
     * CHARACTERISATION: a weblog with no custom domain still appears in the
     * site index, on the site host, exactly as before. Expected to pass on
     * arrival.
     */
    @Test
    void theSiteHostIsUnchangedForWeblogsWithoutADomain() {
        String xml = controller.sitemapIndex(onHost("blog.example.com")).getBody();
        assertTrue(xml.contains("sitemap-plainblog.xml"));
    }

    /**
     * Sets {@code BaseController}'s protected {@code weblogger} field the way
     * Spring's autowiring would; this test package cannot see the field
     * directly and spring-test is not on the classpath.
     */
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + target.getClass());
    }
}
