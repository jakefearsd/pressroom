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

/**
 * Hand-rolled encoder for the <a href="https://blurha.sh">BlurHash</a>
 * compact-placeholder format (no maintained pure-Java implementation exists
 * on Maven Central). Implements the reference algorithm exactly: for each of
 * {@code componentsX * componentsY} 2-D cosine basis functions, compute the
 * average linear-light color weighted by that basis over the whole image
 * (the (0,0) basis is the plain average -- the "DC" component), quantise,
 * and pack everything into a base83 string.
 *
 * <p>Fixed at 4x3 components: enough detail for a CSS blur-up placeholder
 * without an oversized stored string (28 characters total).
 *
 * <p>Encode from a small image -- a rendition or thumbnail, never the
 * original upload -- since cost is {@code O(width * height * componentsX *
 * componentsY)}.
 */
public final class BlurHash {

    private static final String DIGIT_CHARACTERS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    static final int COMPONENTS_X = 4;
    static final int COMPONENTS_Y = 3;

    /** {@link #sRGBToLinear} for each 8-bit channel value, computed once. */
    private static final double[] LINEAR = new double[256];

    static {
        for (int value = 0; value < LINEAR.length; value++) {
            LINEAR[value] = sRGBToLinear(value);
        }
    }

    private BlurHash() {
    }

    /** Encodes {@code image} with the fixed 4x3 component grid. */
    public static String encode(BufferedImage image) {
        return encode(image, COMPONENTS_X, COMPONENTS_Y);
    }

    /**
     * Decodes just the average color (the "DC" component -- characters 2-5 of
     * any BlurHash, a base83-packed 24-bit sRGB value) as a CSS hex color
     * like {@code #8ca3b7}. This is the one piece of a BlurHash that can be
     * used without JavaScript: an inline {@code background-color} on the
     * {@code <img>} gives a dependency-free blur-up placeholder while the
     * real image loads. Returns null for null, too-short, or corrupt input
     * (an unknown base83 digit) -- a bad stored hash must never break a
     * page render.
     */
    public static String averageColor(String blurhash) {
        if (blurhash == null || blurhash.length() < 6) {
            return null;
        }
        int dc = 0;
        for (int i = 2; i < 6; i++) {
            int digit = DIGIT_CHARACTERS.indexOf(blurhash.charAt(i));
            if (digit < 0) {
                return null;
            }
            dc = dc * 83 + digit;
        }
        return String.format("#%06x", dc & 0xFFFFFF);
    }

    /** Package-visible for testing against other component counts. */
    static String encode(BufferedImage image, int componentsX, int componentsY) {
        int width = image.getWidth();
        int height = image.getHeight();

        double[][] factors = basisFactors(image, width, height, componentsX, componentsY);

        double[] dc = factors[0];
        int acCount = factors.length - 1;

        StringBuilder hash = new StringBuilder();
        int sizeFlag = componentsX - 1 + (componentsY - 1) * 9;
        hash.append(encode83(sizeFlag, 1));

        double maximumValue;
        if (acCount > 0) {
            double actualMaximumValue = 0;
            for (int i = 1; i < factors.length; i++) {
                for (double component : factors[i]) {
                    actualMaximumValue = Math.max(actualMaximumValue, Math.abs(component));
                }
            }
            int quantisedMaximumValue = Math.max(0, Math.min(82,
                    (int) Math.floor(actualMaximumValue * 166 - 0.5)));
            hash.append(encode83(quantisedMaximumValue, 1));
            maximumValue = (quantisedMaximumValue + 1) / 166.0;
        } else {
            maximumValue = 1;
            hash.append(encode83(0, 1));
        }

        hash.append(encode83(encodeDc(dc), 4));

        for (int i = 1; i < factors.length; i++) {
            hash.append(encode83(encodeAc(factors[i], maximumValue), 2));
        }

        return hash.toString();
    }

    private static int encodeDc(double[] value) {
        int r = linearToSRGB(value[0]);
        int g = linearToSRGB(value[1]);
        int b = linearToSRGB(value[2]);
        return (r << 16) + (g << 8) + b;
    }

    private static int encodeAc(double[] value, double maximumValue) {
        int qr = quantiseAcComponent(value[0], maximumValue);
        int qg = quantiseAcComponent(value[1], maximumValue);
        int qb = quantiseAcComponent(value[2], maximumValue);
        return qr * 19 * 19 + qg * 19 + qb;
    }

    private static int quantiseAcComponent(double value, double maximumValue) {
        double scaled = signPow(value / maximumValue, 0.5) * 9 + 9.5;
        return Math.max(0, Math.min(18, (int) Math.floor(scaled)));
    }

    private static double signPow(double value, double exponent) {
        return Math.signum(value) * Math.pow(Math.abs(value), exponent);
    }

    /**
     * For every component, sums {@code cos(pi*xComponent*x/width) * cos(pi*yComponent*y/height)}-weighted
     * linear-light RGB over every pixel, normalised so the (0,0) "DC" basis yields the plain average and every
     * other basis is scaled by 2 per the BlurHash spec. Returned in component order, x fastest.
     *
     * <p>One pass over the pixels, a row at a time: each pixel is read and converted to linear light once, and
     * the cosines come from per-axis tables, where the straightforward form re-read every pixel and called
     * {@code Math.cos} twice per pixel for each of the twelve components. Each sum still adds its terms in the
     * same pixel order, from the same expressions, so the result is bit-identical
     * ({@code BlurHashEquivalenceTest} holds it to the straightforward encoder).
     */
    private static double[][] basisFactors(BufferedImage image, int width, int height,
            int componentsX, int componentsY) {
        double[][] cosX = new double[componentsX][width];
        for (int xComponent = 0; xComponent < componentsX; xComponent++) {
            for (int x = 0; x < width; x++) {
                cosX[xComponent][x] = Math.cos(Math.PI * xComponent * x / width);
            }
        }
        double[][] cosY = new double[componentsY][height];
        for (int yComponent = 0; yComponent < componentsY; yComponent++) {
            for (int y = 0; y < height; y++) {
                cosY[yComponent][y] = Math.cos(Math.PI * yComponent * y / height);
            }
        }
        int count = componentsX * componentsY;
        double[] r = new double[count];
        double[] g = new double[count];
        double[] b = new double[count];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x];
                double red = LINEAR[(rgb >> 16) & 0xFF];
                double green = LINEAR[(rgb >> 8) & 0xFF];
                double blue = LINEAR[rgb & 0xFF];
                int component = 0;
                for (int yComponent = 0; yComponent < componentsY; yComponent++) {
                    double cosine = cosY[yComponent][y];
                    for (int xComponent = 0; xComponent < componentsX; xComponent++, component++) {
                        double basis = cosX[xComponent][x] * cosine;
                        r[component] += basis * red;
                        g[component] += basis * green;
                        b[component] += basis * blue;
                    }
                }
            }
        }
        double[][] factors = new double[count][];
        for (int component = 0; component < count; component++) {
            double normalisation = component == 0 ? 1 : 2;
            double scale = normalisation / (width * height);
            factors[component] = new double[] { r[component] * scale, g[component] * scale, b[component] * scale };
        }
        return factors;
    }

    private static double sRGBToLinear(int value) {
        double v = value / 255.0;
        if (v <= 0.04045) {
            return v / 12.92;
        }
        return Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSRGB(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        if (v <= 0.0031308) {
            return (int) (v * 12.92 * 255 + 0.5);
        }
        return (int) ((1.055 * Math.pow(v, 1.0 / 2.4) - 0.055) * 255 + 0.5);
    }

    private static String encode83(int value, int length) {
        StringBuilder result = new StringBuilder();
        for (int i = 1; i <= length; i++) {
            int digit = value / pow83(length - i) % 83;
            result.append(DIGIT_CHARACTERS.charAt(digit));
        }
        return result.toString();
    }

    private static int pow83(int exponent) {
        int result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 83;
        }
        return result;
    }
}
