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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the lookups on {@link MediaFileDirectory}.
 *
 * <p>{@code hasMediaFile} is what the upload path uses to decide whether a
 * filename collides, and {@code isEmpty} is what the delete path uses to decide
 * whether a directory can be removed. Both walk the file set by name, so a
 * mistake in either loses a file or refuses a legitimate upload.
 */
class MediaFileDirectoryTest {

    private Weblog weblog;
    private MediaFileDirectory directory;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        directory = new MediaFileDirectory(weblog, "photos", "Holiday snaps");
    }

    private static MediaFile file(String name) {
        MediaFile file = new MediaFile();
        file.setName(name);
        return file;
    }

    @Test
    void theConstructorAttachesTheDirectoryToItsWeblog() {
        assertSame(weblog, directory.getWeblog());
        assertEquals("photos", directory.getName());
        assertEquals("Holiday snaps", directory.getDescription());
        assertEquals(List.of(directory), weblog.getMediaFileDirectories(),
                "A directory that did not register itself with its weblog would be "
                        + "invisible to Weblog.getMediaFileDirectory() until the next reload");
    }

    @Test
    void aDirectoryWithNoFilesIsEmpty() {
        assertTrue(directory.isEmpty(),
                "A freshly created directory holds nothing; reporting otherwise would "
                        + "block the delete that removes it");

        directory.getMediaFiles().add(file("beach.jpg"));
        assertFalse(directory.isEmpty(),
                "and one holding a file must not be deletable as if it were empty");
    }

    @Test
    void filesAreFoundByName() {
        MediaFile beach = file("beach.jpg");
        directory.setMediaFiles(new HashSet<>(List.of(beach, file("sunset.jpg"))));

        assertTrue(directory.hasMediaFile("beach.jpg"));
        assertSame(beach, directory.getMediaFile("beach.jpg"),
                "The lookup must return the file that matches, not merely some file");

        assertFalse(directory.hasMediaFile("missing.jpg"),
                "A name that is not present must not collide, or the uploader would "
                        + "refuse every new filename");
        assertNull(directory.getMediaFile("missing.jpg"),
                "and must read as absent rather than returning an arbitrary other file");
    }

    @Test
    void aDirectoryWithNoFileSetAtAllStillAnswersLookups() {
        // The collection is nullable in the ORM mapping, and these lookups run
        // before it has been populated on a lazily loaded directory.
        directory.setMediaFiles(null);

        assertFalse(directory.hasMediaFile("beach.jpg"),
                "A null file set must read as 'no such file' rather than NPE");
        assertNull(directory.getMediaFile("beach.jpg"));
    }
}
