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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code [map]}, {@code [cta]} and {@code [faq]} entries through the
 * real PageServlet, real theme, shortcode expansion AND the entry-content
 * sanitizer: the travel-map data payload, the UTM-tagged CTA anchor and the
 * FAQ definition list must all reach the reader intact; script payloads
 * smuggled through pin labels or answers must not; and everything that
 * cannot be shown -- an invalid CTA URL, a private auto-map directory --
 * degrades to the SPI's visible-failure signal without leaking metadata.
 */
class TravelShortcodeRenderingTest {

    private static final String HANDLE = "travelrenderblog";

    /**
     * 127.0.0.1, not the localhost that RenderingTestSupport installs:
     * entry content is sanitized, and HTMLSanitizer validates anchor URLs
     * with commons-validator's UrlValidator, which rejects the TLD-less
     * "localhost". With a localhost base the CTA anchor would be stripped
     * and this test would prove nothing (see GalleryRenderingTest).
     */
    private static final String CONTEXT = "http://127.0.0.1:8080/roller";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        WebloggerRuntimeConfig.setAbsoluteContextURL(CONTEXT);
        user = TestUtils.setupUser("travelrenderuser");
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
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
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

    /** Persists a content-backed jpeg with GPS coordinates into the directory. */
    private MediaFile setupGpsImage(String name, MediaFileDirectory directory,
            double lat, double lng) throws Exception {
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);

        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", bytes), "no ImageIO jpeg writer");

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(directory);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new RollerMessages());
        TestUtils.weblogger().flush();

        // create-time EXIF extraction honours uploads.exif.stripGps (default
        // true) and this synthetic jpeg has no EXIF anyway, so the GPS
        // coordinates go on afterwards, the way an admin edit would
        MediaFile stored = mgr.getMediaFile(mediaFile.getId());
        stored.setGpsLatitude(lat);
        stored.setGpsLongitude(lng);
        mgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), stored);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    // -------------------------------------------------------------- happy path

    @Test
    void aTravelEntryRendersMapCtaAndFaqThroughTheSanitizer() throws Exception {
        entryWithText("iceland-guide",
                "intro\n"
                        + "[map route=\"true\" zoom=\"7\"]\n"
                        + "[pin lat=\"64.1466\" lng=\"-21.9426\" label=\"Reykjavik\"]\n"
                        + "[pin lat=\"63.4053\" lng=\"-19.0755\" label=\"Vik\"]\n"
                        + "[/map]\n"
                        + "[cta href=\"https://booking.example.com/cabin?adults=2\""
                        + " label=\"Book this cabin\" note=\"From EUR 120/night\"]\n"
                        + "[faq]\n[q]Best month?[/q]\n[a]June, by <b>far</b>.[/a]\n[/faq]\n"
                        + "outro");

        String body = render("/" + HANDLE + "/entry/iceland-guide");

        // The map div and its single-line JSON payload. The sanitizer may
        // re-encode the entity form (&quot; becomes &#34;), so compare the
        // decoded payload -- which is what the map script's JSON.parse sees.
        assertTrue(decoded(body).replace("&#34;", "\"").contains(
                "<div class=\"travel-map\" data-pins=\""
                + "[{\"lat\":64.1466,\"lng\":-21.9426,"
                + "\"label\":\"Reykjavik\"},"
                + "{\"lat\":63.4053,\"lng\":-19.0755,"
                + "\"label\":\"Vik\"}]\""), body);
        assertTrue(body.contains("data-zoom=\"7\""), body);
        assertTrue(body.contains("data-route=\"true\""), body);

        // the CTA anchor: UTM-tagged href, rel/target, label and note spans
        assertTrue(decoded(body).contains("href=\"https://booking.example.com/cabin?adults=2"
                + "&utm_source=" + HANDLE + "&utm_medium=blog"
                + "&utm_campaign=iceland-guide\""),
                "the campaign-tagged href must decode back to what the handler built:\n" + body);
        // The sanitizer keeps the new tab and appends noreferrer to the
        // handler's rel; the href's UTM tags carry the attribution instead.
        assertTrue(body.contains("rel=\"nofollow sponsored noopener noreferrer\""), body);
        assertTrue(body.contains("target=\"_blank\""), body);
        assertTrue(body.contains("<span class=\"cta-label\">Book this cabin</span>"), body);
        assertTrue(body.contains("<span class=\"cta-note\">From EUR 120/night</span>"), body);

        // the FAQ definition list, answer markup intact
        assertTrue(body.contains("<dl class=\"faq\">"), body);
        assertTrue(body.contains("<dt>Best month?</dt>"), body);
        assertTrue(body.contains("<dd>June, by <b>far</b>.</dd>"), body);

        // the prose around the shortcodes is intact, the shortcodes are gone
        assertTrue(body.contains("intro"), body);
        assertTrue(body.contains("outro"), body);
        assertFalse(body.contains("[map"), body);
        assertFalse(body.contains("[pin"), body);
        assertFalse(body.contains("[cta"), body);
        assertFalse(body.contains("[faq"), body);
    }

    @Test
    void anAutoMapPinsTheDirectorysGpsPhotos() throws Exception {
        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory album = mgr.createMediaFileDirectory(
                TestUtils.getManagedWebsite(weblog), "iceland");
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        setupGpsImage("glacier.jpg",
                mgr.getMediaFileDirectoryByName(TestUtils.getManagedWebsite(weblog),
                        "iceland"), 64.0784, -16.2306);
        entryWithText("auto-map-entry", "[map auto=\"iceland\"]");

        String body = render("/" + HANDLE + "/entry/auto-map-entry");

        assertTrue(decoded(body).replace("&#34;", "\"").contains(
                        "<div class=\"travel-map\" data-pins=\""
                        + "[{\"lat\":64.0784,\"lng\":-16.2306,"
                        + "\"label\":\"glacier.jpg\"}]\""),
                "the GPS-bearing photo must become a pin:\n" + body);
        assertFalse(body.contains("[map"), body);
    }

    // -------------------------------------------------------- leak assertions

    @Test
    void scriptPayloadsInPinLabelsAndAnswersNeverReachTheReader() throws Exception {
        entryWithText("hostile-entry",
                "[map][pin lat=\"1\" lng=\"2\" label='\"><script>alert(1)</script>'][/map]\n"
                        + "[faq][q]q?[/q][a]\"><script>alert(2)</script>[/a][/faq]");

        String body = render("/" + HANDLE + "/entry/hostile-entry");

        assertFalse(body.contains("<script>alert"),
                "no smuggled script may reach the reader:\n" + body);
        assertTrue(body.contains("<div class=\"travel-map\""),
                "the map itself must still render, payload defanged:\n" + body);
        assertTrue(body.contains("<dl class=\"faq\">"),
                "the FAQ itself must still render, answer defanged:\n" + body);
    }

    @Test
    void aPrivateDirectoryAutoMapLeaksNoCoordinates() throws Exception {
        MediaFileManager mgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory secret = mgr.createMediaFileDirectory(
                TestUtils.getManagedWebsite(weblog), "secret");
        secret.setPrivate(true);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        MediaFileDirectory managedSecret = mgr.getMediaFileDirectoryByName(
                TestUtils.getManagedWebsite(weblog), "secret");
        assertTrue(managedSecret.isPrivate(), "the private flag must persist");
        setupGpsImage("home.jpg", managedSecret, 48.13725, 11.57542);
        entryWithText("private-map-entry", "[map auto=\"secret\"]");

        String body = render("/" + HANDLE + "/entry/private-map-entry");

        // the SPI's visible-failure signal: the shortcode text stays as
        // written and the private photos' coordinates never leave the server
        assertTrue(decoded(body).contains("[map auto=\"secret\"]"),
                "the author must see their shortcode, not silence:\n" + body);
        // the container, not the bare class name: #showMapAssets legitimately
        // names .travel-map in the head's stylesheet and lazy-init guard on
        // every page, map or no map
        assertFalse(body.contains("<div class=\"travel-map\""), body);
        assertFalse(body.contains("48.13725"),
                "a private photo's latitude is location metadata and must "
                        + "not leak:\n" + body);
        assertFalse(body.contains("11.57542"), body);
        assertFalse(body.contains("home.jpg"), body);
    }

    @Test
    void anInvalidCtaHrefLeavesTheShortcodeTextVisible() throws Exception {
        entryWithText("bad-cta-entry", "[cta href=\"/book\" label=\"Book now\"]");

        String body = render("/" + HANDLE + "/entry/bad-cta-entry");

        // the handler refuses before the sanitizer can silently eat the
        // anchor: the author sees their shortcode instead of an unlinked label
        assertTrue(decoded(body).contains("[cta href=\"/book\" label=\"Book now\"]"), body);
        assertFalse(body.contains("cta-card"), body);
    }
}
