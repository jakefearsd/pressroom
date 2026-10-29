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

import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.Weblogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the computed properties of {@link MediaFile}.
 *
 * <p>The thumbnail geometry is the interesting part: it is derived lazily from
 * the stored width and height, and it must preserve the aspect ratio while
 * fitting inside {@link MediaFileManager#MAX_WIDTH} x
 * {@link MediaFileManager#MAX_HEIGHT}. Getting it wrong produces stretched
 * thumbnails throughout the media gallery, which no other test would catch.
 */
class MediaFileLogicTest {

    private MediaFile file;

    @BeforeEach
    void setUp() {
        file = new MediaFile();
        file.setName("holiday.jpg");
        file.setContentType("image/jpeg");
    }

    // ------------------------------------------------------------ file type

    @Test
    void imagesAreRecognisedFromTheirContentTypeWhateverItsCase() {
        file.setContentType("image/png");
        assertTrue(file.isImageFile());

        file.setContentType("IMAGE/PNG");
        assertTrue(file.isImageFile(),
                "Content types arrive from browsers and from the file store in whatever "
                        + "case they please, so the prefix match must be case insensitive");

        file.setContentType("application/pdf");
        assertFalse(file.isImageFile());

        file.setContentType("text/image-description");
        assertFalse(file.isImageFile(),
                "The match is on the content-type prefix; 'image' appearing later in the "
                        + "type must not count");
    }

    @Test
    void aFileWithNoContentTypeIsNotAnImage() {
        file.setContentType(null);
        assertFalse(file.isImageFile(),
                "A file uploaded without a content type must not be treated as an image; "
                        + "it would otherwise be sent to the thumbnailer and NPE there");
    }

    // ------------------------------------------------------------ thumbnails

    @Test
    void aSmallImageIsItsOwnThumbnail() {
        file.setWidth(80);
        file.setHeight(60);

        assertEquals(80, file.getThumbnailWidth(),
                "An image already smaller than the thumbnail box must not be scaled up "
                        + "-- upscaling produces a blurry thumbnail from a perfectly good image");
        assertEquals(60, file.getThumbnailHeight());
    }

    @Test
    void aWideImageIsScaledToTheMaximumWidthKeepingItsAspectRatio() {
        file.setWidth(400);
        file.setHeight(200);

        assertEquals(MediaFileManager.MAX_WIDTH, file.getThumbnailWidth(),
                "A landscape image is constrained by its width");
        assertEquals(MediaFileManager.MAX_WIDTH / 2, file.getThumbnailHeight(),
                "Halving the width must halve the height too; a thumbnail that ignores "
                        + "the aspect ratio comes out stretched in the gallery");
    }

    @Test
    void aTallImageIsScaledToTheMaximumHeightKeepingItsAspectRatio() {
        file.setWidth(200);
        file.setHeight(400);

        assertEquals(MediaFileManager.MAX_HEIGHT, file.getThumbnailHeight(),
                "A portrait image is constrained by its height");
        assertEquals(MediaFileManager.MAX_HEIGHT / 2, file.getThumbnailWidth());
    }

    /*
     * On the thumbnail geometry, three boundary conditions cannot be pinned by
     * any input, because changing them does not change the answer:
     *
     *   - "width > height" only takes the other branch when the two are equal,
     *     and a square is scaled to the same square either way;
     *   - "width > MAX_WIDTH" and "height > MAX_HEIGHT" only take the other
     *     branch at exactly the maximum, where scaling by a factor of 1 is a
     *     no-op.
     *
     * Likewise the two "== -1" checks guarding the lazy computation: the two
     * thumbnail fields have no setters and are only ever assigned together, so
     * no reachable state distinguishes checking one from checking the other.
     * Mutation testing reports all five as surviving; they are equivalent, and
     * an assertion that appeared to kill them would only be asserting the
     * behaviour of a state the class cannot be in.
     */

    @Test
    void thumbnailGeometryIsNotComputedForNonImages() {
        file.setContentType("application/pdf");
        file.setWidth(400);
        file.setHeight(200);

        assertEquals(-1, file.getThumbnailWidth(),
                "A PDF has no thumbnail; -1 is the 'not applicable' marker the gallery "
                        + "checks before rendering an <img>");
        assertEquals(-1, file.getThumbnailHeight());
    }

    // ---------------------------------------------------------------- content

    @Test
    void anExplicitlySuppliedStreamWinsOverTheStoredFile() throws Exception {
        // During upload the bytes are in hand before anything has been written
        // to the file store, so the in-memory stream has to take precedence.
        InputStream uploaded = new ByteArrayInputStream("uploaded".getBytes());
        file.setInputStream(uploaded);

        assertSame(uploaded, file.getInputStream(),
                "The stream handed to setInputStream must be the one returned");
    }

    @Test
    void theStoredFileIsUsedWhenNoStreamWasSupplied(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path directory) throws Exception {
        java.nio.file.Path onDisk = directory.resolve("beach.jpg");
        java.nio.file.Files.writeString(onDisk, "bytes");
        file.setContent(new FileContent(new Weblog(), "file-1", onDisk.toFile()));

        try (InputStream stream = file.getInputStream()) {
            assertEquals("bytes", new String(stream.readAllBytes()),
                    "With nothing supplied in memory the bytes must come from the file "
                            + "store rather than reading as absent");
        }

        java.nio.file.Path thumbOnDisk = directory.resolve("beach-thumb.jpg");
        java.nio.file.Files.writeString(thumbOnDisk, "thumb");
        file.setThumbnailContent(new FileContent(new Weblog(), "file-1", thumbOnDisk.toFile()));
        try (InputStream stream = file.getThumbnailInputStream()) {
            assertEquals("thumb", new String(stream.readAllBytes()),
                    "and the thumbnail must come from the thumbnail file, not the original");
        }
    }

    @Test
    void aFileWithNoContentAtAllReadsAsNull() {
        assertNull(file.getInputStream(),
                "A media file whose content has not been loaded must read as null so the "
                        + "caller can fetch it, rather than handing back an empty stream "
                        + "that looks like a zero byte file");
        assertNull(file.getThumbnailInputStream(),
                "Likewise for the thumbnail, which may legitimately not exist");
    }

    @Test
    void lastModifiedIsTheLastUpdatedTimestamp() {
        // Served as the HTTP Last-Modified header, so it must track the stored
        // timestamp rather than being a second clock reading.
        Timestamp updated = Timestamp.valueOf("2024-05-06 07:08:09");
        file.setLastUpdated(updated);

        assertEquals(updated.getTime(), file.getLastModified(),
                "getLastModified() feeds the HTTP caching headers and must be exactly the "
                        + "stored update time");
    }

    @Test
    void pathIsTheNameOfTheContainingDirectory() {
        MediaFileDirectory directory = new MediaFileDirectory();
        directory.setName("photos");
        file.setDirectory(directory);

        assertEquals("photos", file.getPath());
    }

    // -------------------------------------------------------------------- tags

    @Test
    void addingATagNormalisesAndRecordsIt() throws Exception {
        Weblog weblog = new Weblog();
        weblog.setLocale("en_US");
        file.setWeblog(weblog);

        file.addTag("Holiday");

        assertSame(weblog, file.getWeblog(),
                "Precondition: the file knows which weblog it belongs to, which is where "
                        + "the tag normalisation locale comes from");
        assertEquals(1, file.getTags().size());
        assertEquals("holiday", file.getTags().iterator().next().getName(),
                "Media file tags are lowercased for the same reason entry tags are: "
                        + "there is no reliable case mapping across languages");
        assertTrue(file.getAddedTags().contains("holiday"),
                "New tags must be recorded so the tag rows can be inserted on save");
        assertSame(file, file.getTags().iterator().next().getMediaFile(),
                "The tag must point back at the file that owns it");
    }

    @Test
    void tagsAreLowercasedInTheWeblogsOwnLocale() throws Exception {
        // Same reason as WeblogEntry.addTag: Turkish lowercases 'I' to the
        // dotless 'ı', so using the server default would rewrite the tags of
        // every blog whose language is not the server's.
        Weblog turkish = new Weblog();
        turkish.setLocale("tr");
        file.setWeblog(turkish);

        file.addTag("TITLE");

        assertEquals("tıtle", file.getTags().iterator().next().getName());
    }

    @Test
    void addingTheSameTagTwiceIsANoOp() throws Exception {
        file.addTag("holiday");
        file.addTag("HOLIDAY");

        assertEquals(1, file.getTags().size(),
                "The same tag in different case is one tag");
    }

    @Test
    void aSecondDifferentTagIsStillAdded() throws Exception {
        // The duplicate check scans the existing tags; if it bailed out on the
        // first tag that did *not* match, only ever one tag would stick.
        file.addTag("holiday");
        file.addTag("beach");

        assertEquals(2, file.getTags().size(),
                "A media file must be able to carry more than one tag");
        assertEquals(java.util.Set.of("beach", "holiday"),
                file.getTags().stream().map(MediaFileTag::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void tagsThatNormaliseToNothingAreDiscarded() throws Exception {
        file.addTag("  ");

        assertTrue(file.getTags().isEmpty(),
                "Whitespace normalises away to nothing, and an empty tag would produce a "
                        + "media search URL that matches everything");
    }

    @Test
    void tagsAsStringJoinsTheTagNames() throws Exception {
        file.addTag("holiday");
        file.addTag("beach");

        assertEquals(java.util.Set.of("holiday", "beach"),
                java.util.Set.of(file.getTagsAsString().split(" ")),
                "Every tag must appear in the field, separated by spaces");
        assertFalse(file.getTagsAsString().endsWith(" "),
                "and the separator after the last tag must be trimmed off, or the field "
                        + "grows a trailing space every time it is round-tripped");
    }

    @Test
    void aFileWithNoTagsRendersAsAnEmptyString() {
        assertEquals("", file.getTagsAsString(),
                "The edit form binds this straight into a text field; null would render "
                        + "as the literal text 'null'");
    }

    @Test
    void removedTagsAreRecordedForTheDeleteToReplay() throws Exception {
        file.onRemoveTag("holiday");

        assertTrue(file.getRemovedTags().contains("holiday"),
                "The manager replays removedTags to delete the rows; a tag removed "
                        + "without being recorded stays in the database forever");
    }

}
