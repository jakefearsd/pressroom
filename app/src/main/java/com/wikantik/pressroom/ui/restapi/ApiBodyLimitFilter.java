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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * stream that fails once it passes the cap with {@link BodyTooLargeException};
 * {@link ApiExceptionHandler} finds it under the JSON reader's failure and
 * answers the same 413 (0.16.3; it was a 400 unreadable body). Uploads
 * ({@code multipart/*}) keep their own limit, {@code uploads.file.maxsize};
 * {@code /api/mcp} keeps its transport's.
 *
 * <p>Built inline in the API security chain, never a {@code @Bean}: Boot
 * would register a bean filter for every request in the container.
 */
public class ApiBodyLimitFilter extends OncePerRequestFilter {

    /**
     * What a request carries besides the entry text: the title, summary,
     * tags and other fields, and the JSON framing. 1 MiB, far above any of
     * them, so the text is what the limit is sized for.
     */
    public static final int HEADROOM_BYTES = 1024 * 1024;

    /**
     * The largest request body /api/v1 reads, uploads aside, and the one
     * {@code /api/mcp} accepts: room for the longest entry the column cap
     * allows ({@link ColumnLimits#ENTRY_TEXT} characters) at the most UTF-8
     * spends on a character a JSON body does not escape (3 bytes, the rest of
     * the Basic Multilingual Plane; a supplementary character is 4 bytes for
     * two Java characters, so cheaper per character), plus
     * {@link #HEADROOM_BYTES}. Derived, so changing the cap moves it:
     * 4,048,576 bytes at 1,000,000 characters. Text full of characters JSON
     * must escape ({@code \u0000} is 6 bytes) can exceed it and is refused
     * 413, as an oversized body always was.
     */
    public static final int MAX_BODY_BYTES = 3 * ColumnLimits.ENTRY_TEXT + HEADROOM_BYTES;

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
            problemWriter.write(request, response, refusal());
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream capped;
            private BufferedReader reader;

            @Override
            public ServletInputStream getInputStream() throws IOException {
                if (capped == null) {
                    capped = new CappedInputStream(super.getInputStream());
                }
                return capped;
            }

            /** The same capped stream decoded, so a reader cannot bypass the cap. */
            @Override
            public BufferedReader getReader() throws IOException {
                if (reader == null) {
                    reader = new BufferedReader(new InputStreamReader(getInputStream(), charset()));
                }
                return reader;
            }

            /**
             * The request's charset, UTF-8 when it names none. An unknown name throws
             * UnsupportedEncodingException, as the container's own reader does (an
             * IOException, which Spring answers with a 400), never an unchecked one.
             */
            private Charset charset() throws UnsupportedEncodingException {
                String name = getCharacterEncoding();
                if (name == null) {
                    return StandardCharsets.UTF_8;
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    UnsupportedEncodingException refused = new UnsupportedEncodingException(name);
                    refused.initCause(e);
                    throw refused;
                }
            }
        }, response);
    }

    /** The 413 for a body over the cap, declared or found while reading. */
    static ApiException refusal() {
        return ApiException.problem(ProblemCode.REQUEST_TOO_LARGE, Integer.toString(MAX_BODY_BYTES));
    }

    /**
     * A read went past the cap. Its own type, so the refusal can be told
     * from any other failed read once the JSON reader has wrapped it.
     */
    static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("The request body is over " + MAX_BODY_BYTES + " bytes.");
        }
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
                throw new BodyTooLargeException();
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
