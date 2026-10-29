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
 * Unit tests for {@link WeblogPreviewRequest}.
 *
 * <p>A preview reuses the whole page-request parser and changes three things:
 * it answers to a different servlet, it can be pointed at an unsaved theme or
 * entry, and it always renders as though nobody is logged in. That last point
 * is a correctness requirement -- a preview showing edit links would be
 * misleading, and the preview is also reachable for entries that are still
 * drafts.
 */
class WeblogPreviewRequestTest {

    private static final String PREVIEW_SERVLET = "/roller-ui/authoring/preview";
    private static final String PAGE_SERVLET = "/roller-ui/rendering/page";

    private static WeblogPreviewRequest parse(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogPreviewRequest(mock(Weblogger.class), MockRequest.with(PREVIEW_SERVLET, pathInfo, params));
    }

    @Test
    void previewAnswersToThePreviewServletOnly() throws Exception {
        WeblogPreviewRequest request = parse("/myblog");

        assertTrue(request.isValidDestination(PREVIEW_SERVLET));
        assertFalse(request.isValidDestination(PAGE_SERVLET),
                "The live page servlet must not be able to serve a preview, or "
                        + "draft content would be published by URL alone");
        assertFalse(request.isValidDestination(null));
    }

    @Test
    void requestOnThePageServletIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogPreviewRequest(mock(Weblogger.class), MockRequest.with(PAGE_SERVLET, "/myblog")),
                "A preview request aimed at the live page servlet must be rejected");
    }

    @Test
    void pathParsingIsInheritedFromThePageRequest() throws Exception {
        WeblogPreviewRequest request = parse("/myblog/en_US/entry/hello-world");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("en_US", request.getLocale());
        assertEquals("hello-world", request.getWeblogAnchor());
    }

    @Test
    void themeParameterNamesTheThemeBeingPreviewed() throws Exception {
        assertEquals("journal", parse("/myblog", "theme", "journal").getThemeName());
        assertNull(parse("/myblog").getThemeName(),
                "With no ?theme= the weblog's current theme is previewed");
    }

    @Test
    void typeDefaultsToStandardAndIsOverridableByParameter() throws Exception {
        assertEquals("standard", parse("/myblog").getType(),
                "The default preview type must stay \"standard\"; templates branch on it");
        assertEquals("landing", parse("/myblog", "type", "landing").getType());
    }

    @Test
    void previewEntryParameterIsDecoded() throws Exception {
        WeblogPreviewRequest request = parse("/myblog", "previewEntry", "c%2B%2B-tips");

        assertEquals("c++-tips", request.getPreviewEntry());
    }

    @Test
    void malformedPercentEscapeInPreviewEntryIsRejectedAsAnInvalidRequest() {
        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog", "previewEntry", "50%"),
                "A bare '%' must produce InvalidRequestException rather than an "
                        + "unchecked IllegalArgumentException");
    }

    @Test
    void previewNeverReportsALoggedInUserEvenWhenTheAuthorIsAuthenticated() throws Exception {
        HttpServletRequest servletRequest = MockRequest.with(PREVIEW_SERVLET, "/myblog");
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("author");
        when(servletRequest.getUserPrincipal()).thenReturn(principal);

        WeblogPreviewRequest request = new WeblogPreviewRequest(mock(Weblogger.class), servletRequest);

        assertNull(request.getAuthenticUser(),
                "A preview must render as a visitor sees it, with no author identity");
        assertFalse(request.isLoggedIn(),
                "A preview must always report logged out, or templates emit edit links");
    }

    @Test
    void themeLookupIsSkippedWhenNoThemeWasNamed() throws Exception {
        // getTheme() reaches the ThemeManager through the business tier, which
        // is not bootstrapped in a unit test.
        WeblogPreviewRequest request = parse("/myblog");

        assertNull(request.getTheme(), "With no theme name there is nothing to look up");
    }

    @Test
    void injectedThemeIsReturnedWithoutALookup() throws Exception {
        WeblogPreviewRequest request = parse("/myblog");
        com.wikantik.pressroom.pojos.Theme theme =
                mock(com.wikantik.pressroom.pojos.Theme.class);
        request.setTheme(theme);

        assertEquals(theme, request.getTheme());
    }

    @Test
    void entryLookupIsSkippedWhenNeitherPreviewEntryNorAnchorIsPresent() throws Exception {
        WeblogPreviewRequest request = parse("/myblog");

        assertNull(request.getWeblogEntry(),
                "With nothing identifying an entry the business tier must not be touched");
    }

    @Test
    void injectedEntryIsReturnedWithoutALookup() throws Exception {
        WeblogPreviewRequest request = parse("/myblog", "previewEntry", "hello");
        com.wikantik.pressroom.pojos.WeblogEntry entry =
                new com.wikantik.pressroom.pojos.WeblogEntry();
        request.setWeblogEntry(entry);

        assertEquals(entry, request.getWeblogEntry());
    }
}
