/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one intentional behaviour change of the static-service-locator wave
 * (spec Decision 6): the URLs a <em>theme preview</em> emits through the
 * wrappers come from the preview-aware {@link URLStrategy} the preview
 * installs, not from the production strategy.
 *
 * <p>Before this change {@code WeblogWrapper.getURL()/getAbsoluteURL()},
 * {@code WeblogEntryWrapper.getPermalink()} and every
 * {@code MediaFileWrapper} URL accessor delegated to the pojo, which located
 * the <em>production</em> strategy statically -- so a preview rendered links
 * that left the preview and landed on the live weblog, exactly what
 * {@code PreviewURLStrategy}'s javadoc says it exists to prevent. The
 * wrappers already held the injected strategy (it is what
 * {@code WeblogWrapper.getStylesheet()} and {@code getIcon()} used); the URL
 * methods simply ignored it.
 *
 * <p>Two surfaces are asserted, chosen because they are the bundled
 * templates that actually emit those wrapper methods: a journal permalink's
 * {@code og:image} ({@code $seoImage.permalink} in {@code #showSeoHead}) and
 * the front-page theme's entry rows and weblog directory
 * ({@code $entry.permalink}, {@code $entry.website.URL},
 * {@code $blog.absoluteURL}). Every expected string is computed from the
 * {@link URLStrategy} the preview installs, never hardcoded.
 */
class PreviewUrlRenderingTest {

    private static final String PREVIEW_SERVLET = "/pressroom-ui/authoring/preview";
    private static final String FRONTPAGE_HANDLE_PROP = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED_PROP = "site.frontpage.weblog.aggregated";

    private final Map<String, String> originalProperties = new HashMap<>();

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;
    private MediaFile image;
    private User frontpageUser;
    private Weblog frontpageWeblog;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        suffix = Long.toString(System.nanoTime(), 36);
        user = TestUtils.setupUser("prevurluser" + suffix);
        weblog = TestUtils.setupWeblog("prevurl" + suffix, user);
        image = TestUtils.setupImageMediaFile(weblog, "prevurl-hawk.jpg");
        entry = TestUtils.setupWeblogEntry("prevurl-entry", weblog, user);
        entry.setFeaturedImageId(image.getId());
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (!originalProperties.isEmpty()) {
                PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
                Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
                originalProperties.forEach((name, value) -> config.get(name).setValue(value));
                pmgr.saveProperties(config);
                TestUtils.weblogger().flush();
                TestUtils.endSession(true);
            }
        } finally {
            if (frontpageWeblog != null) {
                TestUtils.teardownWeblog(frontpageWeblog.getId());
            }
            if (frontpageUser != null) {
                TestUtils.teardownUser(frontpageUser.getUserName());
            }
            TestUtils.teardownWeblog(weblog.getId());
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aPermalinkPreviewsOgImageIsThePreviewStrategysMediaUrl() throws Exception {
        URLStrategy preview = previewStrategy("journal");
        URLStrategy production = TestUtils.weblogger().getUrlStrategy();
        // hawk.jpg is 500px wide, below the 1600px og:image rendition cut-off,
        // so #showSeoHead takes the $seoImage.permalink branch
        String expected = preview.getMediaFileURL(weblog, image.getId(), true);
        String productionUrl = production.getMediaFileURL(weblog, image.getId(), true);
        assertFalse(expected.equals(productionUrl),
                "precondition: the preview strategy must shape media urls differently, "
                        + "or this test cannot tell the two apart");

        String body = preview("/" + weblog.getHandle() + "/entry/" + entry.getAnchor(), "journal");

        assertTrue(body.contains(ogImage(expected)),
                "og:image in a theme preview must be the preview strategy's url "
                        + expected + ":\n" + body);
        assertFalse(body.contains(ogImage(productionUrl)),
                "og:image in a theme preview must not be the production url "
                        + productionUrl);
    }

    @Test
    void aFrontPagePreviewLinksToPreviewShapedEntriesAndWeblogs() throws Exception {
        frontpageUser = TestUtils.setupUser("prevfrontuser" + suffix);
        frontpageWeblog = TestUtils.setupWeblog("prevfront" + suffix, frontpageUser);
        Weblog managed = TestUtils.getManagedWebsite(frontpageWeblog);
        managed.setEditorTheme("frontpage");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        setProperty(FRONTPAGE_HANDLE_PROP, frontpageWeblog.getHandle());
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");

        URLStrategy preview = previewStrategy("frontpage");
        Weblog managedEntryWeblog = TestUtils.getManagedWebsite(weblog);
        String expectedPermalink = preview.getWeblogEntryURL(
                managedEntryWeblog, null, entry.getAnchor(), true);
        String expectedRelativeWeblogUrl = preview.getWeblogURL(managedEntryWeblog, null, false);
        String expectedAbsoluteWeblogUrl = preview.getWeblogURL(managedEntryWeblog, null, true);
        String productionPermalink = TestUtils.weblogger().getUrlStrategy()
                .getWeblogEntryURL(managedEntryWeblog, null, entry.getAnchor(), true);
        assertFalse(expectedPermalink.equals(productionPermalink),
                "precondition: the preview strategy must shape entry urls differently");

        String body = preview("/" + frontpageWeblog.getHandle() + "/", "frontpage");

        assertTrue(body.contains(href(expectedPermalink)),
                "$entry.permalink on a front-page preview must be the preview strategy's "
                        + expectedPermalink + ":\n" + body);
        assertFalse(body.contains(href(productionPermalink)),
                "$entry.permalink on a front-page preview must not be the production url "
                        + productionPermalink);
        assertTrue(body.contains(href(expectedRelativeWeblogUrl)),
                "$entry.website.URL must be the preview strategy's " + expectedRelativeWeblogUrl
                        + ":\n" + body);
        assertTrue(body.contains(href(expectedAbsoluteWeblogUrl)),
                "$blog.absoluteURL must be the preview strategy's " + expectedAbsoluteWeblogUrl
                        + ":\n" + body);
    }

    // ---------------------------------------------------------------- helpers

    private static URLStrategy previewStrategy(String theme) {
        return TestUtils.weblogger().getUrlStrategy().getPreviewURLStrategy(theme);
    }

    private static String preview(String pathInfo, String theme) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PREVIEW_SERVLET, pathInfo);
        request.setParameter("theme", theme);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);
        assertEquals(200, response.getStatus(),
                "preview of " + pathInfo + ":\n" + response.getContentAsString());
        return response.getContentAsString();
    }

    private static String ogImage(String url) {
        return "<meta property=\"og:image\" content=\"" + StringEscapeUtils.escapeHtml4(url) + "\">";
    }

    private static String href(String url) {
        return "href=\"" + StringEscapeUtils.escapeHtml4(url) + "\"";
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
