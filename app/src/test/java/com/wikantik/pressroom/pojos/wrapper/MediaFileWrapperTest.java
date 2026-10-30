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
package com.wikantik.pressroom.pojos.wrapper;

import java.sql.Timestamp;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that {@link MediaFileWrapper} is really a view of the {@link MediaFile}
 * it wraps: every accessor is asserted against a distinct value so an
 * accessor wired to the wrong field, or hard-coded to a constant, cannot pass
 * by coincidence (see the analogous rationale in {@code WeblogEntryWrapperTest}).
 */
class MediaFileWrapperTest {

    private Weblog weblog;
    private MediaFile pojo;
    private URLStrategy urls;
    private Weblogger weblogger;
    private MediaFileWrapper wrapper;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");

        pojo = new MediaFile();
        pojo.setWeblog(weblog);
        pojo.setName("sunset.jpg");
        pojo.setDescription("A sunset");
        pojo.setContentType("image/jpeg");
        pojo.setWidth(1200);
        pojo.setHeight(800);
        pojo.setDateUploaded(Timestamp.valueOf("2024-03-09 15:30:00"));
        pojo.setLastUpdated(Timestamp.valueOf("2024-03-10 09:00:00"));
        pojo.setBlurhash("LKO2?U%2Tw=w]~RBVZRi};RPxuwH");
        pojo.setExifCamera("Canon EOS R5");
        pojo.setExifLens("RF100-500mm F4.5-7.1 L IS USM");
        pojo.setExifExposure("1/1000 sec");
        pojo.setExifAperture("f/5.6");
        pojo.setExifIso(400);
        pojo.setExifFocalLength("400 mm");
        pojo.setExifTaken(Timestamp.valueOf("2024-06-15 10:30:00"));
        pojo.setGpsLatitude(37.8199);
        pojo.setGpsLongitude(-122.4783);
        pojo.setSortOrder(7);
        pojo.setFocalX(0.31);
        pojo.setFocalY(0.62);

        urls = mock(URLStrategy.class);
        weblogger = mock(Weblogger.class);
        wrapper = MediaFileWrapper.wrap(pojo, urls, weblogger);
    }

    @Test
    void wrappingNullGivesNull() {
        assertNull(MediaFileWrapper.wrap(null, urls, weblogger),
                "Templates test media files for presence before dereferencing them");
    }

    @Test
    void scalarAccessorsReportTheWrappedPojosOwnValues() {
        assertEquals(pojo.getId(), wrapper.getId());
        assertEquals("sunset.jpg", wrapper.getName());
        assertEquals("A sunset", wrapper.getDescription());
        assertEquals("image/jpeg", wrapper.getContentType());
        assertEquals(1200, wrapper.getWidth());
        assertEquals(800, wrapper.getHeight());
        assertEquals(Timestamp.valueOf("2024-03-09 15:30:00"), wrapper.getDateUploaded());
        assertEquals(Timestamp.valueOf("2024-03-10 09:00:00"), wrapper.getLastUpdated());
        assertEquals("LKO2?U%2Tw=w]~RBVZRi};RPxuwH", wrapper.getBlurhash());
    }

    @Test
    void exifAccessorsReportTheWrappedPojosOwnValues() {
        assertEquals("Canon EOS R5", wrapper.getExifCamera());
        assertEquals("RF100-500mm F4.5-7.1 L IS USM", wrapper.getExifLens());
        assertEquals("1/1000 sec", wrapper.getExifExposure());
        assertEquals("f/5.6", wrapper.getExifAperture());
        assertEquals(Integer.valueOf(400), wrapper.getExifIso());
        assertEquals("400 mm", wrapper.getExifFocalLength());
        assertEquals(Timestamp.valueOf("2024-06-15 10:30:00"), wrapper.getExifTaken());
        assertEquals(37.8199, wrapper.getGpsLatitude(), 0.0001);
        assertEquals(-122.4783, wrapper.getGpsLongitude(), 0.0001);
    }

    @Test
    void gpsAccessorsAreNullWhenTheUnderlyingFieldsAreNull() {
        pojo.setGpsLatitude(null);
        pojo.setGpsLongitude(null);

        assertNull(wrapper.getGpsLatitude());
        assertNull(wrapper.getGpsLongitude());
    }

    @Test
    void galleryAccessorsReportTheWrappedPojosOwnValues() {
        assertEquals(Integer.valueOf(7), wrapper.getSortOrder());
        assertEquals(0.31, wrapper.getFocalX(), 0.0001);
        assertEquals(0.62, wrapper.getFocalY(), 0.0001);
    }

    @Test
    void galleryAccessorsAreNullWhenTheUnderlyingFieldsAreNull() {
        pojo.setSortOrder(null);
        pojo.setFocalX(null);
        pojo.setFocalY(null);

        assertNull(wrapper.getSortOrder(), "Uncurated files carry no sort order");
        assertNull(wrapper.getFocalX(), "Null focal point means center");
        assertNull(wrapper.getFocalY(), "Null focal point means center");
    }

    @Test
    void freeTextAccessorsRunThroughTheSanitiser() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            pojo.setName("Name <script>alert(1)</script>");
            pojo.setDescription("Desc <script>alert(1)</script>");
            pojo.setExifCamera("Camera <script>alert(1)</script>");
            pojo.setExifLens("Lens <script>alert(1)</script>");
            pojo.setAltText("Alt <script>alert(1)</script>");

            assertFalse(wrapper.getName().contains("<script>"),
                    "EXIF/name/description fields originate in attacker-controlled file "
                            + "metadata, not just user input, and must be sanitised before a "
                            + "theme renders them");
            assertFalse(wrapper.getDescription().contains("<script>"));
            assertFalse(wrapper.getExifCamera().contains("<script>"));
            assertFalse(wrapper.getExifLens().contains("<script>"));
            assertFalse(wrapper.getAltText().contains("<script>"),
                    "altText is author-entered free text stored raw (W4), the same as "
                            + "description, and must be sanitised the same way before a theme "
                            + "emits it into the alt attribute");

            assertTrue(pojo.getName().contains("<script>"),
                    "Precondition: the underlying pojo still holds the raw value, so the "
                            + "cleaning demonstrably happens in the wrapper");
            assertTrue(pojo.getAltText().contains("<script>"),
                    "Precondition: the underlying pojo still holds the raw altText value");
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    private static final String PERMALINK = "http://example.com/pressroom/testblog/media-resources/abc123";

    private void withMockedPermalink(Runnable body) {
        // The strategy the wrapper was GIVEN is the only source of its urls
        // (spec Decision 6) -- no static locator is consulted.
        when(urls.getMediaFileURL(weblog, pojo.getId(), true)).thenReturn(PERMALINK);
        body.run();
    }

    @Test
    void urlsComeFromTheInjectedStrategy() {
        when(urls.getMediaFileURL(weblog, pojo.getId(), true)).thenReturn(PERMALINK);
        when(urls.getMediaFileThumbnailURL(weblog, pojo.getId(), true)).thenReturn(PERMALINK + "?t=true");

        assertEquals(PERMALINK, wrapper.getPermalink(),
                "a theme preview hands the wrapper a preview-aware strategy; the wrapper "
                        + "must build its urls from that, not from the production strategy");
        assertEquals(PERMALINK + "?t=true", wrapper.getThumbnailURL());
    }

    @Test
    void theCreatorIsResolvedThroughTheInjectedTierAndDegradesToNull() throws Exception {
        pojo.setCreatorUserName("alice");
        User alice = new User();
        alice.setUserName("alice");
        alice.setScreenName("Alice A");
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);

        assertEquals("Alice A", wrapper.getCreator().getScreenName(),
                "the uploader must be reachable, wrapped, through the tier the wrapper was given");

        when(users.getUserByUserName("alice")).thenThrow(new WebloggerException("gone"));
        assertNull(wrapper.getCreator(),
                "a creator that no longer resolves must not take the page down");
    }

    @Test
    void urlAppendsTheWidthQueryParameterForALadderWidth() {
        withMockedPermalink(() -> {
            assertEquals(PERMALINK + "?w=480", wrapper.url(480),
                    "480 is one of RenditionSupport's ladder widths, so the srcset URL for "
                            + "it must carry ?w=480 for the servlet to serve that rung");
            assertEquals(PERMALINK + "?w=960", wrapper.url(960));
        });
    }

    @Test
    void urlFallsBackToThePlainPermalinkForANonLadderWidth() {
        withMockedPermalink(() ->
                assertEquals(PERMALINK, wrapper.url(999),
                        "999 is not a rendition width Pressroom ever generates; emitting ?w=999 "
                                + "would be a dead link the servlet always 404-falls-back on, so "
                                + "the wrapper must not emit it at all"));
    }

    @Test
    void webpUrlMatchesTheSameUrlContentNegotiationHandlesTheFormat() {
        // MediaResourceServlet negotiates WebP via the Accept header on this
        // same URL rather than a distinct path -- see the javadoc on webpUrl().
        withMockedPermalink(() -> assertEquals(wrapper.url(480), wrapper.webpUrl(480)));
    }

    @Test
    void renditionEligibilityFollowsTheContentType() {
        // #showSeoHead only declares rendition (?w=) URLs and their scaled
        // dimensions when a rendition can actually exist; for gif/bmp the
        // servlet would silently serve the full-size original at that URL.
        assertTrue(wrapper.isRenditionEligible(),
                "the fixture is image/jpeg, which the ladder covers");
        pojo.setContentType("image/gif");
        assertFalse(wrapper.isRenditionEligible(),
                "gif never gets ladder renditions");
        pojo.setContentType(null);
        assertFalse(wrapper.isRenditionEligible());
    }

    @Test
    void srcsetClimbsTheLadderBelowTheOriginalAndEndsOnTheOriginal() {
        withMockedPermalink(() -> {
            // 1200w original: 480 and 960 rungs exist; 1600/2400 were never
            // generated (renditions are never upscaled) so they must not be
            // offered; the original itself is the largest candidate.
            assertEquals(PERMALINK + "?w=480 480w, " + PERMALINK + "?w=960 960w, "
                            + PERMALINK + " 1200w",
                    wrapper.getSrcset());
        });
    }

    @Test
    void srcsetIsNullForFormatsOutsideTheLadder() {
        pojo.setContentType("image/gif");
        withMockedPermalink(() ->
                assertNull(wrapper.getSrcset(),
                        "for gif every ?w= URL silently serves the full-size original, "
                                + "so offering a srcset (or a webp <source>) would lie to "
                                + "the browser -- gif/bmp get a plain <img>"));
    }

    @Test
    void srcsetIsNullWithoutWidthMetadata() {
        pojo.setWidth(0);
        withMockedPermalink(() ->
                assertNull(wrapper.getSrcset(),
                        "pre-ladder uploads have no width: we cannot know which rungs "
                                + "exist, and an honest plain src always renders"));
    }

    @Test
    void averageColorDecodesTheBlurhashDcComponent() {
        // Fixture hash "LKO2?U%2Tw=w]~RBVZRi};RPxuwH": DC chars "O2?U"
        // = ((24*83 + 2)*83 + 73)*83 + 30 = 13742755 = 0xd1b2a3.
        assertEquals("#d1b2a3", wrapper.getAverageColor());

        pojo.setBlurhash(null);
        assertNull(wrapper.getAverageColor(),
                "no blurhash (pre-pipeline upload) means no placeholder color");
    }

    @Test
    void objectPositionFormatsTheFocalPointAsStableCssPercentages() {
        assertEquals(0.31, wrapper.getFocalX());
        assertEquals(0.62, wrapper.getFocalY());
        assertEquals("31% 62%", wrapper.getObjectPosition());

        // double arithmetic noise (0.456 * 100 != 45.6 exactly) must never
        // leak into emitted CSS
        pojo.setFocalX(0.456);
        pojo.setFocalY(0.5);
        assertEquals("45.6% 50%", wrapper.getObjectPosition());

        pojo.setFocalX(0.0);
        pojo.setFocalY(1.0);
        assertEquals("0% 100%", wrapper.getObjectPosition());

        // out-of-range stored values (bad data) are clamped, not emitted
        pojo.setFocalX(1.5);
        pojo.setFocalY(-0.5);
        assertEquals("100% 0%", wrapper.getObjectPosition());
    }

    @Test
    void objectPositionIsNullUnlessBothCoordinatesAreSet() {
        pojo.setFocalX(null);
        assertNull(wrapper.getObjectPosition(),
                "half a focal point positions nothing; templates must fall back to defaults");

        pojo.setFocalX(0.4);
        pojo.setFocalY(null);
        assertNull(wrapper.getObjectPosition());
    }

    @Test
    void thePojoEscapeHatchReturnsTheVeryObjectThatWasWrapped() {
        assertSame(pojo, wrapper.getPojo(),
                "Rendering internals unwrap through getPojo(); handing back a copy would "
                        + "silently drop any state they then set on it");
    }
}
