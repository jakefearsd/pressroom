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

package com.wikantik.pressroom.util.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the buffer that holds a rendered page on its way into the cache.
 *
 * Renderers write to the Writer this hands out; what ends up in the cache is
 * whatever had been flushed. Two things therefore matter: the bytes must come
 * back exactly as written, in UTF-8 (a blog is full of text that is not ASCII),
 * and content written but never flushed must not be served as if it were
 * complete.
 */
public class CachedContentTest {

    @Test
    public void writtenContentIsReadableOnceFlushed() throws IOException {
        try (CachedContent content = new CachedContent(64)) {
            content.getCachedWriter().print("hello world");
            content.flush();

            assertEquals("hello world", content.getContentAsString(),
                    "Flushed content must read back exactly as it was written");
            assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), content.getContent(),
                    "and the raw bytes handed to the servlet response must match too");
        }
    }

    @Test
    public void contentIsEncodedAsUtf8() throws IOException {
        String text = "café — 日本語";

        try (CachedContent content = new CachedContent(64)) {
            content.getCachedWriter().print(text);
            content.flush();

            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), content.getContent(),
                    "Cached pages must be encoded in UTF-8. The servlet response is written "
                            + "in UTF-8, so any other encoding here reaches the reader as "
                            + "mojibake.");
            assertEquals(text, content.getContentAsString(),
                    "and decoding it again must give back the original text");
        }
    }

    @Test
    public void unflushedContentIsNotVisible() throws IOException {
        try (CachedContent content = new CachedContent(64)) {
            content.getCachedWriter().print("half a page");

            assertEquals("", content.getContentAsString(),
                    "Content is only cached as of the last flush; a half-rendered page must "
                            + "not be readable, or a renderer that failed part way through "
                            + "would have its output cached and served");
            assertEquals(0, content.getContent().length, "so there are no bytes to hand out yet");
        }
    }

    @Test
    public void flushingAgainPicksUpWhatWasWrittenSince() throws IOException {
        try (CachedContent content = new CachedContent(64)) {
            content.getCachedWriter().print("first");
            content.flush();
            content.getCachedWriter().print(" second");
            content.flush();

            assertEquals("first second", content.getContentAsString(),
                    "Each flush must extend the cached content, not replace it");
        }
    }

    @Test
    public void closingCapturesAnythingStillBuffered() throws IOException {
        CachedContent content = new CachedContent(64);
        content.getCachedWriter().print("written but never flushed");

        content.close();

        assertEquals("written but never flushed", content.getContentAsString(),
                "close() must capture what was written since the last flush, so a renderer "
                        + "that closes without flushing still caches a complete page");
    }

    @Test
    public void closingIsIdempotent() throws IOException {
        CachedContent content = new CachedContent(64);
        content.getCachedWriter().print("content");
        content.close();

        content.close();

        assertEquals("content", content.getContentAsString(),
                "A second close() must be a no-op rather than losing the content; "
                        + "try-with-resources around an explicit close() is ordinary");
    }

    @Test
    public void flushingAfterCloseIsRejected() throws IOException {
        CachedContent content = new CachedContent(64);
        content.getCachedWriter().print("content");
        content.close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, content::flush,
                "Flushing a closed buffer must fail loudly: there is no stream left to "
                        + "flush, and quietly doing nothing would hide a renderer writing "
                        + "into the void");
        assertNotNull(thrown.getMessage(), "and the failure must say what happened");
    }

    @Test
    public void contentTypeIsCarriedAlongsideTheBytes() throws IOException {
        try (CachedContent typed = new CachedContent(64, "application/atom+xml; charset=utf-8");
             CachedContent untyped = new CachedContent(64)) {

            assertEquals("application/atom+xml; charset=utf-8", typed.getContentType(),
                    "A cached feed has to remember its content type -- it is served straight "
                            + "from the cache without being re-rendered");
            assertNull(untyped.getContentType(),
                    "and a buffer created without one must not invent it");
        }
    }

    @Test
    public void aNonPositiveSizeHintStillGivesAUsableBuffer() throws IOException {
        // callers pass an estimate of the page size; 0 or a negative estimate
        // is a caller bug, not a reason to fail the request
        try (CachedContent zero = new CachedContent(0);
             CachedContent negative = new CachedContent(-1)) {

            zero.getCachedWriter().print("content");
            zero.flush();
            assertEquals("content", zero.getContentAsString(),
                    "A size hint of 0 must fall back to a default buffer, not an unusable one");

            negative.getCachedWriter().print("content");
            negative.flush();
            assertEquals("content", negative.getContentAsString(),
                    "and so must a negative one -- sizing a buffer at -1 throws");
        }
    }
}
