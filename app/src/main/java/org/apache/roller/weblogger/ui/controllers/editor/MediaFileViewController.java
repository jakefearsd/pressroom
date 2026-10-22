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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileComparator;
import org.apache.roller.weblogger.pojos.MediaFileComparator.MediaFileComparatorType;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * View media files.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MediaFileViewController extends MediaFileBase {

    private static final Logger log = LoggerFactory.getLogger(MediaFileViewController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileView";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileView.title";
    }

    // Both verbs, like entryAddWithMediaFile.rol: browsing is a GET, but the
    // view page's own form (the sort-by onchange and the edit modal's
    // onEditSuccess() both call document.mediaFileViewForm.submit(), and the
    // form is method="post") posts back to this same URL. The Struts action
    // this was transcribed from answered any verb; a GET-only mapping 405s
    // every one of those submits.
    @RequestMapping(value = "/mediaFileView.rol",
            method = {RequestMethod.GET, RequestMethod.POST})
    @SuppressFBWarnings(
            value = "SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING",
            justification = "Changes nothing: it lists a media directory. POST is accepted only because "
                    + "the page's sort form posts back here; every change on the page has its own "
                    + "POST-only mapping.")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "directoryId", required = false) String directoryId,
                          @RequestParam(value = "directoryName", required = false) String directoryName,
                          @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, directoryName, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!createNewDirectory.rol")
    public String createNewDirectory(HttpServletRequest request, Model model,
                                     @RequestParam(value = "directoryId", required = false) String directoryId,
                                     @RequestParam(value = "newDirectoryName", required = false) String newDirectoryName,
                                     @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);

        if (StringUtils.isEmpty(newDirectoryName)) {
            addError(model, "mediaFile.error.view.dirNameEmpty", request);
        } else if (newDirectoryName.contains("/")) {
            addError(model, "mediaFile.error.view.dirNameInvalid", request);
        } else {
            try {
                MediaFileManager manager = weblogger.getMediaFileManager();
                if (!getActionWeblog(request).hasMediaFileDirectory(newDirectoryName)) {
                    MediaFileDirectory dir = manager.createMediaFileDirectory(getActionWeblog(request), newDirectoryName);
                    weblogger.flush();
                    addMessage(model, "mediaFile.directoryCreate.success",
                            StringEscapeUtils.escapeHtml4(newDirectoryName), request);
                    directoryId = dir.getId();
                } else {
                    addError(model, "mediaFile.directoryCreate.error.exists", StringEscapeUtils.escapeHtml4(newDirectoryName), request);
                }
            } catch (WebloggerException e) {
                log.error("Error creating new directory", e);
                addError(model, "generic.error.check.logs", request);
            }
        }

        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!deleteSelected.rol")
    public String deleteSelected(HttpServletRequest request, Model model,
                                 @RequestParam(value = "directoryId", required = false) String directoryId,
                                 @RequestParam(value = "selectedMediaFiles", required = false) String[] selectedMediaFiles,
                                 @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doDeleteSelected(selectedMediaFiles, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @RequestParam(value = "directoryId", required = false) String directoryId,
                         @RequestParam(value = "mediaFileId", required = false) String mediaFileId,
                         @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doDeleteMediaFile(mediaFileId, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!deleteFolder.rol")
    public String deleteFolder(HttpServletRequest request, Model model,
                               @RequestParam(value = "directoryId", required = false) String directoryId,
                               @RequestParam(value = "directoryName", required = false) String directoryName,
                               @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);

        try {
            MediaFileManager manager = weblogger.getMediaFileManager();
            MediaFileDirectory mediaFileDir = ownedDirectory(directoryId, request);
            if (mediaFileDir != null) {
                manager.removeMediaFileDirectory(mediaFileDir);
                weblogger.getWeblogManager().saveWeblog(getActionWeblog(request));
                weblogger.flush();
                weblogger.release();
                addMessage(model, "mediaFile.deleteFolder.success", request);
                CacheManager.invalidate(getActionWeblog(request));

                mediaFileDir = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
                directoryId = mediaFileDir.getId();
            } else if (StringUtils.isNotEmpty(directoryId)) {
                // A folder delete is recursive; a global by-id lookup here let
                // an editor on one weblog wipe another weblog's folder.
                log.warn("Refusing to delete directory {}: not owned by weblog {}",
                        directoryId, getActionWeblog(request).getHandle());
                addError(model, "mediaFile.deleteFolder.error", request);
                directoryId = null;
            }
        } catch (WebloggerException ex) {
            log.error("Error deleting folder", ex);
            addError(model, "mediaFile.deleteFolder.error", request);
        }

        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    /**
     * Flips a directory's private flag. Private directories vanish from every
     * public surface (base media path, inline galleries, sitemap images) and
     * are visible only to the blog's own members. Saving the weblog bumps
     * its {@code lastModified}, which is what expires the rendered pages
     * holding inline galleries of this directory and the cached sitemap
     * advertising its images; {@code CacheManager.invalidate} reaches only
     * the site-wide cache.
     */
    @PostMapping("/mediaFileView!togglePrivate.rol")
    public String togglePrivate(HttpServletRequest request, Model model,
                                @RequestParam(value = "directoryId", required = false) String directoryId,
                                @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        try {
            MediaFileDirectory directory = ownedDirectory(directoryId, request);
            if (directory == null) {
                addError(model, "mediaFile.privacy.error", request);
            } else {
                directory.setPrivate(!directory.isPrivate());
                weblogger.getWeblogManager().saveWeblog(getActionWeblog(request));
                weblogger.flush();
                CacheManager.invalidate(getActionWeblog(request));
                addMessage(model, "mediaFile.privacy.updated", request);
            }
        } catch (WebloggerException e) {
            log.error("Error toggling directory privacy", e);
            addError(model, "mediaFile.privacy.error", request);
        }
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!moveSelected.rol")
    public String moveSelected(HttpServletRequest request, Model model,
                               @RequestParam(value = "directoryId", required = false) String directoryId,
                               @RequestParam(value = "selectedMediaFiles", required = false) String[] selectedMediaFiles,
                               @RequestParam(value = "selectedDirectory", required = false) String selectedDirectory,
                               @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doMoveSelected(selectedMediaFiles, selectedDirectory, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!search.rol")
    public String search(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") MediaFileSearchBean bean,
                         @RequestParam(value = "directoryId", required = false) String directoryId,
                         @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);

        if (StringUtils.isEmpty(bean.getName()) && StringUtils.isEmpty(bean.getTags())
                && StringUtils.isEmpty(bean.getType()) && bean.getSize() == 0) {
            addError(model, "MediaFile.error.search.empty", request);
            return ".MediaFileView";
        }

        MediaFileFilter filter = new MediaFileFilter();
        bean.copyTo(filter);
        MediaFileManager manager = weblogger.getMediaFileManager();
        try {
            List<MediaFile> rawResults = manager.searchMediaFiles(getActionWeblog(request), filter);
            boolean hasMore = false;
            List<MediaFile> results = new ArrayList<>(rawResults);
            if (results.size() > MediaFileSearchBean.PAGE_SIZE) {
                results.remove(results.size() - 1);
                hasMore = true;
            }
            model.addAttribute("pager", new MediaFilePager(bean.getPageNum(), results, hasMore));
        } catch (Exception e) {
            log.error("Error applying search criteria", e);
            addError(model, "generic.error.check.logs", request);
        }

        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!view.rol")
    public String view(HttpServletRequest request, Model model,
                       @RequestParam(value = "viewDirectoryId", required = false) String viewDirectoryId,
                       @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, viewDirectoryId, null, sortBy);
        return ".MediaFileView";
    }

    private void loadDirectory(HttpServletRequest request, Model model,
                               String directoryId, String directoryName, String sortBy) {
        MediaFileManager manager = weblogger.getMediaFileManager();
        try {
            MediaFileDirectory directory;
            if (StringUtils.isNotEmpty(directoryId)) {
                // Read paths need the same ownership check the write paths
                // have: this method puts the directory's whole file listing
                // into the model.
                directory = ownedDirectory(directoryId, request);
                if (directory == null) {
                    addError(model, "MediaFile.error.view", request);
                }
            } else if (StringUtils.isNotEmpty(directoryName)) {
                directory = manager.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
            } else {
                directory = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
            }

            if (directory != null) {
                List<MediaFile> childFiles = new ArrayList<>(directory.getMediaFiles());

                if ("type".equals(sortBy)) {
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.TYPE));
                } else if ("date_uploaded".equals(sortBy)) {
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.DATE_UPLOADED));
                } else {
                    sortBy = "name";
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.NAME));
                }

                model.addAttribute("childFiles", childFiles);
                model.addAttribute("currentDirectory", directory);
                model.addAttribute("directoryId", directory.getId());
                model.addAttribute("directoryName", directory.getName());
                model.addAttribute("viewDirectoryId", directory.getId());
            }
            model.addAttribute("sortBy", sortBy);

        } catch (Exception ex) {
            log.error("Error viewing media file directory", ex);
            addError(model, "MediaFile.error.view", request);
        }
    }

    private void loadDropdowns(HttpServletRequest request, Model model) {
        model.addAttribute("fileTypes", getFileTypes(request));
        model.addAttribute("sizeFilterTypes", getSizeFilterTypes(request));
        model.addAttribute("sizeUnits", getSizeUnits(request));
        model.addAttribute("sortOptions", getSortOptions(request));
    }

    private List<KeyValueObject> getFileTypes(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.any", getText("mediaFileView.any", request)));
        list.add(new KeyValueObject("mediaFileView.others", getText("mediaFileView.others", request)));
        list.add(new KeyValueObject("mediaFileView.image", getText("mediaFileView.image", request)));
        list.add(new KeyValueObject("mediaFileView.video", getText("mediaFileView.video", request)));
        list.add(new KeyValueObject("mediaFileView.audio", getText("mediaFileView.audio", request)));
        return list;
    }

    private List<KeyValueObject> getSizeFilterTypes(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.gt", getText("mediaFileView.gt", request)));
        list.add(new KeyValueObject("mediaFileView.ge", getText("mediaFileView.ge", request)));
        list.add(new KeyValueObject("mediaFileView.eq", getText("mediaFileView.eq", request)));
        list.add(new KeyValueObject("mediaFileView.le", getText("mediaFileView.le", request)));
        list.add(new KeyValueObject("mediaFileView.lt", getText("mediaFileView.lt", request)));
        return list;
    }

    private List<KeyValueObject> getSizeUnits(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.bytes", getText("mediaFileView.bytes", request)));
        list.add(new KeyValueObject("mediaFileView.kb", getText("mediaFileView.kb", request)));
        list.add(new KeyValueObject("mediaFileView.mb", getText("mediaFileView.mb", request)));
        return list;
    }

    private List<KeyValueObject> getSortOptions(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("name", getText("generic.name", request)));
        list.add(new KeyValueObject("date_uploaded", getText("mediaFileView.date", request)));
        list.add(new KeyValueObject("type", getText("mediaFileView.type", request)));
        return list;
    }

    @ModelAttribute("bean")
    public MediaFileSearchBean getSearchBean() {
        return new MediaFileSearchBean();
    }
}
