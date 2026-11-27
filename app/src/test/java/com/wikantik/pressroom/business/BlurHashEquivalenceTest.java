package com.wikantik.pressroom.business;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The encoder's output is byte-identical to the straightforward BlurHash
 * algorithm kept below as {@link Reference}, which is the encoder as it stood
 * on 2026-11-27, before it was made faster.
 *
 * <p>A characterisation test: written first and passing against that encoder
 * unchanged. It pins equality across random noise in odd sizes, several pixel
 * formats, a single pixel, a 480w rendition like those the uploader encodes, and
 * the suite's real photographs. Stored blurhashes therefore stay valid, and no
 * re-encode is needed.
 */
class BlurHashEquivalenceTest {

    @Test
    void randomImagesOfManySizesAndFormatsEncodeExactlyAsTheReferenceDoes() {
        Random random = new Random(20261127L);
        int[][] sizes = { {1, 1}, {2, 3}, {7, 5}, {31, 17}, {64, 64}, {101, 3}, {3, 101}, {480, 320} };
        int[] types = { BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR,
                BufferedImage.TYPE_BYTE_GRAY };
        for (int[] size : sizes) {
            // every component grid and format on the small images; the default grid and two
            // formats on the large ones, which the reference (the slow encoder) would make slow
            boolean small = size[0] * size[1] <= 64 * 64;
            for (int type : small ? types : new int[] { types[0], types[2] }) {
                BufferedImage image = noise(random, size[0], size[1], type);
                for (int cx = small ? 1 : 4; cx <= 4; cx++) {
                    for (int cy = small ? 1 : 3; cy <= 3; cy++) {
                        assertEquals(Reference.encode(image, cx, cy), BlurHash.encode(image, cx, cy),
                                size[0] + "x" + size[1] + " type " + type + " components " + cx + "x" + cy);
                    }
                }
            }
        }
    }

    @Test
    void theSuitesPhotographsEncodeExactlyAsTheReferenceDoes() throws IOException {
        List<String> photos = List.of("/hawk.jpg", "/hawk-exif.jpg", "/portrait-exif.jpg", "/small-photo.jpg");
        List<String> checked = new ArrayList<>();
        for (String photo : photos) {
            try (InputStream in = BlurHashEquivalenceTest.class.getResourceAsStream(photo)) {
                assertNotNull(in, photo);
                BufferedImage image = ImageDecoding.read(in);
                assertNotNull(image, photo);
                assertEquals(Reference.encode(image, 4, 3), BlurHash.encode(image), photo);
                checked.add(photo);
            }
        }
        assertEquals(photos, checked);
    }

    private static BufferedImage noise(Random random, int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    /** The BlurHash encoder as it stood before it was made faster: the oracle. */
    private static final class Reference {

        private static final String DIGITS =
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

        static String encode(BufferedImage image, int componentsX, int componentsY) {
            int width = image.getWidth();
            int height = image.getHeight();
            double[][] factors = new double[componentsX * componentsY][];
            int index = 0;
            for (int y = 0; y < componentsY; y++) {
                for (int x = 0; x < componentsX; x++) {
                    factors[index++] = basis(image, width, height, x, y);
                }
            }
            StringBuilder hash = new StringBuilder();
            hash.append(base83(componentsX - 1 + (componentsY - 1) * 9, 1));
            double maximumValue;
            if (factors.length > 1) {
                double actual = 0;
                for (int i = 1; i < factors.length; i++) {
                    for (double component : factors[i]) {
                        actual = Math.max(actual, Math.abs(component));
                    }
                }
                int quantised = Math.max(0, Math.min(82, (int) Math.floor(actual * 166 - 0.5)));
                hash.append(base83(quantised, 1));
                maximumValue = (quantised + 1) / 166.0;
            } else {
                maximumValue = 1;
                hash.append(base83(0, 1));
            }
            double[] dc = factors[0];
            hash.append(base83((toSrgb(dc[0]) << 16) + (toSrgb(dc[1]) << 8) + toSrgb(dc[2]), 4));
            for (int i = 1; i < factors.length; i++) {
                double[] ac = factors[i];
                hash.append(base83(quantise(ac[0], maximumValue) * 19 * 19
                        + quantise(ac[1], maximumValue) * 19 + quantise(ac[2], maximumValue), 2));
            }
            return hash.toString();
        }

        private static double[] basis(BufferedImage image, int width, int height, int xComponent, int yComponent) {
            double r = 0;
            double g = 0;
            double b = 0;
            double normalisation = (xComponent == 0 && yComponent == 0) ? 1 : 2;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double basis = Math.cos(Math.PI * xComponent * x / width)
                            * Math.cos(Math.PI * yComponent * y / height);
                    int rgb = image.getRGB(x, y);
                    r += basis * toLinear((rgb >> 16) & 0xFF);
                    g += basis * toLinear((rgb >> 8) & 0xFF);
                    b += basis * toLinear(rgb & 0xFF);
                }
            }
            double scale = normalisation / (width * height);
            return new double[] { r * scale, g * scale, b * scale };
        }

        private static int quantise(double value, double maximumValue) {
            double v = value / maximumValue;
            double scaled = Math.signum(v) * Math.pow(Math.abs(v), 0.5) * 9 + 9.5;
            return Math.max(0, Math.min(18, (int) Math.floor(scaled)));
        }

        private static double toLinear(int value) {
            double v = value / 255.0;
            return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }

        private static int toSrgb(double value) {
            double v = Math.max(0.0, Math.min(1.0, value));
            if (v <= 0.0031308) {
                return (int) (v * 12.92 * 255 + 0.5);
            }
            return (int) ((1.055 * Math.pow(v, 1.0 / 2.4) - 0.055) * 255 + 0.5);
        }

        private static String base83(int value, int length) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i <= length; i++) {
                int divisor = 1;
                for (int j = 0; j < length - i; j++) {
                    divisor *= 83;
                }
                out.append(DIGITS.charAt((value / divisor) % 83));
            }
            return out.toString();
        }
    }
}
