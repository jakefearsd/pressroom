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
package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/v1 read a JSON body of any size: Tomcat's form-size limit does not
 * apply to JSON, so one request could make the server buffer and parse as
 * much as it was sent. Uploads keep their own limit (uploads.file.maxsize),
 * and /api/mcp has its transport's.
 */
class ApiBodyLimitFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX = ApiBodyLimitFilter.MAX_BODY_BYTES;

    private final ApiBodyLimitFilter filter = new ApiBodyLimitFilter(new ApiProblemWriter(JSON));
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private static MockHttpServletRequest json(String uri, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void aBodyOverTheCapIsRefusedWith413BeforeAnythingReadsIt() throws Exception {
        MockHttpServletRequest request = json("/api/v1/weblogs/myblog/entries", MAX + 1);

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        JsonNode problem = JSON.readTree(response.getContentAsString());
        assertEquals(413, problem.get("status").asInt());
        assertNull(chain.getRequest(), "nothing downstream ran");
    }

    @Test
    void aBodyAtTheCapGoesThrough() throws Exception {
        filter.doFilter(json("/api/v1/weblogs/myblog/entries", MAX), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    /** A chunked body: it declares no length. */
    private static MockHttpServletRequest undeclared(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/myblog/entries") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    private ServletInputStream bodySeenDownstream(HttpServletRequest request) throws Exception {
        filter.doFilter(request, response, chain);
        return ((HttpServletRequest) chain.getRequest()).getInputStream();
    }

    /** With no length to refuse up front, reading past the cap fails instead of buffering on. */
    @Test
    void aBodyOfUndeclaredLengthStopsBeingReadAtTheCap() throws Exception {
        InputStream body = bodySeenDownstream(undeclared(MAX + 1));

        assertThrows(IOException.class, body::readAllBytes);
    }

    /** A reader that takes one byte at a time is held to the same cap. */
    @Test
    void readingOneByteAtATimeAlsoStopsAtTheCap() throws Exception {
        InputStream body = bodySeenDownstream(undeclared(MAX + 1));

        assertThrows(IOException.class, () -> {
            while (body.read() != -1) {
                // drain
            }
        });
    }

    @Test
    void aBodyUnderTheCapReadsToItsEndOneByteAtATime() throws Exception {
        InputStream body = bodySeenDownstream(undeclared(3));

        assertEquals(0, body.read());
        assertEquals(0, body.read());
        assertEquals(0, body.read());
        assertEquals(-1, body.read());
    }

    /**
     * An asynchronous reader asks the stream whether it is finished or ready
     * and registers a listener; the capped stream answers from the real one,
     * or a non-blocking read would never start or never end.
     */
    @Test
    void anAsynchronousReaderSeesTheRealStreamsState() throws Exception {
        ServletInputStream real = mock(ServletInputStream.class);
        when(real.isFinished()).thenReturn(true);
        when(real.isReady()).thenReturn(false);
        MockHttpServletRequest request = undeclared(0);
        ServletInputStream body = bodySeenDownstream(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return real;
            }
        });
        ReadListener listener = mock(ReadListener.class);

        body.setReadListener(listener);

        assertTrue(body.isFinished());
        assertFalse(body.isReady());
        verify(real).setReadListener(listener);
    }

    @Test
    void anUploadKeepsItsOwnLimit() throws Exception {
        MockHttpServletRequest request = json("/api/v1/weblogs/myblog/media", MAX + 1);
        request.setContentType("multipart/form-data; boundary=x");

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void theMcpEndpointKeepsItsTransportsLimit() throws Exception {
        filter.doFilter(json("/api/mcp", MAX + 1), response, chain);

        assertNotNull(chain.getRequest());
    }
}
