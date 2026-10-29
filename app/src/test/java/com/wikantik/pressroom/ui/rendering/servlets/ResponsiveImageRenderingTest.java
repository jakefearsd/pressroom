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
 * Renders real themes through the real PageServlet and asserts on the
 * {@code #showResponsiveImage} macro: the featured image becomes a
 * {@code <picture>} whose srcset climbs the rendition ladder, a hero on the
 * permalink page and a linked card image on entry-list pages; formats outside
 * the ladder (gif) degrade to a plain honest {@code <img>}; and entries
 * without a featured image render exactly as they always did.
 *
 * <p>Runs on the travel theme: journal (the default fixture theme) is a
 * reading-first design that never renders featured images, so these macro
 * assertions need a theme whose day template actually calls the macro --
 * travel's hero/card slots are that theme-side wiring.
 */
class ResponsiveImageRenderingTest {

    private static final String HANDLE = "responsiverenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("responsiverenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme("travel");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private WeblogEntry entryWithFeaturedImage(String anchor, String imageId)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setFeaturedImageId(imageId);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /**
     * Persists a content-backed media file from a generated blank image --
     * the bundled hawk.jpg is always jpeg, which can never exercise the
     * outside-the-ladder degradation path.
     */
    private MediaFile setupGeneratedImage(String name, String contentType,
            String format, int width, int height) throws Exception {
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory root = mgr.getDefaultMediaFileDirectory(managed);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes),
                "no ImageIO writer for format " + format);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(root);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new PressroomMessages());
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    // ------------------------------------------------------------------ hero

    @Test
    void thePermalinkShowsTheFeaturedImageAsAResponsiveHero() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("hero-entry", imageId);

        String body = render("/" + HANDLE + "/entry/hero-entry");

        String imageUrl = BASE + "/mediaresource/" + imageId;
        // hawk.jpg is 500x373: the 480 rung exists, the original tops the set.
        String srcset = imageUrl + "?w=480 480w, " + imageUrl + " 500w";
        assertTrue(body.contains("<picture>"), body);
        assertTrue(body.contains(
                "<source type=\"image/webp\" srcset=\"" + srcset + "\" sizes=\"100vw\">"),
                "webp-capable browsers get an early pick of the same URLs:\n" + body);
        assertTrue(body.contains("<img src=\"" + imageUrl + "\" srcset=\"" + srcset
                        + "\" sizes=\"100vw\""),
                "the img itself must carry the ladder srcset:\n" + body);
        assertTrue(body.contains(" width=\"500\" height=\"373\""),
                "intrinsic dimensions must be declared to prevent layout shift:\n" + body);
        assertTrue(body.contains(" loading=\"lazy\" decoding=\"async\""), body);
        assertTrue(body.contains(" class=\"tg-hero-img\""),
                "the permalink slot is the full-width hero:\n" + body);
        assertTrue(body.contains(" data-blurhash=\""),
                "fresh uploads carry a blurhash for client-side blur-up:\n" + body);
        assertTrue(body.contains(" style=\"background-color:#"),
                "the blurhash average color is the JS-free placeholder:\n" + body);
    }

    @Test
    void entryListPagesShowALinkedFeaturedThumb() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "thumb-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("thumb-entry", imageId);

        String body = render("/" + HANDLE);

        assertTrue(body.contains(" class=\"tg-card-img\""),
                "list pages show the card image, not the hero:\n" + body);
        assertFalse(body.contains("tg-hero-img"), body);
        assertTrue(body.contains(" sizes=\"(max-width: 700px) 100vw, 33vw\""),
                "the card image declares its rendered width so the browser picks a "
                        + "small rung:\n" + body);
        assertTrue(body.contains(
                "<a class=\"tg-card-media\" href=\"" + BASE + "/entry/thumb-entry\">"),
                "the card image must link to the entry:\n" + body);
    }

    // ------------------------------------------------- honest degradation

    @Test
    void aGifFeaturedImageDegradesToAPlainImg() throws Exception {
        // The ladder only covers jpeg/png; for a gif every ?w= URL silently
        // serves the full-size original, so no srcset and no webp <source>
        // may be offered.
        MediaFile image = setupGeneratedImage("plain.gif", "image/gif", "gif", 600, 400);
        entryWithFeaturedImage("gif-entry", image.getId());

        String body = render("/" + HANDLE + "/entry/gif-entry");

        String imageUrl = BASE + "/mediaresource/" + image.getId();
        assertTrue(body.contains("<img src=\"" + imageUrl + "\""), body);
        assertFalse(body.contains("srcset"), body);
        assertFalse(body.contains("image/webp"), body);
        assertTrue(body.contains(" width=\"600\" height=\"400\""),
                "known dimensions still prevent layout shift:\n" + body);
    }

    @Test
    void anEntryWithoutAFeaturedImageRendersExactlyAsBefore() throws Exception {
        TestUtils.setupWeblogEntry("bare-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/bare-entry");

        assertFalse(body.contains("<picture>"), body);
        assertFalse(body.contains("tg-hero-img"), body);
        assertFalse(body.contains("tg-card-img"), body);
    }

    // ---------------------------------------------------------- focal point

    @Test
    void aFocalPointBecomesObjectPositionOnTheHero() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "focal-image");
        String imageId = image.getId();
        TestUtils.endSession(true);

        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        MediaFile managed = mgr.getMediaFile(imageId);
        managed.setFocalX(0.3);
        managed.setFocalY(0.7);
        mgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed);
        TestUtils.endSession(true);

        entryWithFeaturedImage("focal-entry", imageId);

        String body = render("/" + HANDLE + "/entry/focal-entry");

        // theme-side inline style is allowed (only entry content is
        // sanitized), and it must merge with the blurhash average color
        // rather than replace it
        assertTrue(body.contains(";object-position:30% 70%\""),
                "the focal point must be emitted as object-position:\n" + body);
        assertTrue(body.contains(" style=\"background-color:#"),
                "the average-color placeholder must survive alongside it:\n" + body);
    }

    @Test
    void withoutAFocalPointNoObjectPositionIsEmitted() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "nofocal-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("nofocal-entry", imageId);

        String body = render("/" + HANDLE + "/entry/nofocal-entry");

        assertFalse(body.contains("object-position"),
                "files without focal points must render exactly as today:\n" + body);
        assertTrue(body.contains(" style=\"background-color:#"), body);
    }

    // ------------------------------------------------------ other themes

    @Test
    void theFrontpageThemeStillRendersWithTheNewCardMarkup() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme("frontpage");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        // The macro call in _entry.vm must at least parse: a Velocity error
        // would leak the raw directive or reference text into the page.
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
    }
}
