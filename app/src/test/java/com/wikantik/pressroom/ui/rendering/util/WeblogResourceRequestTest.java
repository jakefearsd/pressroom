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

package com.wikantik.pressroom.ui.rendering.util;

import com.wikantik.pressroom.business.Weblogger;
import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the three resource-serving request parsers:
 * {@link WeblogResourceRequest}, {@link WeblogMediaResourceRequest} and
 * {@link WeblogPreviewResourceRequest}.
 *
 * <p>All three override {@code isLocale()} to return false. That override is
 * the whole point of these classes: an uploads directory called "de" or "es"
 * would otherwise be eaten by the locale parsing in {@link WeblogRequest} and
 * the file underneath it would 404.
 */
class WeblogResourceRequestTest {

    private static final String RESOURCE_SERVLET = "/pressroom-ui/rendering/resources";

    private static WeblogResourceRequest parse(String pathInfo) throws InvalidRequestException {
        return new WeblogResourceRequest(mock(Weblogger.class), MockRequest.with(RESOURCE_SERVLET, pathInfo));
    }

    private static WeblogMediaResourceRequest parseMedia(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogMediaResourceRequest(mock(Weblogger.class), 
                MockRequest.with(RESOURCE_SERVLET, pathInfo, params));
    }

    // -------------------------------------------------- WeblogResourceRequest

    @Test
    void resourcePathIsEverythingAfterTheHandle() throws Exception {
        WeblogResourceRequest request = parse("/myblog/images/header.png");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("images/header.png", request.getResourcePath());
    }

    @Test
    void localeLikeDirectoryNameIsKeptAsPartOfThePath() throws Exception {
        // "de" would be a valid locale segment for a page request. For a
        // resource request it is a real directory in the weblog's uploads area,
        // and losing it would break every file inside it.
        WeblogResourceRequest request = parse("/myblog/de/logo.png");

        assertNull(request.getLocale(),
                "The resource servlet has no locale segment");
        assertEquals("de/logo.png", request.getResourcePath(),
                "A directory whose name looks like a locale must stay in the path");
    }

    @Test
    void missingPathIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog"),
                "A resource request must name a file");
    }

    @Test
    void singleCharacterPathIsRejected() {
        // The guard is `pathInfo.trim().length() > 1`, so a one-character
        // remainder sits exactly on the boundary.
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/x"),
                "A one-character remainder is on the wrong side of the length guard");
    }

    @Test
    void leadingSlashIsStrippedFromTheResourcePath() throws Exception {
        // A doubled slash in the URL leaves the remainder starting with "/".
        // The stored path is resolved against the upload directory, so a
        // leading slash would make it look absolute.
        WeblogResourceRequest request = parse("/myblog//images/header.png");

        assertEquals("images/header.png", request.getResourcePath(),
                "A leading slash must be stripped so the path stays relative to "
                        + "the weblog's upload directory");
    }

    @Test
    void plusSignInAFilenameIsPreservedRatherThanBecomingASpace() throws Exception {
        // ROL-1065: people upload files with '+' in the name and expect the
        // unescaped URL to work. The parser escapes '+' before decoding so it
        // survives.
        WeblogResourceRequest request = parse("/myblog/my+file.png");

        assertEquals("my+file.png", request.getResourcePath(),
                "A '+' in an uploaded filename must not decode to a space");
    }

    @Test
    void percentEscapesInAFilenameAreDecoded() throws Exception {
        WeblogResourceRequest request = parse("/myblog/my%20file.png");

        assertEquals("my file.png", request.getResourcePath());
    }

    @Test
    void malformedPercentEscapeIsRejectedAsAnInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/100%.png"),
                "A bare '%' must produce InvalidRequestException rather than an "
                        + "unchecked IllegalArgumentException");
    }

    @Test
    void traversalSegmentsSurviveParsingAndMustBeHandledByTheServlet() throws Exception {
        // Deliberately pinned: this parser does not sanitise the path, it only
        // decodes it. Anything that resolves the returned path against the
        // filesystem has to reject "..' itself. If a future change adds
        // sanitising here, this test should be updated to assert the rejection
        // rather than deleted.
        WeblogResourceRequest request = parse("/myblog/../../etc/passwd");

        assertEquals("../../etc/passwd", request.getResourcePath(),
                "The parser passes traversal segments through untouched; the "
                        + "consumer is responsible for confining the path");
    }

    // --------------------------------------------- WeblogMediaResourceRequest

    @Test
    void mediaResourceIdIsEverythingAfterTheHandle() throws Exception {
        WeblogMediaResourceRequest request = parseMedia("/myblog/abc123");

        assertEquals("abc123", request.getResourceId());
    }

    @Test
    void mediaResourceIdIsNotDecoded() throws Exception {
        // Media files are addressed by opaque id, so unlike the file-path form
        // there is nothing to decode. Pinning this keeps a well-meaning "add a
        // decode for symmetry" change from mangling ids.
        WeblogMediaResourceRequest request = parseMedia("/myblog/a%2Bb");

        assertEquals("a%2Bb", request.getResourceId());
    }

    @Test
    void mediaLocaleLikeSegmentIsKeptInTheId() throws Exception {
        WeblogMediaResourceRequest request = parseMedia("/myblog/de/logo.png");

        assertNull(request.getLocale());
        assertEquals("de/logo.png", request.getResourceId());
    }

    @Test
    void mediaRequestWithoutAnIdIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parseMedia("/myblog"),
                "A media resource request must name a resource");
    }

    @Test
    void mediaRequestWithASingleCharacterIdIsRejected() {
        // The guard is `pathInfo.trim().length() > 1`, so a one-character
        // remainder sits exactly on it. Media ids are generated UUIDs and are
        // never one character, so accepting one would send a junk id to the
        // media file lookup instead of returning a clean bad-request.
        assertThrows(InvalidRequestException.class, () -> parseMedia("/myblog/x"),
                "A one-character remainder is on the wrong side of the length guard");
    }

    @Test
    void mediaRequestWithATwoCharacterIdIsAccepted() {
        // One character past the guard: the shortest id that must work.
        assertDoesNotThrowMedia("/myblog/xy", "xy");
    }

    private static void assertDoesNotThrowMedia(String pathInfo, String expectedId) {
        try {
            assertEquals(expectedId, parseMedia(pathInfo).getResourceId(),
                    "A two-character id is past the length guard and must be accepted");
        } catch (InvalidRequestException ex) {
            throw new AssertionError("A two-character media id must be accepted, but "
                    + "parsing threw: " + ex.getMessage(), ex);
        }
    }

    @Test
    void thumbnailFlagRequiresTheExactValueTrue() throws Exception {
        // The check is `"true".equals(...)`, so anything else serves the full
        // image. Worth pinning because serving a full-size original where a
        // thumbnail was meant is a bandwidth problem, not a visible error.
        assertTrue(parseMedia("/myblog/abc123", "t", "true").isThumbnail());
        assertFalse(parseMedia("/myblog/abc123", "t", "TRUE").isThumbnail(),
                "The thumbnail flag is case-sensitive");
        assertFalse(parseMedia("/myblog/abc123", "t", "1").isThumbnail());
        assertFalse(parseMedia("/myblog/abc123").isThumbnail(),
                "Without ?t= the full-size resource is served");
    }

    // -------------------------------------- WeblogPreviewResourceRequest

    @Test
    void previewResourceCarriesTheThemeBeingPreviewed() throws Exception {
        HttpServletRequest servletRequest =
                MockRequest.with(RESOURCE_SERVLET, "/myblog/images/header.png",
                        "theme", "journal");

        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest(mock(Weblogger.class), servletRequest);

        assertEquals("journal", request.getThemeName());
        assertEquals("images/header.png", request.getResourcePath(),
                "The preview variant must still parse the resource path");
    }

    @Test
    void previewResourceNeverReportsALoggedInUser() throws Exception {
        // A preview renders the weblog as a visitor would see it. Reporting the
        // author as logged in would leak edit links into the preview.
        HttpServletRequest servletRequest =
                MockRequest.with(RESOURCE_SERVLET, "/myblog/images/header.png");
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("author");
        when(servletRequest.getUserPrincipal()).thenReturn(principal);

        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest(mock(Weblogger.class), servletRequest);

        assertNull(request.getAuthenticUser(),
                "A preview must never expose the authenticated user");
        assertFalse(request.isLoggedIn(),
                "A preview must always render as logged out");
    }

    @Test
    void previewResourceThemeLookupIsSkippedWhenNoThemeWasNamed() {
        // getTheme() reaches the ThemeManager through the business tier, which
        // is not bootstrapped here; the null-name guard keeps it from trying.
        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest();

        assertNull(request.getTheme(), "With no theme name there is nothing to look up");
    }

    @Test
    void previewResourceInjectedThemeIsReturnedWithoutALookup() {
        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest();
        com.wikantik.pressroom.pojos.Theme theme =
                mock(com.wikantik.pressroom.pojos.Theme.class);
        request.setTheme(theme);

        assertEquals(theme, request.getTheme());
    }
}
