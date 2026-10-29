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
package com.wikantik.pressroom.ui.rendering.servlets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code [gallery]} entries through the real PageServlet, real theme,
 * shortcode expansion AND the entry-content sanitizer: the justified grid,
 * its lightbox data payload, and the theme-head grid styles must all reach
 * the reader intact, while private and unknown directories degrade to the
 * SPI's visible-failure signal (the shortcode text stays as written).
 */
class GalleryRenderingTest {

    private static final String HANDLE = "galleryrenderblog";

    /**
     * 127.0.0.1, not the localhost that RenderingTestSupport installs:
     * entry content is sanitized, and HTMLSanitizer validates anchor/img
     * URLs with commons-validator's UrlValidator, which rejects the
     * TLD-less "localhost" (production runs on a real domain and the
     * browser ITs already run against http://127.0.0.1 for the same
     * reason). With a localhost base every gallery anchor and img would be
     * stripped and this test would prove nothing.
     */
    private static final String CONTEXT = "http://127.0.0.1:8080/roller";
    private static final String BASE = CONTEXT + "/" + HANDLE;

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        WebloggerRuntimeConfig.setAbsoluteContextURL(CONTEXT);
        user = TestUtils.setupUser("galleryrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        // restore what ensureRenderingRuntime installed for the other tests
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/roller");
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    /**
     * The rendered page with HTML entities decoded.
     *
     * <p>The sanitizer entity-encodes characters inside attribute values --
     * {@code ?w=480} is serialized as {@code ?w&#61;480}, and a quote inside
     * body text as {@code &#34;}. A browser decodes those before it parses a
     * srcset or resolves a URL, so the page works; assertions written against
     * the author's literal text would not. Decoding first keeps the assertion
     * about meaning rather than about the serializer's choices.
     */
    private static String decoded(String body) {
        return org.apache.commons.text.StringEscapeUtils.unescapeHtml4(body);
    }

    private WeblogEntry entryWithText(String anchor, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setText(text);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** Persists a content-backed jpeg into the given directory (null = default). */
    private MediaFile setupImageInDirectory(String name, MediaFileDirectory directory,
            int width, int height) throws Exception {
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory target = directory != null
                ? directory : mgr.getDefaultMediaFileDirectory(managed);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", bytes), "no ImageIO jpeg writer");

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(target);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new PressroomMessages());
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    // ------------------------------------------------------------------- grid

    @Test
    void aGalleryEntryRendersTheJustifiedGridThroughTheSanitizer() throws Exception {
        MediaFile first = setupImageInDirectory("gallery-a.jpg", null, 800, 600);
        MediaFile second = setupImageInDirectory("gallery-b.jpg", null, 1200, 800);
        entryWithText("gallery-entry", "before [gallery dir=\"default\"] after");

        String body = render("/" + HANDLE + "/entry/gallery-entry");

        // the grid and both figures survived the sanitizer round-trip
        assertTrue(body.contains("<div class=\"jgrid\">"), body);
        assertTrue(body.contains("<figure class=\"ar-135\">"),
                "800x600 must pack with its aspect ratio:\n" + body);
        assertTrue(body.contains("<figure class=\"ar-150\">"),
                "1200x800 must pack with its aspect ratio:\n" + body);

        // the lightbox anchors: absolute original + full-size dimensions
        String firstUrl = BASE + "/mediaresource/" + first.getId();
        String secondUrl = BASE + "/mediaresource/" + second.getId();
        assertTrue(body.contains("<a href=\"" + firstUrl + "\" data-pswp-width=\"800\""
                        + " data-pswp-height=\"600\""),
                "the anchor must carry the lightbox payload through the sanitizer:\n" + body);
        assertTrue(body.contains("<a href=\"" + secondUrl + "\" data-pswp-width=\"1200\""),
                body);

        // the grid images: ladder srcset, lazy, async, blur-up placeholder
        // The sanitizer entity-encodes "=" and pads the srcset commas, both of
        // which are spec-legal and decoded by the browser before it picks a
        // candidate. Assert the candidates, not the serializer's spacing.
        String plain = decoded(body).replace(" , ", ", ");
        assertTrue(plain.contains("<img src=\"" + firstUrl + "\" srcset=\""
                        + firstUrl + "?w=480 480w, " + firstUrl + " 800w\""),
                "the ladder srcset must survive the sanitizer:\n" + plain);
        assertTrue(body.contains("loading=\"lazy\""), body);
        assertTrue(body.contains("decoding=\"async\""), body);
        assertTrue(body.contains("data-blurhash=\""),
                "fresh uploads carry a blurhash for the lightbox placeholder:\n" + body);

        // sortOrder is null for both: they follow name order, a before b
        assertTrue(body.indexOf(first.getId()) < body.indexOf(second.getId()),
                "unordered files must render in name order:\n" + body);

        // the entry text around the shortcode is intact, the shortcode is not
        assertTrue(body.contains("before "), body);
        assertTrue(body.contains(" after"), body);
        assertFalse(body.contains("[gallery"),
                "the shortcode text itself must never reach readers:\n" + body);
    }

    @Test
    void theThemeHeadShipsTheGridStylesWithoutLeakingTheMacro() throws Exception {
        entryWithText("style-entry", "no gallery here");

        String body = render("/" + HANDLE + "/entry/style-entry");

        assertTrue(body.contains(".jgrid { display: flex;"),
                "every theme head must emit the grid styles:\n" + body);
        assertTrue(body.contains("flex-grow: var(--ar)"), body);
        // the ResponsiveImageRenderingTest leak assertions, for this macro:
        // a Velocity error would leak the raw directive text into the page
        assertFalse(body.contains("#showGalleryGridStyles"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        // and an entry without a gallery renders no grid markup at all
        assertFalse(body.contains("<div class=\"jgrid\""), body);
    }

    // ------------------------------------------------------ refusal to render

    @Test
    void aPrivateDirectoryRendersNothingButTheShortcodeText() throws Exception {
        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory secret = mgr.createMediaFileDirectory(managed, "secret");
        secret.setPrivate(true);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        MediaFileDirectory managedSecret = mgr.getMediaFileDirectoryByName(
                TestUtils.getManagedWebsite(weblog), "secret");
        assertTrue(managedSecret.isPrivate(), "the private flag must persist");
        MediaFile hidden = setupImageInDirectory("hidden.jpg", managedSecret, 640, 480);
        entryWithText("private-entry", "[gallery dir=\"secret\"]");

        String body = render("/" + HANDLE + "/entry/private-entry");

        // the SPI's visible-failure signal: the shortcode text stays as
        // written (share-page rendering of private directories is T5's path)
        assertTrue(decoded(body).contains("[gallery dir=\"secret\"]"),
                "the author must see their shortcode, not silence:\n" + body);
        assertFalse(body.contains("<div class=\"jgrid\""), body);
        assertFalse(body.contains(hidden.getId()),
                "no URL of a private directory's file may leak:\n" + body);
    }

    @Test
    void anUnknownDirectoryLeavesTheShortcodeTextVisible() throws Exception {
        entryWithText("unknown-entry", "[gallery dir=\"no-such-album\"]");

        String body = render("/" + HANDLE + "/entry/unknown-entry");

        assertTrue(decoded(body).contains("[gallery dir=\"no-such-album\"]"),
                body);
        assertFalse(body.contains("<div class=\"jgrid\""), body);
    }
}
