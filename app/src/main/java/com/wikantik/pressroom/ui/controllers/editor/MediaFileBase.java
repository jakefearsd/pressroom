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
package com.wikantik.pressroom.ui.controllers.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileDirectoryComparator;
import com.wikantik.pressroom.pojos.MediaFileDirectoryComparator.DirectoryComparatorType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.ui.Model;

/**
 * Base class for all controllers related to media files.
 *
 * <p>Every media id and directory id on these screens arrives as client input,
 * and both {@code MediaFileManager.getMediaFile} and
 * {@code getMediaFileDirectory} are <em>global</em> by-id lookups: they answer
 * for any weblog on the site. The permission interceptor only establishes that
 * the caller may edit the <em>action</em> weblog, so re-establishing the weblog
 * boundary for the resolved object is this tier's job. {@link #ownedMediaFile}
 * and {@link #ownedDirectory} are the single place that happens; nothing in
 * these controllers should call the global lookups directly.
 */
public abstract class MediaFileBase extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MediaFileBase.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    /**
     * The directory with this id, but only when it belongs to the action
     * weblog; {@code null} for a blank id, an unknown id, or a foreign one.
     * Callers must treat all three the same way -- telling them apart would
     * confirm the existence of another weblog's folder.
     */
    protected MediaFileDirectory ownedDirectory(String directoryId, HttpServletRequest request)
            throws WebloggerException {
        if (StringUtils.isEmpty(directoryId)) {
            return null;
        }
        MediaFileDirectory directory =
                weblogger.getMediaFileManager().getMediaFileDirectory(directoryId);
        if (directory == null || !belongsToActionWeblog(directory.getWeblog(), request)) {
            return null;
        }
        return directory;
    }

    /**
     * The media file with this id, but only when it belongs to the action
     * weblog; {@code null} otherwise, on the same terms as
     * {@link #ownedDirectory}.
     *
     * <p>Ownership is read off the containing directory when there is one --
     * that is the relationship the on-disk layout and the privacy rules both
     * follow -- and falls back to the file's own weblog column for a file that
     * is not yet filed anywhere.
     */
    protected MediaFile ownedMediaFile(String mediaFileId, HttpServletRequest request)
            throws WebloggerException {
        if (StringUtils.isEmpty(mediaFileId)) {
            return null;
        }
        MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(mediaFileId);
        if (mediaFile == null) {
            return null;
        }
        Weblog owner = mediaFile.getDirectory() != null
                && mediaFile.getDirectory().getWeblog() != null
                ? mediaFile.getDirectory().getWeblog()
                : mediaFile.getWeblog();
        return belongsToActionWeblog(owner, request) ? mediaFile : null;
    }

    private boolean belongsToActionWeblog(Weblog owner, HttpServletRequest request) {
        Weblog actionWeblog = getActionWeblog(request);
        if (owner == null || owner.getId() == null || actionWeblog == null) {
            return false;
        }
        return owner.getId().equals(actionWeblog.getId());
    }

    /**
     * Deletes media file.
     */
    protected void doDeleteMediaFile(String mediaFileId, HttpServletRequest request, Model model) {
        try {
            log.debug("Processing delete of file id - {}", mediaFileId);
            MediaFile mediaFile = ownedMediaFile(mediaFileId, request);
            if (mediaFile == null) {
                addError(model, "mediaFile.delete.error.single", request);
                return;
            }
            weblogger.getMediaFileManager().removeMediaFile(getActionWeblog(request), mediaFile);
            weblogger.flush();
            weblogger.release();
            addMessage(model, "mediaFile.delete.success", request);
        } catch (WebloggerException e) {
            log.error("Error deleting media file", e);
            addError(model, "mediaFile.delete.error.single", request);
        }
    }

    /**
     * Delete selected media files.
     */
    protected void doDeleteSelected(String[] selectedMediaFiles, HttpServletRequest request, Model model) {
        try {
            MediaFileManager manager = weblogger.getMediaFileManager();

            if (selectedMediaFiles != null && selectedMediaFiles.length > 0) {
                log.debug("Processing delete of {} media files.", selectedMediaFiles.length);
                for (String fileId : selectedMediaFiles) {
                    log.debug("Deleting media file - {}", fileId);
                    MediaFile mediaFile = ownedMediaFile(fileId, request);
                    if (mediaFile != null) {
                        manager.removeMediaFile(getActionWeblog(request), mediaFile);
                    }
                }
            }

            weblogger.getWeblogManager().saveWeblog(getActionWeblog(request));
            weblogger.flush();
            weblogger.release();
            addMessage(model, "mediaFile.delete.success", request);

        } catch (WebloggerException e) {
            log.error("Error deleting selected media files", e);
            addError(model, "mediaFile.delete.error", request);
        }
    }

    /**
     * Move selected media files to a directory.
     */
    protected void doMoveSelected(String[] selectedMediaFiles, String selectedDirectory,
                                  HttpServletRequest request, Model model) {
        try {
            int movedFiles = 0;
            MediaFileManager manager = weblogger.getMediaFileManager();

            if (selectedMediaFiles != null && selectedMediaFiles.length > 0) {
                log.debug("Processing move of {} media files.", selectedMediaFiles.length);
                MediaFileDirectory targetDirectory = ownedDirectory(selectedDirectory, request);
                if (targetDirectory == null) {
                    log.warn("Refusing to move media files into directory {}: not owned by weblog {}",
                            selectedDirectory, getActionWeblog(request).getHandle());
                    addError(model, "mediaFile.move.errors", request);
                    return;
                }
                for (String fileId : selectedMediaFiles) {
                    log.debug("Moving media file - {} to directory - {}", fileId, selectedDirectory);
                    MediaFile mediaFile = ownedMediaFile(fileId, request);
                    if (mediaFile != null && !mediaFile.getDirectory().getId().equals(targetDirectory.getId())) {
                        manager.moveMediaFile(mediaFile, targetDirectory);
                        movedFiles++;
                    }
                }
            }

            weblogger.flush();
            weblogger.release();
            if (movedFiles > 0) {
                addMessage(model, "mediaFile.move.success", request);
            }

        } catch (WebloggerException e) {
            log.error("Error moving selected media files", e);
            addError(model, "mediaFile.move.errors", request);
        }
    }

    /**
     * Refresh the list of directories.
     */
    protected List<MediaFileDirectory> refreshAllDirectories(HttpServletRequest request) {
        try {
            MediaFileManager mgr = weblogger.getMediaFileManager();
            List<MediaFileDirectory> directories = mgr.getMediaFileDirectories(getActionWeblog(request));
            List<MediaFileDirectory> sortedDirList = new ArrayList<>(directories);
            sortedDirList.sort(new MediaFileDirectoryComparator(DirectoryComparatorType.NAME));
            return sortedDirList;
        } catch (WebloggerException ex) {
            log.error("Error looking up media file directories", ex);
        }
        return Collections.emptyList();
    }
}
