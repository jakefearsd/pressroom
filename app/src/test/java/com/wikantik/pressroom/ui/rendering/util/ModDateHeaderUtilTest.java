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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModDateHeaderUtil}, the conditional-GET handling shared
 * by every rendering servlet.
 *
 * <p>Getting the comparison wrong in one direction serves stale pages that
 * readers cannot refresh away; getting it wrong in the other direction throws
 * away the cache and re-renders every page on every hit. The second-granularity
 * truncation is the subtle part: HTTP dates carry no milliseconds, so a page
 * modified at .500 must still count as "not modified" against an
 * If-Modified-Since taken at .000 of the same second.
 */
class ModDateHeaderUtilTest {

    private static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    /** An arbitrary whole-second instant, used as the client's cached timestamp. */
    private static final long ONE_SECOND_MARK = 1_000L;

    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost/roller/x"));
    }

    @Test
    void contentOlderThanTheClientCopyGetsA304() {
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(2 * ONE_SECOND_MARK);

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                ONE_SECOND_MARK);

        assertTrue(handled, "Content older than the client's copy must produce a 304");
        verify(response).setStatus(HttpServletResponse.SC_NOT_MODIFIED);
    }

    @Test
    void contentModifiedAtExactlyTheClientTimestampGetsA304() {
        // The comparison is `lastModified <= sinceDate`. Equality has to be on
        // the 304 side, otherwise every unchanged page is re-sent on the very
        // next request.
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(ONE_SECOND_MARK);

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                ONE_SECOND_MARK);

        assertTrue(handled, "An exact timestamp match must count as not modified");
        verify(response).setStatus(HttpServletResponse.SC_NOT_MODIFIED);
    }

    @Test
    void subSecondChangesWithinTheClientSecondStillGetA304() {
        // 1999ms truncates to 1000ms, which equals the client's timestamp. This
        // is the case the truncation exists for: without it, a weblog touched
        // mid-second would never return 304 again.
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(ONE_SECOND_MARK);

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                2 * ONE_SECOND_MARK - 1);

        assertTrue(handled,
                "1999ms must truncate to 1000ms and count as not modified; if this "
                        + "fails the second-granularity truncation was dropped");
        verify(response).setStatus(HttpServletResponse.SC_NOT_MODIFIED);
    }

    @Test
    void contentOneWholeSecondNewerIsSentAgain() {
        // 2000ms truncates to itself, which is past the client's 1000ms. One
        // millisecond more than the previous case, and the answer must flip.
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(ONE_SECOND_MARK);

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                2 * ONE_SECOND_MARK);

        assertFalse(handled,
                "A full second newer must be re-sent; if this returns true the "
                        + "comparison boundary is off by one second");
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void lastModifiedOnA304IsDerivedFromTheResourceNotEchoedFromTheRequest() {
        // The Last-Modified header on a 304 must describe the resource, not
        // repeat back whatever string the client happened to send as
        // If-Modified-Since. Echoing untrusted request-header input straight
        // into a response header is the response-splitting shape (modern
        // Tomcat rejects CR/LF in header values, so this was defence in depth
        // rather than a live exploit) -- and the echoed value was simply
        // wrong on its own terms besides: the caller already knows the
        // resource's real last-modified time and must use that.
        String clientHeader = "Wed, 21 Oct 2015 07:28:00 GMT";
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(2 * ONE_SECOND_MARK);
        when(request.getHeader(IF_MODIFIED_SINCE)).thenReturn(clientHeader);

        ModDateHeaderUtil.respondIfNotModified(request, response, ONE_SECOND_MARK);

        verify(response).setDateHeader("Last-Modified", ONE_SECOND_MARK);
        verify(response, never()).setHeader(anyString(), anyString());
    }

    @Test
    void missingIfModifiedSinceHeaderMeansSendTheContent() {
        // The servlet API reports an absent date header as -1, which is older
        // than any real modification time.
        when(request.getDateHeader(IF_MODIFIED_SINCE)).thenReturn(-1L);

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                ONE_SECOND_MARK);

        assertFalse(handled, "A first-time visitor must receive the content");
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void unparseableIfModifiedSinceHeaderMeansSendTheContent() {
        // Containers throw IllegalArgumentException for a date they cannot
        // parse. A broken client header must degrade to a normal 200, not a 500.
        when(request.getDateHeader(IF_MODIFIED_SINCE))
                .thenThrow(new IllegalArgumentException("not a date"));

        boolean handled = ModDateHeaderUtil.respondIfNotModified(request, response,
                ONE_SECOND_MARK);

        assertFalse(handled, "A malformed client header must fall back to sending content");
        verify(response, never()).setStatus(anyInt());
        verify(response, never()).setHeader(anyString(), anyString());
    }

    @Test
    void lastModifiedIsPublishedAlongsideAnAlreadyExpiredExpiresHeader() {
        // Expires: 0 is what forces clients to revalidate rather than serving
        // from cache without asking, which is what makes the 304 path above
        // reachable at all.
        ModDateHeaderUtil.setLastModifiedHeader(response, ONE_SECOND_MARK);

        verify(response).setDateHeader("Last-Modified", ONE_SECOND_MARK);
        verify(response).setDateHeader("Expires", 0L);
    }
}
