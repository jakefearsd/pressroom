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
package com.wikantik.pressroom.business;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;

/**
 * Interface to media file management.
 */
public interface MediaFileManager {

    int MAX_WIDTH = MediaFile.THUMBNAIL_MAX_WIDTH;
    int MAX_HEIGHT = MediaFile.THUMBNAIL_MAX_HEIGHT;

    /**
     * Initialization; deal with upgrade migrations, etc.
     */
    void initialize();

    /**
     * Release all resources associated with Roller session.
     */
    void release();

    /**
     * Create a media file
     */
    void createMediaFile(Weblog weblog, MediaFile mediaFile,
            PressroomMessages errors) throws WebloggerException;

    /**
     * Create theme media file.
     * Same as createMediaFile() except no checks are done for uploads disabled, etc.
     */
    void createThemeMediaFile(Weblog weblog, MediaFile mediaFile,
            PressroomMessages errors) throws WebloggerException;

    /**
     * Update metadata for a media file
     */
    void updateMediaFile(Weblog weblog, MediaFile mediaFile)
            throws WebloggerException;

    /**
     * Update metadata for a media file and content.
     */
    void updateMediaFile(Weblog website, MediaFile mf, InputStream fis)
            throws WebloggerException;

    /**
     * Get media file metadata by file id
     */
    MediaFile getMediaFile(String id) throws WebloggerException;

    /**
     * Get media file metadata optionally including the actual content
     */
    MediaFile getMediaFile(String id, boolean includeContent)
            throws WebloggerException;

    /**
     * Delete a media file
     */
    void removeMediaFile(Weblog weblog, MediaFile mediaFile)
            throws WebloggerException;

    /**
     * Search for media files based on the filter criteria
     */
    List<MediaFile> searchMediaFiles(Weblog weblog,
            MediaFileFilter filter) throws WebloggerException;

    /**
     * Create default folder for media files in a weblog.
     */
    MediaFileDirectory createDefaultMediaFileDirectory(Weblog weblog)
            throws WebloggerException;

    /**
     * Create a media file directory
     */
    void createMediaFileDirectory(MediaFileDirectory directory)
            throws WebloggerException;

    /**
     * Create a media file directory with a given name.
     */
    MediaFileDirectory createMediaFileDirectory(Weblog weblog,
            String name) throws WebloggerException;

    /**
     * Get media file directory by id
     */
    MediaFileDirectory getMediaFileDirectory(String id)
            throws WebloggerException;

    /**
     * Get media file directory by its path
     */
    MediaFileDirectory getMediaFileDirectoryByName(Weblog weblog,
            String name) throws WebloggerException;

    /**
     * Get media file by directory path (single folder + file name).
     */
    MediaFile getMediaFileByPath(Weblog weblog, String path)
            throws WebloggerException;

    /**
     * Get media file by the original path by which it was stored. Required for
     * support of old upload file URLs and for theme resources. {@inheritDoc}
     */
    MediaFile getMediaFileByOriginalPath(Weblog weblog, String origpath)
            throws WebloggerException;

    /**
     * The oldest file in {@code directory} whose content hash is {@code
     * contentHash}, or null. A file without a hash -- from before V032, or
     * replaced or cropped since -- never matches.
     */
    MediaFile getMediaFileByContentHash(MediaFileDirectory directory, String contentHash)
            throws WebloggerException;

    /**
     * Get the list of media file directories for the given weblog.
     */
    List<MediaFileDirectory> getMediaFileDirectories(Weblog weblog)
            throws WebloggerException;

    /**
     * Get the default folder for media files for the given weblog.
     */
    MediaFileDirectory getDefaultMediaFileDirectory(Weblog weblog)
            throws WebloggerException;

    /**
     * Move a set of media files to a new directory.
     */
    void moveMediaFiles(Collection<MediaFile> mediaFiles,
            MediaFileDirectory directory) throws WebloggerException;

    /**
     * Move one media file to a new directory.
     */
    void moveMediaFile(MediaFile mediaFile, MediaFileDirectory directory)
            throws WebloggerException;

    /**
     * Remove all media files associated with a weblog.
     * 
     * @param website
     */
    void removeAllFiles(Weblog website) throws WebloggerException;

    /**
     * Remove media file directory
     * 
     * @param mediaFileDir
     */
    void removeMediaFileDirectory(MediaFileDirectory mediaFileDir)
            throws WebloggerException;

    /**
     * Replace a media file's tags with {@code tags}: tags no longer named are
     * removed (through {@link #removeMediaFileTag}), new ones added, names
     * normalised in the weblog's locale. A null list leaves the tags alone.
     * This used to be {@code MediaFile.updateTags} -- a write issued from
     * inside the entity through the static service locator.
     */
    void updateTags(MediaFile file, List<String> tags) throws WebloggerException;

    /**
     * Remove tag with given name from given MediaFile
     *
     * @param name
     *            Name of tag to be removed
     */
    void removeMediaFileTag(String name, MediaFile entry)
            throws WebloggerException;

    /**
     * Regenerate the responsive-image rendition ladder for every image file
     * in the given weblog. Used for backfill after the rendition pipeline
     * was introduced (or after a ladder/format change) so existing uploads
     * catch up. Per-file failures are logged and do not abort the walk.
     *
     * @return the number of image files processed
     */
    int regenerateRenditions(Weblog weblog) throws WebloggerException;

    /**
     * Destructively crop an image media file: re-encode the stored original
     * to just the requested rectangle and rebuild everything derived from
     * its pixels -- stored width/height, the admin thumbnail, the whole
     * responsive rendition ladder, and the blurhash. The rectangle is
     * measured on the displayed (EXIF-orientation-corrected) image, exactly
     * as a browser presents it to the person drawing the selection, and is
     * clamped to the image bounds.
     *
     * <p>The stored EXIF metadata fields (camera, lens, exposure, ...) are
     * deliberately left as they are: they still truthfully describe the
     * photograph even though the re-encoded file itself no longer carries an
     * EXIF block. A focal point, if set, is remapped into the cropped
     * coordinate space.
     *
     * @throws WebloggerException when the file is not a croppable image,
     *         the rectangle lies outside the image, or storage fails.
     */
    void cropMediaFile(Weblog weblog, MediaFile mediaFile,
            int x, int y, int width, int height) throws WebloggerException;
}
