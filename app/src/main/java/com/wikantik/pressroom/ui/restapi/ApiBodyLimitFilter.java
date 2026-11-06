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
import java.util.Locale;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body /api/v1 reads at {@link #MAX_BODY_BYTES} (code
 * review, 2026-11-03). Tomcat's form-size limit does not apply to JSON, so a
 * single request could make the server buffer and parse as much as it was
 * sent.
 *
 * <p>A body that declares a larger length is refused with a 413 before
 * anything reads it. One that declares none (chunked) is read through a
 * stream that fails once it passes the cap, which the JSON reader reports as
 * an unreadable body. Uploads ({@code multipart/*}) keep their own limit,
 * {@code uploads.file.maxsize}; {@code /api/mcp} keeps its transport's.
 *
 * <p>Built inline in the API security chain, never a {@code @Bean}: Boot
 * would register a bean filter for every request in the container.
 */
public class ApiBodyLimitFilter extends OncePerRequestFilter {

    /** The largest request body /api/v1 reads, uploads aside: 2 MiB, as {@code /api/mcp}. */
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final String API_V1 = "/api/v1/";

    private final ApiProblemWriter problemWriter;

    public ApiBodyLimitFilter(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    @SuppressFBWarnings(
            value = "SERVLET_CONTENT_TYPE",
            justification = "Only chooses which limit applies: a client that labels a JSON body multipart "
                    + "skips this cap but then fails the JSON routes (415) and meets the multipart one.")
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String type = request.getContentType();
        return !path.startsWith(API_V1)
                || type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            problemWriter.write(response, ApiException.tooLarge(
                    "The request body is over " + MAX_BODY_BYTES + " bytes.").toProblem(request.getRequestURI()));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream capped;

            @Override
            public ServletInputStream getInputStream() throws IOException {
                if (capped == null) {
                    capped = new CappedInputStream(super.getInputStream());
                }
                return capped;
            }
        }, response);
    }

    /** Fails a read that goes past the cap rather than buffering on. */
    private static final class CappedInputStream extends ServletInputStream {

        private final ServletInputStream in;
        private long read;

        CappedInputStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) {
                throw new IOException("The request body is over " + MAX_BODY_BYTES + " bytes.");
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
