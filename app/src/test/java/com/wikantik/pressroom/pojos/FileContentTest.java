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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link FileContent}, the adapter between a media file record and the
 * bytes on disk.
 *
 * <p>Its accessors feed the HTTP response that serves an uploaded file:
 * {@code getLength} becomes Content-Length and {@code getLastModified} becomes
 * Last-Modified. Both have to reflect the file actually on disk rather than
 * anything cached on the record, or browsers cache a stale image or truncate
 * a download.
 */
class FileContentTest {

    @Test
    void theAccessorsDescribeTheFileOnDisk(@TempDir Path directory) throws Exception {
        Path onDisk = directory.resolve("beach.jpg");
        Files.writeString(onDisk, "twelve bytes", StandardCharsets.UTF_8);
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");

        FileContent content = new FileContent(weblog, "file-1", onDisk.toFile());

        assertSame(weblog, content.getWeblog());
        assertEquals("file-1", content.getFileId());
        assertEquals("beach.jpg", content.getName(),
                "The name is the file's own, not the id it is stored under");
        assertEquals(Files.size(onDisk), content.getLength(),
                "getLength() becomes the Content-Length header; a wrong value truncates "
                        + "the download or leaves the client waiting for bytes that never come");
        assertEquals(onDisk.toFile().lastModified(), content.getLastModified(),
                "getLastModified() becomes the Last-Modified header, so it must come from "
                        + "the file rather than being a fresh clock reading");
    }

    @Test
    void theContentIsReadableAsAStream(@TempDir Path directory) throws Exception {
        Path onDisk = directory.resolve("note.txt");
        Files.writeString(onDisk, "hello", StandardCharsets.UTF_8);

        FileContent content = new FileContent(new Weblog(), "file-1", onDisk.toFile());

        try (InputStream stream = content.getInputStream()) {
            assertEquals("hello", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aMissingFileFailsLoudlyRatherThanServingNothing(@TempDir Path directory) {
        // The record says the file exists; if the bytes are gone, the caller has
        // to hear about it rather than serve an empty 200 response.
        File missing = directory.resolve("deleted.jpg").toFile();
        FileContent content = new FileContent(new Weblog(), "file-1", missing);

        assertThrows(RuntimeException.class, content::getInputStream,
                "A file record pointing at bytes that are no longer on disk must raise, "
                        + "not hand back an empty stream that renders as a broken image");
    }
}
