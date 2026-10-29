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

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CwebpEncoder}.
 *
 * <p>Real {@code cwebp} presence differs between the developer's machine and
 * CI/production containers, so most of this suite runs against the forced
 * test seam rather than the real binary -- that way the tests pass
 * regardless of whether {@code cwebp} happens to be installed where they
 * run. The one behavior that genuinely needs the real binary
 * ({@link #encodeProducesARealWebpFileWhenCwebpIsPresent()}) is skipped
 * (not failed) when it is absent.
 */
class CwebpEncoderTest {

    @AfterEach
    void resetDetectionCache() {
        CwebpEncoder.setAvailableForTesting(null);
    }

    @Test
    void forcedUnavailableReportsFalseWithoutTouchingTheRealBinary() {
        CwebpEncoder.setAvailableForTesting(false);

        assertFalse(CwebpEncoder.isAvailable());
    }

    @Test
    void forcedAvailableReportsTrue() {
        CwebpEncoder.setAvailableForTesting(true);

        assertTrue(CwebpEncoder.isAvailable());
    }

    @Test
    void detectionResultIsCachedAcrossCalls() {
        CwebpEncoder.setAvailableForTesting(true);
        assertTrue(CwebpEncoder.isAvailable());

        // Real detection is bypassed by the cache, not re-run, so flipping the
        // system property here must NOT change the answer.
        System.setProperty(CwebpEncoder.FORCE_PROPERTY, "false");
        try {
            assertTrue(CwebpEncoder.isAvailable(), "cached result must not be re-detected");
        } finally {
            System.clearProperty(CwebpEncoder.FORCE_PROPERTY);
        }
    }

    @Test
    void encodeReturnsFalseAndNeverThrowsWhenCwebpIsMissing() {
        // A nonexistent input also guarantees encode() can't succeed even if
        // some cwebp happens to be on PATH here.
        File missingInput = new File("/nonexistent/path/should-not-exist.png");
        File output = new File(System.getProperty("java.io.tmpdir"), "pressroom-cwebp-test-output.webp");
        output.deleteOnExit();

        boolean result = CwebpEncoder.encode(missingInput, output, 80);

        assertFalse(result);
    }

    @Test
    void encodeProducesARealWebpFileWhenCwebpIsPresent() throws Exception {
        CwebpEncoder.setAvailableForTesting(null); // use real detection for this one test
        org.junit.jupiter.api.Assumptions.assumeTrue(CwebpEncoder.isAvailable(),
                "cwebp is not installed on this machine -- skipping the real-encode check");

        File input = File.createTempFile("pressroom-cwebp-test-", ".png");
        File output = File.createTempFile("pressroom-cwebp-test-", ".webp");
        input.deleteOnExit();
        output.deleteOnExit();
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.imageio.ImageIO.write(img, "png", input);

            boolean result = CwebpEncoder.encode(input, output, 80);

            assertTrue(result, "cwebp should successfully encode a trivial PNG");
            assertTrue(output.length() > 0, "the webp output file must be non-empty");
        } finally {
            input.delete();
            output.delete();
        }
    }
}
