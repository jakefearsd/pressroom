package com.wikantik.pressroom.business.jpa;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.wikantik.pressroom.business.ImageDecoding;
import com.wikantik.pressroom.pojos.MediaFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin thumbnail ({@code _sm}) is exactly the requested box and ARGB. It
 * is also visually the thumbnail AWT's area-averaging scaler made: the mean
 * difference per channel (of 255) stays within {@value #PHOTO_MAX} for a
 * photograph and {@value #ALPHA_MAX} where transparency blends (measured on
 * 2026-11-27 at 0.02-0.25 and 2.2).
 * The reference is that scaler, kept here; it was the implementation until
 * 2026-11-27, and this test passed against it first.
 */
class AdminThumbnailTest {

    private static final double PHOTO_MAX = 1.0;
    private static final double ALPHA_MAX = 3.0;

    @Test
    void theSuitesPhotographsScaleToTheBoxAndLookAsTheyDid() throws IOException {
        for (String photo : List.of("/hawk.jpg", "/portrait-exif.jpg", "/small-photo.jpg", "/oversized-exif.jpg")) {
            BufferedImage image;
            try (InputStream in = AdminThumbnailTest.class.getResourceAsStream(photo)) {
                assertNotNull(in, photo);
                image = ImageDecoding.read(in);
            }
            int[] box = box(image.getWidth(), image.getHeight());
            assertLikeTheReference(photo, image, box[0], box[1], PHOTO_MAX);
        }
    }

    @Test
    void transparencySurvivesTheScale() throws IOException {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 600; x++) {
                int alpha = x * 255 / 599;
                image.setRGB(x, y, (alpha << 24) | ((y * 255 / 399) << 16) | (0x40 << 8) | 0xC0);
            }
        }
        int[] box = box(600, 400);
        assertLikeTheReference("alpha gradient", image, box[0], box[1], ALPHA_MAX);
    }

    private static void assertLikeTheReference(String name, BufferedImage image, int width, int height,
            double max) throws IOException {
        BufferedImage actual = ImageDerivatives.adminThumbnail(image, width, height);
        assertEquals(width, actual.getWidth(), name);
        assertEquals(height, actual.getHeight(), name);
        assertEquals(BufferedImage.TYPE_INT_ARGB, actual.getType(), name);
        double difference = meanChannelDifference(reference(image, width, height), actual);
        assertTrue(difference <= max, name + ": mean difference " + difference + " per channel, more than " + max);
    }

    /** The box MediaFile computes for an image of this size. */
    private static int[] box(int width, int height) {
        MediaFile file = new MediaFile();
        file.setContentType("image/jpeg");
        file.setWidth(width);
        file.setHeight(height);
        return new int[] { file.getThumbnailWidth(), file.getThumbnailHeight() };
    }

    /** AWT's area-averaging scale, the admin thumbnail's scaler until 2026-11-27. */
    private static BufferedImage reference(BufferedImage image, int width, int height) {
        Image scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(scaled, 0, 0, width, height, null);
        g2.dispose();
        return out;
    }

    private static double meanChannelDifference(BufferedImage a, BufferedImage b) {
        long sum = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                for (int shift = 0; shift <= 24; shift += 8) {
                    sum += Math.abs(((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF));
                }
            }
        }
        return sum / (4.0 * a.getWidth() * a.getHeight());
    }
}
