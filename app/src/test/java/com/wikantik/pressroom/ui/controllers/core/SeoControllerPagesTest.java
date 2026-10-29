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
import java.time.format.DateTimeFormatter;
import java.time.Instant;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.util.URLUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pages sit alongside entries in the per-weblog sitemap. Same rules as
 * entries: only {@code PUBLISHED}, {@code noindex} still excludes, and
 * {@code lastmod} reflects the page's own {@code updated} timestamp -- see
 * {@link SeoControllerTest} for the entry-side coverage this mirrors.
 */
class SeoControllerPagesTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/pressroom";

    private SeoController controller;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");

        user = TestUtils.setupUser("seoPagesUser");
        weblog = TestUtils.setupWeblog("seopagesblog", user);
        TestUtils.endSession(true);

        controller = new SeoController();
        inject(controller, "weblogger", TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogPageManager pageManager() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private WeblogPage savePage(String slug, WeblogPage.PubStatus status, boolean noindex)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        page.setNoindex(noindex);
        pageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return pageManager().getPageBySlug(TestUtils.getManagedWebsite(weblog), slug);
    }

    @Test
    void weblogSitemapListsPublishedPagesExcludesDraftAndNoindexAndCarriesLastmod()
            throws Exception {
        WeblogPage published = savePage("about", WeblogPage.PubStatus.PUBLISHED, false);
        WeblogPage draft = savePage("secret", WeblogPage.PubStatus.DRAFT, false);
        WeblogPage noindexed = savePage("hidden", WeblogPage.PubStatus.PUBLISHED, true);

        ResponseEntity<String> response = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);

        String publishedUrl = ABSOLUTE_CONTEXT + "/" + weblog.getHandle() + "/"
                + URLUtilities.encode(published.getSlug());
        assertTrue(body.contains("<loc>" + publishedUrl + "</loc>"),
                "The published page's absolute URL must be listed:\n" + body);

        assertFalse(body.contains(draft.getSlug()),
                "A DRAFT page must not leak into the sitemap:\n" + body);
        assertFalse(body.contains(noindexed.getSlug()),
                "A noindex=true page must be excluded from the sitemap:\n" + body);

        String expectedLastmod = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochSecond(published.getUpdated().getTime() / 1000));
        assertTrue(body.contains("<loc>" + publishedUrl + "</loc>\n"
                        + "    <lastmod>" + expectedLastmod + "</lastmod>"),
                "The page's own updated timestamp must be the lastmod:\n" + body);
    }

    /**
     * The sitemap {@code <loc>} must be the same URL the site's own nav
     * emits for the page ({@link com.wikantik.pressroom.ui.rendering
     * .model.URLModel#staticPage}), not the raw, unencoded slug -- a slug
     * containing a space would otherwise produce a {@code <loc>} that does
     * not resolve.
     */
    @Test
    void weblogSitemapEncodesThePageSlugTheSameWayNavigationDoes() throws Exception {
        WeblogPage page = savePage("my page", WeblogPage.PubStatus.PUBLISHED, false);

        String body = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest()).getBody();

        assertNotNull(body);
        String expectedUrl = ABSOLUTE_CONTEXT + "/" + weblog.getHandle() + "/"
                + URLUtilities.encode(page.getSlug());
        assertTrue(body.contains("<loc>" + expectedUrl + "</loc>"),
                "The page URL must be encoded the same way URLModel.staticPage encodes it:\n"
                        + body);
        assertFalse(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/" + weblog.getHandle() + "/"
                        + page.getSlug() + "</loc>"),
                "The raw, unencoded slug must not appear as a loc:\n" + body);
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
