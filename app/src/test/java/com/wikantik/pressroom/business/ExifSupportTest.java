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
package com.wikantik.pressroom.business;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExifSupport} against fixtures directly on the
 * classpath -- no database, no {@code MediaFileManager} -- the JPA-backed
 * end-to-end path (upload through the business layer, GPS-strip config,
 * blurhash interaction) lives in {@link MediaFileTest}.
 */
class ExifSupportTest {

    @Test
    void extractsCameraLensExposureApertureIsoFocalLengthAndTakenFromAFullExifBlock() throws Exception {
        ExifSupport.ExifData data;
        try (InputStream is = getClass().getResourceAsStream("/hawk-exif.jpg")) {
            data = ExifSupport.extract(is);
        }

        assertEquals("Canon EOS R5", data.camera);
        assertEquals("RF100-500mm F4.5-7.1 L IS USM", data.lens);
        assertNotNull(data.exposure);
        assertNotNull(data.aperture);
        assertEquals(400, data.iso);
        assertNotNull(data.focalLength);

        Date expectedTaken = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse("2024:06:15 10:30:00");
        assertEquals(expectedTaken.getTime(), data.taken.getTime());
    }

    @Test
    void extractsGpsCoordinatesAsDecimalDegrees() throws Exception {
        ExifSupport.ExifData data;
        try (InputStream is = getClass().getResourceAsStream("/hawk-exif.jpg")) {
            data = ExifSupport.extract(is);
        }

        assertNotNull(data.gpsLatitude);
        assertNotNull(data.gpsLongitude);
        assertEquals(37.8199, data.gpsLatitude, 0.001);
        assertEquals(-122.4783, data.gpsLongitude, 0.001);
    }

    @Test
    void anImageWithNoExifBlockYieldsAllNullFieldsRatherThanFailing() throws Exception {
        ExifSupport.ExifData data;
        try (InputStream is = getClass().getResourceAsStream("/hawk.jpg")) {
            data = ExifSupport.extract(is);
        }

        assertNull(data.camera);
        assertNull(data.lens);
        assertNull(data.exposure);
        assertNull(data.aperture);
        assertNull(data.iso);
        assertNull(data.focalLength);
        assertNull(data.taken);
        assertNull(data.gpsLatitude);
        assertNull(data.gpsLongitude);
    }

    @Test
    void garbageBytesNeverThrowAndProduceEmptyData() {
        ExifSupport.ExifData data = ExifSupport.extract(
                new ByteArrayInputStream("not an image".getBytes()));

        assertEquals(ExifSupport.ExifData.EMPTY, data,
                "unreadable input must yield the shared EMPTY instance, not a thrown exception");
    }

    @Test
    void anEmptyStreamNeverThrows() {
        // Defensive: extract() must be safe to call even if a caller messes up
        // stream plumbing upstream -- never worth failing an upload over.
        ExifSupport.ExifData data = ExifSupport.extract(new ByteArrayInputStream(new byte[0]));

        assertEquals(ExifSupport.ExifData.EMPTY, data);
    }

    /**
     * EXIF ASCII fields are attacker-controlled and unbounded, while the
     * columns they land in are VARCHAR(128) (camera/lens) and VARCHAR(32)
     * (exposure/aperture/focal length). Without a clamp here a crafted JPEG
     * fails the deferred flush of the whole upload transaction -- outside
     * every catch on the metadata path -- leaving the file bytes orphaned on
     * disk. oversized-exif.jpg carries a 500-char Make, a 500-char Model and
     * a 400-char LensModel.
     */
    @Test
    void overlongExifStringsAreClampedToTheirColumnWidths() throws Exception {
        ExifSupport.ExifData data;
        try (InputStream is = getClass().getResourceAsStream("/oversized-exif.jpg")) {
            data = ExifSupport.extract(is);
        }

        assertNotNull(data.camera);
        assertEquals(ExifSupport.MAX_CAMERA_LENGTH, data.camera.length(),
                "a 1001-char Make+Model must be truncated to the exif_camera column width");
        assertTrue(data.camera.startsWith("MMM"), "the truncation must keep the leading text");
        assertNotNull(data.lens);
        assertEquals(ExifSupport.MAX_LENS_LENGTH, data.lens.length(),
                "a 400-char LensModel must be truncated to the exif_lens column width");

        // The remaining strings are short in this fixture but must still be
        // within their (narrower) columns.
        assertTrue(data.exposure.length() <= ExifSupport.MAX_SETTING_LENGTH);
        assertTrue(data.aperture.length() <= ExifSupport.MAX_SETTING_LENGTH);
        assertTrue(data.focalLength.length() <= ExifSupport.MAX_SETTING_LENGTH);
    }

    // ------------------------------------------------------------ orientation

    @Test
    void readOrientationReturnsTheTaggedValue() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/portrait-exif.jpg")) {
            assertEquals(6, ExifSupport.readOrientation(is),
                    "portrait-exif.jpg is a landscape raster tagged Orientation=6");
        }
    }

    @Test
    void readOrientationDefaultsToNormalWhenThereIsNoExifBlock() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/hawk.jpg")) {
            assertEquals(1, ExifSupport.readOrientation(is));
        }
    }

    @Test
    void readOrientationNeverThrowsOnGarbage() {
        assertEquals(1, ExifSupport.readOrientation(
                new ByteArrayInputStream("not an image".getBytes())));
    }
}
