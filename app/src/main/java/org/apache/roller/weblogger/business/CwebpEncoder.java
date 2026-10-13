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
package org.apache.roller.weblogger.business;

import java.io.File;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around the {@code cwebp} command-line tool.
 *
 * <p>There is no maintained pure-JVM WebP encoder, so responsive-image WebP
 * renditions are produced by shelling out to {@code cwebp} (from the
 * libwebp-tools package) when it is present on the host. Presence is
 * feature-detected once at first use and cached for the life of the JVM;
 * when the binary is absent, callers fall back to JPEG/PNG-only renditions
 * -- {@code cwebp} is never a hard requirement.
 */
public final class CwebpEncoder {

    private static final Logger log = LoggerFactory.getLogger(CwebpEncoder.class);

    /**
     * System property that, when set to {@code true} or {@code false}, forces
     * the detection result without touching the real {@code cwebp} binary.
     * Intended for tests and for operators who want to force the mode
     * without relying on PATH detection.
     */
    public static final String FORCE_PROPERTY = "roller.rendition.cwebp.forceAvailable";

    // null = not yet detected
    private static volatile Boolean available;

    private CwebpEncoder() {
    }

    /**
     * Returns true if {@code cwebp} is usable on this host. Detected once
     * (via {@code cwebp -version}) and cached; the result never changes for
     * the life of the JVM except through the test seam below.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (CwebpEncoder.class) {
            if (available == null) {
                available = detect();
                log.info("cwebp {}", available
                        ? "detected; WebP renditions enabled"
                        : "not found; WebP renditions disabled, JPEG/PNG ladder only");
            }
            return available;
        }
    }

    /**
     * Test-only seam: forces the cached availability result so tests are
     * deterministic regardless of whether the machine running them happens
     * to have {@code cwebp} installed. Public because rendition generation
     * (business package) and rendition serving (rendering.servlets package)
     * both need it exercised from their own test packages; production code
     * must never call this. Pass {@code null} to clear the override and
     * force real re-detection on the next {@link #isAvailable()} call.
     */
    public static void setAvailableForTesting(Boolean forced) {
        available = forced;
    }

    private static boolean detect() {
        String forced = System.getProperty(FORCE_PROPERTY);
        if (forced != null) {
            return Boolean.parseBoolean(forced);
        }
        try {
            Process process = new ProcessBuilder("cwebp", "-version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            // no cwebp on PATH, or not executable -- never a hard failure
            return false;
        }
    }

    /**
     * Encodes {@code input} (a JPEG or PNG file) to WebP at {@code output}.
     * Returns true on success. Never throws -- callers treat encode failure
     * as "skip this rendition", not as an upload failure.
     */
    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification = "ProcessBuilder runs the fixed executable cwebp with an argument vector, never "
                    + "a shell; the two paths are temp files RenditionSupport made with "
                    + "File.createTempFile, and quality is an int.")
    public static boolean encode(File input, File output, int quality) {
        try {
            Process process = new ProcessBuilder("cwebp", "-quiet",
                    "-q", String.valueOf(quality),
                    input.getAbsolutePath(), "-o", output.getAbsolutePath())
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && output.isFile() && output.length() > 0;
        } catch (Exception e) {
            log.warn("cwebp encode failed for {}", input, e);
            return false;
        }
    }
}
