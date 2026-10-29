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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for {@link BlurHash}.
 *
 * <p>The expected strings below were NOT produced by running this class --
 * that would only prove the encoder agrees with itself. They come from a
 * second, independent implementation of the same published BlurHash
 * algorithm (github.com/woltapp/blurhash, TypeScript reference:
 * encode.ts/base83.ts/utils.ts), written in Python against the reference
 * source rather than against this file, then run once to compute the
 * hash for each fixture image below. If this encoder and the reference
 * implementation ever disagree on the same pixels, one of the two has a bug
 * -- these tests catch that regardless of which side it's on.
 */
class BlurHashTest {

    @Test
    void encodesASolidColorImageToTheReferenceHash() {
        BufferedImage image = solidColor(32, 24, 128, 64, 200);

        String hash = BlurHash.encode(image);

        assertEquals("L7Ew7V$pfQ$p$;jvfQjvfQfQfQfQ", hash);
    }

    @Test
    void encodesAGradientImageToTheReferenceHash() {
        BufferedImage image = gradient(32, 24);

        String hash = BlurHash.encode(image);

        assertEquals("L$HewF2swxX8l}WDjte;gJfjfQfj", hash);
    }

    @Test
    void producesTheFixedLengthTwentyEightCharacterStringForTheDefaultFourByThreeGrid() {
        // 1 (size) + 1 (max AC) + 4 (DC) + 2*(4*3-1) (AC) = 28
        String hash = BlurHash.encode(solidColor(10, 10, 10, 20, 30));

        assertEquals(28, hash.length());
    }

    @Test
    void isDeterministicForTheSameImage() {
        BufferedImage image = gradient(16, 16);

        assertEquals(BlurHash.encode(image), BlurHash.encode(image));
    }

    @Test
    void differentImagesProduceDifferentHashes() {
        String red = BlurHash.encode(solidColor(16, 16, 255, 0, 0));
        String blue = BlurHash.encode(solidColor(16, 16, 0, 0, 255));

        assertNotNull(red);
        assertNotNull(blue);
        assertTrue(!red.equals(blue));
    }

    // ------------------------------------------------------- averageColor

    @Test
    void averageColorRoundTripsThroughASolidColorEncode() {
        // The DC component of a solid-color image is that color exactly, so
        // decoding it back must reproduce the input (sRGB->linear->sRGB is
        // lossless for a uniform image).
        String hash = BlurHash.encode(solidColor(16, 16, 128, 64, 200));

        assertEquals("#8040c8", BlurHash.averageColor(hash));
    }

    @Test
    void averageColorIsNullSafeForMissingOrCorruptHashes() {
        assertNull(BlurHash.averageColor(null),
                "pre-pipeline uploads have no blurhash at all");
        assertNull(BlurHash.averageColor("L7E"),
                "too short to contain a DC component");
        assertNull(BlurHash.averageColor("L7\"w7V$pfQ$p$;jvfQjvfQfQfQfQ"),
                "a character outside the base83 alphabet must not throw");
    }

    private static BufferedImage solidColor(int width, int height, int r, int g, int b) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int rgb = (r << 16) | (g << 8) | b;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /** Matches the fixture generator used to compute the golden reference values. */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int g = 255 * y / (height - 1);
            for (int x = 0; x < width; x++) {
                int r = 255 * x / (width - 1);
                int rgb = (r << 16) | (g << 8) | 128;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
