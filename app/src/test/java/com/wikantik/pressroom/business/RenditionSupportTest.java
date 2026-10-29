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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RenditionSupport} against a fake {@link FileContentManager}
 * -- no database, no real filesystem, no real {@code cwebp} process. The
 * JPA-backed end-to-end path (upload through {@code MediaFileManager}, disk
 * assertions) lives in {@link MediaFileTest}.
 */
class RenditionSupportTest {

    @AfterEach
    void resetCwebpDetection() {
        CwebpEncoder.setAvailableForTesting(null);
    }

    @Test
    void isRenditionFileNameMatchesTheExactConvention() {
        String id = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        assertTrue(RenditionSupport.isRenditionFileName(id + "_sm"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_480"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_960"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_1600"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_2400"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_480.webp"));
        assertTrue(RenditionSupport.isRenditionFileName(id + "_2400.webp"));
    }

    @Test
    void isRenditionFileNameRejectsLegitimateUnderscoredUserFilenames() {
        // Real uploads are stored under their id, not their original name, but
        // originalPath-style lookups and any future convention change must
        // never treat an ordinary underscored name as a rendition.
        assertFalse(RenditionSupport.isRenditionFileName("my_vacation_photo.jpg"));
        assertFalse(RenditionSupport.isRenditionFileName("report_480.pdf"));
        assertFalse(RenditionSupport.isRenditionFileName("short-id_480"));
        assertFalse(RenditionSupport.isRenditionFileName(null));
    }

    @Test
    void isRenditionFileNameRejectsAnUnknownSuffix() {
        String id = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        // 500 is not a ladder width or the admin thumbnail suffix.
        assertFalse(RenditionSupport.isRenditionFileName(id + "_500"));
    }

    @Test
    void isLadderEligibleMatchesExactlyTheFormatsTheLadderGenerates() {
        // Callers that declare the served image's dimensions (og:image) must
        // be able to tell whether a ?w= URL really serves a resized file or
        // silently falls back to the original.
        assertTrue(RenditionSupport.isLadderEligible("image/jpeg"));
        assertTrue(RenditionSupport.isLadderEligible("image/jpg"));
        assertTrue(RenditionSupport.isLadderEligible("IMAGE/PNG"));
        assertFalse(RenditionSupport.isLadderEligible("image/gif"),
                "no gif renditions are ever generated");
        assertFalse(RenditionSupport.isLadderEligible("image/bmp"));
        assertFalse(RenditionSupport.isLadderEligible("image/webp"));
        assertFalse(RenditionSupport.isLadderEligible(null));
    }

    @Test
    void renditionFileIdsListsEveryLadderWidthWithAndWithoutWebp() {
        List<String> ids = RenditionSupport.renditionFileIds("abc");

        assertEquals(RenditionSupport.LADDER_WIDTHS.size() * 2, ids.size());
        for (int width : RenditionSupport.LADDER_WIDTHS) {
            assertTrue(ids.contains("abc_" + width));
            assertTrue(ids.contains("abc_" + width + ".webp"));
        }
    }

    @Test
    void formatForPreservesJpegAndPngFamiliesAndRejectsOthers() {
        assertEquals("jpg", RenditionSupport.formatFor("image/jpeg"));
        assertEquals("jpg", RenditionSupport.formatFor("image/jpg"));
        assertEquals("jpg", RenditionSupport.formatFor("IMAGE/JPEG"));
        assertEquals("png", RenditionSupport.formatFor("image/png"));
        assertNull(RenditionSupport.formatFor("image/gif"));
        assertNull(RenditionSupport.formatFor("image/webp"));
        assertNull(RenditionSupport.formatFor(null));
    }

    @Test
    void generateSkipsLadderWidthsAtOrAboveTheOriginal() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/jpeg");
        // 500 wide: only the 480 rung is narrower than the original.
        BufferedImage original = new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        assertTrue(cmgr.saved.containsKey(mediaFile.getId() + "_480"),
                "480w rendition must be generated for a 500w original");
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_960"),
                "960w must be skipped: it is not narrower than the 500w original");
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_1600"));
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_2400"));
    }

    /**
     * Pins the boundary condition exactly: {@code width >= originalWidth}
     * skips a rung, so an original whose width is EXACTLY equal to a ladder
     * rung (960 here) must not produce that rung's rendition -- Roller never
     * upscales, and "equal" is "not narrower".
     */
    @Test
    void generateSkipsARungExactlyEqualToTheOriginalWidth() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/jpeg");
        BufferedImage original = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        assertTrue(cmgr.saved.containsKey(mediaFile.getId() + "_480"),
                "480w is narrower than the 960w original and must be generated");
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_960"),
                "a rung exactly equal to the original width must be skipped, not generated");
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_1600"));
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_2400"));
    }

    /**
     * Reproduces the bug a review round caught: {@code Thumbnails.outputQuality(...)}
     * is silently discarded when the resize result is pulled out via
     * {@code asBufferedImage()} (no encoding happens at that call at all), so
     * the JPEG ladder was actually being written at the JDK's default
     * compression regardless of the configured quality. Encoding now happens
     * explicitly in {@code RenditionSupport.encodeToBytes}, verified directly
     * here: a lower quality setting must produce a materially smaller file
     * for a detailed (non-flat) image, where the quality setting actually has
     * something to compress away.
     */
    @Test
    void jpegQualityActuallyChangesTheEncodedBytes() throws Exception {
        BufferedImage noisy = noisyImage(256, 256);

        byte[] highQuality = RenditionSupport.encodeToBytes(noisy, "jpg", 0.85f);
        byte[] lowQuality = RenditionSupport.encodeToBytes(noisy, "jpg", 0.1f);

        assertTrue(lowQuality.length < highQuality.length,
                "0.1 quality (" + lowQuality.length + " bytes) must encode smaller than 0.85 quality ("
                        + highQuality.length + " bytes) for a detailed image");

        // and both must still decode back to a valid image of the right size
        assertEquals(256, ImageIO.read(new ByteArrayInputStream(highQuality)).getWidth());
        assertEquals(256, ImageIO.read(new ByteArrayInputStream(lowQuality)).getWidth());
    }

    @Test
    void pngEncodingIgnoresTheQualityParameterSinceItIsLossless() throws Exception {
        BufferedImage noisy = noisyImage(64, 64);

        byte[] atOneQuality = RenditionSupport.encodeToBytes(noisy, "png", 1.0f);
        byte[] atLowQuality = RenditionSupport.encodeToBytes(noisy, "png", 0.1f);

        // PNG is lossless: both must decode to pixel-identical images regardless
        // of the (irrelevant, ignored) quality argument.
        BufferedImage decoded1 = ImageIO.read(new ByteArrayInputStream(atOneQuality));
        BufferedImage decoded2 = ImageIO.read(new ByteArrayInputStream(atLowQuality));
        assertEquals(noisy.getRGB(0, 0), decoded1.getRGB(0, 0));
        assertEquals(decoded1.getRGB(0, 0), decoded2.getRGB(0, 0));
    }

    private static BufferedImage noisyImage(int width, int height) {
        // A flat/solid color compresses to ~the same size at any JPEG quality;
        // real image detail is what quality settings actually trade off, so
        // the quality-difference test needs pseudo-random per-pixel noise, not
        // a synthetic gradient or solid fill.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    @Test
    void generateProducesNoWebpSiblingsWhenCwebpIsUnavailable() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/jpeg");
        BufferedImage original = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        assertTrue(cmgr.saved.containsKey(mediaFile.getId() + "_480"));
        assertTrue(cmgr.saved.containsKey(mediaFile.getId() + "_960"));
        assertFalse(cmgr.saved.keySet().stream().anyMatch(k -> k.endsWith(".webp")),
                "no .webp siblings should be produced when cwebp is unavailable: " + cmgr.saved.keySet());

        // and the raster renditions decode back to real, correctly-narrowed images
        BufferedImage decoded480 = ImageIO.read(new ByteArrayInputStream(cmgr.saved.get(mediaFile.getId() + "_480")));
        assertEquals(480, decoded480.getWidth());
    }

    @Test
    void generatePreservesThePngFormatFamily() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/png");
        BufferedImage original = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        byte[] rendition = cmgr.saved.get(mediaFile.getId() + "_480");
        assertTrue(rendition != null && rendition.length > 0);
        // PNG signature: 0x89 'P' 'N' 'G' ...
        assertEquals((byte) 0x89, rendition[0]);
        assertEquals('P', rendition[1]);
        assertEquals('N', rendition[2]);
        assertEquals('G', rendition[3]);
    }

    @Test
    void generateSkipsUnsupportedFormatFamiliesEntirely() {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/gif");
        BufferedImage original = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        assertTrue(cmgr.saved.isEmpty(), "gif is not a ladder-covered format; nothing should be generated");
    }

    @Test
    void aSaveFailureOnOneWidthDoesNotBlockTheOthers() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile mediaFile = imageMediaFile("image/jpeg");
        FakeFileContentManager cmgr = new FakeFileContentManager();
        cmgr.failOn(mediaFile.getId() + "_480");
        BufferedImage original = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);

        // must not throw despite the simulated disk failure on the 480 rung
        RenditionSupport.generate(cmgr, mediaFile, original);

        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_480"));
        assertTrue(cmgr.saved.containsKey(mediaFile.getId() + "_960"),
                "a failure on one rung must not prevent the others from being generated");
    }

    @Test
    void generateNeverThrowsEvenWithAGarbageContentType() {
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("not-an-image-type");
        BufferedImage original = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile, original);

        assertTrue(cmgr.saved.isEmpty());
    }

    // ------------------------------------------------------------------ crop

    @Test
    void clampCropRectPassesAnInBoundsRectangleThroughUnchanged() {
        java.awt.Rectangle rect = RenditionSupport.clampCropRect(1000, 600, 100, 50, 400, 300);

        assertEquals(new java.awt.Rectangle(100, 50, 400, 300), rect);
    }

    @Test
    void clampCropRectTrimsBrowserOvershootAtTheEdges() {
        // Selection coordinates come from client-side layout math scaled to
        // natural pixels; a few pixels of overshoot is normal, not an error.
        java.awt.Rectangle rect = RenditionSupport.clampCropRect(1000, 600, -3, -2, 1010, 610);

        assertEquals(new java.awt.Rectangle(0, 0, 1000, 600), rect);
    }

    @Test
    void clampCropRectTrimsARectangleHangingOffTheFarCorner() {
        java.awt.Rectangle rect = RenditionSupport.clampCropRect(1000, 600, 900, 500, 400, 300);

        assertEquals(new java.awt.Rectangle(900, 500, 100, 100), rect);
    }

    @Test
    void clampCropRectRejectsARectangleEntirelyOutsideTheImage() {
        assertThrows(IllegalArgumentException.class,
                () -> RenditionSupport.clampCropRect(1000, 600, 1000, 0, 100, 100),
                "a rectangle starting at the right edge covers no pixels");
        assertThrows(IllegalArgumentException.class,
                () -> RenditionSupport.clampCropRect(1000, 600, -200, -200, 100, 100));
    }

    @Test
    void clampCropRectRejectsDegenerateSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> RenditionSupport.clampCropRect(1000, 600, 10, 10, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> RenditionSupport.clampCropRect(1000, 600, 10, 10, 100, -5));
        assertThrows(IllegalArgumentException.class,
                () -> RenditionSupport.clampCropRect(0, 0, 0, 0, 10, 10),
                "an image with no pixels cannot be cropped");
    }

    @Test
    void cropCopiesExactlyTheRequestedPixels() {
        BufferedImage source = cornerMarkedImage(); // 4x2, uniquely colored corners

        BufferedImage cropped = RenditionSupport.crop(source,
                new java.awt.Rectangle(3, 0, 1, 1));

        assertEquals(1, cropped.getWidth());
        assertEquals(1, cropped.getHeight());
        assertEquals(0x00FF00, cropped.getRGB(0, 0) & 0xFFFFFF,
                "the 1x1 crop at (3,0) must contain the top-right (green) pixel");
    }

    @Test
    void cropReturnsAnIndependentImageNotASharedSubRaster() {
        BufferedImage source = cornerMarkedImage();

        BufferedImage cropped = RenditionSupport.crop(source,
                new java.awt.Rectangle(0, 0, 2, 2));
        source.setRGB(0, 0, 0xFFFFFF);

        assertEquals(0xFF0000, cropped.getRGB(0, 0) & 0xFFFFFF,
                "mutating the source after cropping must not change the crop");
    }

    @Test
    void encodeRoutesJpegAndPngByContentTypeAndRejectsTheRest() throws Exception {
        BufferedImage image = noisyImage(32, 32);

        byte[] jpeg = RenditionSupport.encode(image, "image/jpeg");
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1], "JPEG SOI marker expected");

        byte[] png = RenditionSupport.encode(image, "image/png");
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> RenditionSupport.encode(image, "image/gif"),
                "formats outside the ladder have no re-encode path");
    }

    // ------------------------------------------------------------ orientation

    /**
     * A 4x2 image whose four corners are uniquely colored, so the position of
     * the red pixel after a transform pins down exactly which of the eight
     * EXIF orientations was applied.
     */
    private static BufferedImage cornerMarkedImage() {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, 0x101010);
            }
        }
        image.setRGB(0, 0, 0xFF0000); // top-left     red
        image.setRGB(3, 0, 0x00FF00); // top-right    green
        image.setRGB(0, 1, 0x0000FF); // bottom-left  blue
        image.setRGB(3, 1, 0xFFFF00); // bottom-right yellow
        return image;
    }

    private static void assertOriented(int orientation, int expectedWidth, int expectedHeight,
            int expectedRedX, int expectedRedY) {
        BufferedImage result = RenditionSupport.applyOrientation(cornerMarkedImage(), orientation);

        assertEquals(expectedWidth, result.getWidth(), "width for orientation " + orientation);
        assertEquals(expectedHeight, result.getHeight(), "height for orientation " + orientation);
        assertEquals(0xFF0000, result.getRGB(expectedRedX, expectedRedY) & 0xFFFFFF,
                "orientation " + orientation + " must move the top-left (red) pixel to ("
                        + expectedRedX + "," + expectedRedY + ")");
    }

    /**
     * All eight EXIF orientation values, documented by example: 1 is the
     * identity, 2/4 mirror, 3 rotates 180, 5/7 transpose/transverse, and
     * 6/8 are the 90-degree rotations that phone cameras actually emit for
     * portrait shots (and the ones that swap width and height).
     */
    @Test
    void applyOrientationHandlesAllEightExifOrientations() {
        assertOriented(1, 4, 2, 0, 0); // identity
        assertOriented(2, 4, 2, 3, 0); // mirror horizontal
        assertOriented(3, 4, 2, 3, 1); // rotate 180
        assertOriented(4, 4, 2, 0, 1); // mirror vertical
        assertOriented(5, 2, 4, 0, 0); // transpose
        assertOriented(6, 2, 4, 1, 0); // rotate 90 CW
        assertOriented(7, 2, 4, 1, 3); // transverse
        assertOriented(8, 2, 4, 0, 3); // rotate 270 CW (90 CCW)
    }

    @Test
    void applyOrientationLeavesTheImageAloneForNormalOrGarbageValues() {
        BufferedImage original = cornerMarkedImage();

        // 1 is "normal"; 0 and 9 are out of the EXIF range and must not be
        // guessed at -- an unreadable tag is not a reason to rotate a photo.
        assertSame(original, RenditionSupport.applyOrientation(original, 1));
        assertSame(original, RenditionSupport.applyOrientation(original, 0));
        assertSame(original, RenditionSupport.applyOrientation(original, 9));
        assertNull(RenditionSupport.applyOrientation(null, 6));
    }

    /**
     * The whole point of the transform: renditions are generated from the
     * decoded raster, which carries no EXIF, so the ladder must be built from
     * the already-corrected image or a portrait photo ships sideways in
     * srcset while the original URL displays upright.
     */
    @Test
    void generateBuildsTheLadderFromTheOrientationCorrectedImage() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        FakeFileContentManager cmgr = new FakeFileContentManager();
        MediaFile mediaFile = imageMediaFile("image/jpeg");
        // A landscape raster that is really a portrait photo (Orientation=6).
        BufferedImage raster = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);

        RenditionSupport.generate(cmgr, mediaFile,
                RenditionSupport.applyOrientation(raster, 6));

        BufferedImage rendition = ImageIO.read(
                new ByteArrayInputStream(cmgr.saved.get(mediaFile.getId() + "_480")));
        assertEquals(480, rendition.getWidth());
        assertEquals(800, rendition.getHeight(),
                "the 480w rung of a 600x1000 upright image must be 800 tall, not 288");
        assertFalse(cmgr.saved.containsKey(mediaFile.getId() + "_960"),
                "after rotation the image is only 600 wide, so the 960 rung must be skipped");
    }

    private static MediaFile imageMediaFile(String contentType) {
        Weblog weblog = new Weblog();
        weblog.setHandle("renditiontestblog");
        MediaFileDirectory dir = new MediaFileDirectory();
        MediaFile mediaFile = new MediaFile();
        mediaFile.setWeblog(weblog);
        mediaFile.setDirectory(dir);
        mediaFile.setContentType(contentType);
        return mediaFile;
    }

    /**
     * In-memory stand-in for {@link FileContentManager} that records every
     * save by fileId, so tests can assert on exactly what RenditionSupport
     * attempted to write without touching a real filesystem or database.
     */
    private static final class FakeFileContentManager implements FileContentManager {

        final Map<String, byte[]> saved = new HashMap<>();
        private String failingFileId;

        void failOn(String fileId) {
            this.failingFileId = fileId;
        }

        @Override
        public com.wikantik.pressroom.pojos.FileContent getFileContent(Weblog weblog, String fileId) {
            throw new UnsupportedOperationException("not needed by RenditionSupport.generate()");
        }

        @Override
        public void saveFileContent(Weblog weblog, String fileId, InputStream is) throws FileIOException {
            if (fileId.equals(failingFileId)) {
                throw new FileIOException("simulated disk failure for " + fileId);
            }
            try {
                saved.put(fileId, is.readAllBytes());
            } catch (java.io.IOException e) {
                throw new FileIOException("read failure", e);
            }
        }

        @Override
        public void deleteFile(Weblog weblog, String fileId) {
            saved.remove(fileId);
        }

        @Override
        public boolean overQuota(Weblog weblog) {
            return false;
        }

        @Override
        public boolean canSave(Weblog weblog, String fileName, String contentType, long size,
                com.wikantik.pressroom.util.PressroomMessages messages) {
            return true;
        }

        @Override
        public void release() {
        }
    }
}
