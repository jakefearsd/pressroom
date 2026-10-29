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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import com.wikantik.pressroom.ui.rendering.RenderingException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RenderingServletUtils}.
 */
class RenderingServletUtilsTest {

    @Test
    void aSuccessfulRenderReturnsTheBufferAndSendsNoErrorResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        var output = RenderingServletUtils.render(
                (model, writer) -> {
                    try {
                        writer.write("hi");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                Map.of(), 1024, "test", response);

        assertEquals("hi", output.getContentAsString());
        assertEquals(200, response.getStatus());
    }

    @Test
    void aRenderingFailureSends404AndReturnsNull() throws Exception {
        // The buffer's own best-effort close() cannot be made to throw from a
        // real CachedContent (ByteArrayOutputStream/PrintWriter never do), so
        // this covers the render-failure path up to and including the 404 --
        // not the doubly-nested close-failure log line, which is genuinely
        // unreachable without a production seam built solely for the test.
        MockHttpServletResponse response = new MockHttpServletResponse();

        var output = RenderingServletUtils.render(
                (model, writer) -> {
                    throw new RenderingException("boom");
                },
                Map.of(), 1024, "test", response);

        assertNull(output);
        assertEquals(404, response.getStatus());
    }

    /**
     * A reader who leaves mid-download is not a server error: Tomcat reports
     * the closed connection as a ClientAbortException on the next write, once
     * the response is committed. That was logged at ERROR with a stack trace,
     * once per abandoned file (MediaResourceServlet has the same rule).
     */
    @Test
    void aReaderWhoLeavesMidDownloadIsLoggedAtDebugOnly() throws Exception {
        AbandonedResponse response = new AbandonedResponse();

        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            RenderingServletUtils.streamResource(
                    new ByteArrayInputStream(new byte[] {1, 2, 3}), response, "theme file journal/x.css");

            assertTrue(capture.lines(Level.ERROR).isEmpty(), "logged as an error: " + capture.lines(Level.ERROR));
            assertTrue(capture.lines(Level.WARN).isEmpty(), "logged as a warning: " + capture.lines(Level.WARN));
            assertTrue(capture.lines(Level.DEBUG).stream().anyMatch(line -> line.contains("journal/x.css")),
                    "the abandoned file is still named at DEBUG: " + capture.lines(Level.DEBUG));
        }
        assertEquals(200, response.getStatus(), "nothing is sent after the reader has gone");
    }

    /** A file that cannot be read is a real failure: ERROR, naming it, and a 500. */
    @Test
    void aFileThatCannotBeReadIsAnErrorThatNamesIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }
        };

        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            RenderingServletUtils.streamResource(unreadable, response, "theme file journal/y.css");

            assertTrue(capture.lines(Level.ERROR).stream().anyMatch(line -> line.contains("journal/y.css")),
                    "a read failure is an error naming the file: " + capture.lines(Level.ERROR));
        }
        assertEquals(500, response.getStatus());
    }

    /** A response whose reader closed the connection once the first bytes went out. */
    private static final class AbandonedResponse extends MockHttpServletResponse {
        private final ServletOutputStream gone = new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw abort();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw abort();
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // not asynchronous
            }
        };

        private IOException abort() {
            setCommitted(true);
            return new ClientAbortException(new IOException("Broken pipe"));
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return gone;
        }
    }
}
