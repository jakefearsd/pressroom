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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileComparator;
import org.apache.roller.weblogger.pojos.MediaFileComparator.MediaFileComparatorType;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator.DirectoryComparatorType;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Browse media files action (overlay mode).
 */
@Controller
@RequestMapping("/roller-ui/authoring/overlay")
public class MediaFileImageChooserController extends MediaFileBase {

    private static final Logger log = LoggerFactory.getLogger(MediaFileImageChooserController.class);

    /** The page editor's image chooser, on the site space as on a blog. */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileImageChooser";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileImageChooser.title";
    }

    @GetMapping("/mediaFileImageChooser.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "directoryId", required = false) String directoryId,
                          @RequestParam(value = "directoryName", required = false) String directoryName,
                          @RequestParam(value = "imagesOnly", required = false) boolean imagesOnly) {
        populateCommonModel(request, model);
        model.addAttribute("overlayMode", true);
        model.addAttribute("imagesOnly", imagesOnly);

        MediaFileManager manager = weblogger.getMediaFileManager();
        try {
            MediaFileDirectory directory;
            if (directoryId != null) {
                // getMediaFileDirectory is a global by-id lookup and this page
                // renders the whole directory listing.
                directory = ownedDirectory(directoryId, request);
                if (directory == null) {
                    log.warn("Refusing to browse directory {}: not owned by weblog {}",
                            directoryId, getActionWeblog(request).getHandle());
                    addError(model, "MediaFile.error.view", request);
                    return ".MediaFileImageChooser";
                }
            } else if (directoryName != null) {
                directory = manager.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
                directoryId = directory.getId();
            } else {
                directory = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
                directoryId = directory.getId();
            }

            // imagesOnly: the featured-image and social-image pickers, where
            // a non-image could only be refused after it was clicked.
            List<MediaFile> childFiles = new ArrayList<>(directory.getMediaFiles());
            if (imagesOnly) {
                childFiles.removeIf(file -> !file.isImageFile());
            }
            childFiles.sort(new MediaFileComparator(MediaFileComparatorType.NAME));

            model.addAttribute("childFiles", childFiles);
            model.addAttribute("currentDirectory", directory);
            model.addAttribute("directoryId", directoryId);

            // List of available directories
            List<MediaFileDirectory> sortedDirList = new ArrayList<>();
            List<MediaFileDirectory> directories = manager.getMediaFileDirectories(getActionWeblog(request));
            for (MediaFileDirectory mediaFileDirectory : directories) {
                if (!"default".equals(mediaFileDirectory.getName()) || !"default".equals(directory.getName())) {
                    sortedDirList.add(mediaFileDirectory);
                }
            }
            sortedDirList.sort(new MediaFileDirectoryComparator(DirectoryComparatorType.NAME));
            model.addAttribute("allDirectories", sortedDirList);

            // build hierarchy
            List<KeyValueObject> directoryHierarchy = new ArrayList<>();
            String fullPath = "/" + directory.getName();
            if (fullPath.length() > 1) {
                String[] directoryNames = fullPath.substring(1).split("/");
                StringBuilder dirPath = new StringBuilder();
                for (String dirName : directoryNames) {
                    dirPath.append('/').append(dirName);
                    directoryHierarchy.add(new KeyValueObject(dirPath.toString(), dirName));
                }
            }
            model.addAttribute("currentDirectoryHierarchy", directoryHierarchy);

        } catch (Exception ex) {
            log.error("Error viewing media file directory", ex);
            addError(model, "MediaFile.error.view", request);
        }

        return ".MediaFileImageChooser";
    }
}
