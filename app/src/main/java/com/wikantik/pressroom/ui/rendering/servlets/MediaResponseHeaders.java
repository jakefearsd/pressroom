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

import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

/**
 * What a media file may do when a browser opens it from this site.
 *
 * <p>A stored file's content type is the uploader's own claim (the multipart
 * part's type), and the upload policy allows every type but exe by default,
 * so an author or an API token could store an HTML page or a scripted SVG and
 * send an admin to it on the site's own origin (code review, 2026-11-03).
 * So nothing is ever sniffed; passive media is served inline as before; an
 * SVG still displays, but in a sandbox where no script runs; anything else is
 * a download, in a sandbox as well.
 */
final class MediaResponseHeaders {

    /** What an opaque file is served as. */
    static final String OPAQUE = "application/octet-stream";

    /** Types a browser shows without running anything of the file's own. */
    private static final Set<String> PASSIVE = Set.of("image/jpeg", "image/png", "image/gif", "image/webp",
            "image/avif", "image/bmp", "image/x-icon", "image/vnd.microsoft.icon", "application/pdf", "text/plain");

    private static final String SVG = "image/svg+xml";

    /** An SVG's own styles still apply; scripts, fetches and forms do not. */
    static final String SVG_POLICY = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:";

    private MediaResponseHeaders() {
    }

    /**
     * Sets the headers for serving a file of {@code contentType} and returns
     * the content type to send: the stored one, or {@value #OPAQUE} when
     * there is none.
     */
    static String apply(HttpServletResponse response, String contentType) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        String type = contentType == null || contentType.isBlank() ? OPAQUE : contentType;
        String base = baseType(type);
        if (PASSIVE.contains(base) || base.startsWith("video/") || base.startsWith("audio/")) {
            return type;
        }
        if (SVG.equals(base)) {
            response.setHeader("Content-Security-Policy", SVG_POLICY);
            return type;
        }
        response.setHeader("Content-Security-Policy", "sandbox");
        response.setHeader("Content-Disposition", "attachment");
        return type;
    }

    /**
     * The type without parameters, ASCII letters folded: "TEXT/HTML;
     * charset=utf-8" is text/html. Only A-Z fold (media types are ASCII), so
     * no Unicode case mapping can turn another string into an allowed type.
     */
    private static String baseType(String type) {
        int semicolon = type.indexOf(';');
        String base = (semicolon < 0 ? type : type.substring(0, semicolon)).trim();
        StringBuilder folded = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + 'a' - 'A') : c);
        }
        return folded.toString();
    }
}
