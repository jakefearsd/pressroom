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
package com.wikantik.pressroom.ui.rendering.servlets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a media file may do when a browser opens it from this site. The
 * content type is the uploader's own claim (the multipart part's type), and
 * the upload policy allows every type but exe by default, so an author or an
 * API token could store an HTML page or a scripted SVG and send an admin to
 * it on the site's own origin (code review, 2026-11-03). Nothing is ever
 * sniffed; passive media is served inline as before; an SVG still displays,
 * but in a sandbox where no script runs; anything else is a download, in a
 * sandbox as well.
 */
class MediaResponseHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/gif", "image/webp", "image/avif",
            "video/mp4", "audio/mpeg", "application/pdf", "text/plain", "IMAGE/JPEG"})
    void passiveMediaIsInlineAndNeverSniffed(String type) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(type, MediaResponseHeaders.apply(response, type));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertNull(response.getHeader("Content-Disposition"), type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "image/svg+xml; charset=utf-8"})
    void anSvgDisplaysButNoScriptInItRuns(String type) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(type, MediaResponseHeaders.apply(response, type));
        assertNull(response.getHeader("Content-Disposition"), "an image, so it still opens in the browser");
        String csp = response.getHeader("Content-Security-Policy");
        assertTrue(csp != null && csp.startsWith("sandbox;") && csp.contains("default-src 'none'"), csp);
        assertFalse(csp.contains("allow-scripts"), csp);
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "TEXT/HTML; charset=utf-8", "application/xhtml+xml", "text/xml",
            "application/xml", "application/javascript", "text/javascript", "application/octet-stream",
            "application/x-unknown", "\u0130MAGE/PNG", "image/pn\u0261"})
    void anythingElseIsADownloadInASandbox(String type) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(type, MediaResponseHeaders.apply(response, type));
        assertTrue(String.valueOf(response.getHeader("Content-Disposition")).startsWith("attachment"), type);
        assertEquals("sandbox", response.getHeader("Content-Security-Policy"), type);
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void noTypeAtAllIsAnOpaqueDownload(String type) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals("application/octet-stream", MediaResponseHeaders.apply(response, type));
        assertTrue(String.valueOf(response.getHeader("Content-Disposition")).startsWith("attachment"));
    }
}
