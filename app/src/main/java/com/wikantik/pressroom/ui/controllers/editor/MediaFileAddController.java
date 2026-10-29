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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.util.I18nMessages;
import com.wikantik.pressroom.util.PressroomMessages;
import com.wikantik.pressroom.util.PressroomMessages.PressroomMessage;
import com.wikantik.pressroom.util.Utilities;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Adds a new media file.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class MediaFileAddController extends MediaFileBase {

    private static final Logger log = LoggerFactory.getLogger(MediaFileAddController.class);

    /** The site's pages carry images, so the site space has a media library. */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileAdd";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileAdd.title";
    }

    @GetMapping("/mediaFileAdd.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") MediaFileBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        resolveDirectory(request, model, bean);
        return ".MediaFileAdd";
    }

    @PostMapping("/mediaFileAdd!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") MediaFileBean bean,
                       @RequestParam(value = "uploadedFiles", required = false) MultipartFile[] uploadedFiles) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        MediaFileDirectory directory = resolveDirectory(request, model, bean);

        // validate
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            addError(model, "error.upload.disabled", request);
            return ".MediaFileAdd";
        }

        if (!hasErrors(model) && uploadedFiles != null && uploadedFiles.length > 0) {
            MediaFileManager manager = weblogger.getMediaFileManager();
            PressroomMessages errors = new PressroomMessages();
            List<MediaFile> uploaded = new ArrayList<>();
            List<MediaFile> newImages = new ArrayList<>();
            List<MediaFile> newFiles = new ArrayList<>();

            for (MultipartFile uploadedFile : uploadedFiles) {
                if (uploadedFile == null || uploadedFile.isEmpty()) {
                    continue;
                }

                try {
                    MediaFile mediaFile = new MediaFile();
                    bean.copyTo(mediaFile, weblogger.getMediaFileManager());

                    String fileName = uploadedFile.getOriginalFilename();
                    if (fileName != null) {
                        int terminated = fileName.indexOf('\000');
                        if (terminated != -1) {
                            fileName = fileName.substring(0, terminated).trim();
                        }
                        if (fileName.indexOf('/') != -1 || fileName.indexOf('\\') != -1 || fileName.contains("..")) {
                            addError(model, "uploadFiles.error.badPath",
                                    StringEscapeUtils.escapeHtml4(fileName), request);
                            continue;
                        }
                    }

                    mediaFile.setName(fileName);
                    mediaFile.setDirectory(directory);
                    mediaFile.setWeblog(getActionWeblog(request));
                    mediaFile.setLength(uploadedFile.getSize());
                    mediaFile.setInputStream(uploadedFile.getInputStream());

                    String contentType = uploadedFile.getContentType();
                    if (contentType == null || contentType.endsWith("/octet-stream")) {
                        String ctype = Utilities.getContentTypeFromFileName(mediaFile.getName());
                        if (ctype != null) {
                            contentType = ctype;
                        }
                    }
                    mediaFile.setContentType(contentType);

                    // createMediaFile reports a validation refusal (quota, forbidden
                    // file type, ...) by adding to this shared collector and returning
                    // normally rather than throwing -- so a growth in the error count
                    // across this one call is this file being refused, not persisted.
                    int errorCountBeforeThisFile = errors.getErrorCount();
                    manager.createMediaFile(getActionWeblog(request), mediaFile, errors);
                    weblogger.flush();

                    if (errors.getErrorCount() > errorCountBeforeThisFile) {
                        continue;
                    }

                    if (mediaFile.isImageFile()) {
                        newImages.add(mediaFile);
                    } else {
                        newFiles.add(mediaFile);
                    }
                    uploaded.add(mediaFile);

                } catch (Exception e) {
                    log.error("Error uploading media file", e);
                    addError(model, "mediaFileAdd.errorUploading",
                            StringEscapeUtils.escapeHtml4(uploadedFile.getOriginalFilename()), request);
                }
            }

            for (Iterator<PressroomMessage> it = errors.getErrors(); it.hasNext();) {
                PressroomMessage msg = it.next();
                String[] args = msg.getArgs();
                if (args == null || args.length == 0) {
                    addError(model, msg.getKey(), request);
                } else {
                    // The manager's refusals carry the uploaded file's name and
                    // content type, both sent by the browser; the banner is
                    // printed unescaped.
                    Object[] escaped = new Object[args.length];
                    for (int i = 0; i < args.length; i++) {
                        escaped[i] = StringEscapeUtils.escapeHtml4(args[i]);
                    }
                    addError(model, msg.getKey(), escaped, request);
                }
            }

            // A batch upload can partly succeed: report BOTH what landed and what
            // did not, rather than one generic failure that hides which of thirty
            // files actually made it. Whenever anything landed, land on the success
            // page -- it lists exactly the files in `uploaded`, and any errors
            // recorded above render in the same response via the shared errors
            // banner, so a mixed batch shows both the successes and the failures at
            // once instead of suppressing the successes because something else in
            // the batch failed. Only a batch where nothing landed at all falls back
            // to redisplaying the form.
            if (!uploaded.isEmpty()) {
                addMessage(model, "uploadFiles.uploadedFiles", request);
                for (MediaFile upload : uploaded) {
                    addMessage(model, "uploadFiles.uploadedFile",
                            weblogger.getUrlStrategy().getMediaFileURL(upload.getWeblog(), upload.getId(), true),
                            request);
                }

                model.addAttribute("newImages", newImages);
                model.addAttribute("newFiles", newFiles);
                return ".MediaFileAddSuccess";
            }
        }

        return ".MediaFileAdd";
    }

    /**
     * The editor's paste/drop upload. JSON in, JSON out, one result per file;
     * the per-file logic is {@link MediaUploads}, shared with the automation
     * API so the two surfaces cannot drift on what a refusal is. A foreign
     * directory is 404, never 403 (the by-id ownership rule); {@code
     * uploads.enabled} off is 403.
     */
    @PostMapping(value = "/mediaFileAdd!upload.rol", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> upload(HttpServletRequest request,
            @RequestParam(value = "directoryId", required = false) String directoryId,
            @RequestParam(value = "file", required = false) MultipartFile[] files) throws WebloggerException {
        Weblog weblog = getActionWeblog(request);
        // A refusal of the whole request carries a sentence in the reader's
        // language beside its code: the editor shows "detail" when there are
        // no per-file results to show.
        I18nMessages text = I18nMessages.getMessages(request.getLocale());
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uploads.disabled",
                    "detail", text.getString("mediaUploads.detail.disabled")));
        }
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "no.files",
                    "detail", text.getString("mediaUploads.detail.noFiles")));
        }

        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFileDirectory directory;
        if (StringUtils.isNotBlank(directoryId)) {
            directory = MediaUploads.directoryFor(weblogger, weblog, directoryId);
            if (directory == null) {
                return ResponseEntity.notFound().build();
            }
        } else {
            directory = MediaUploads.defaultDirectory(weblog, mfm);
        }

        PressroomMessages messages = new PressroomMessages();
        List<Map<String, Object>> results = new ArrayList<>();
        int created = 0;
        for (MultipartFile file : files) {
            MediaUploads.Result r = MediaUploads.store(file, weblog, directory, mfm, messages, request.getLocale());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fileName", r.fileName());
            row.put("status", r.status());
            row.put("detail", r.detail());
            if (r.file() != null) {
                created++;
                row.put("id", r.file().getId());
                row.put("url", weblogger.getUrlStrategy().getMediaFileURL(weblog, r.file().getId(), false));
            }
            results.add(row);
        }
        weblogger.flush();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        body.put("created", created);
        body.put("failed", files.length - created);
        return ResponseEntity.status(created == files.length ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS)
                .body(body);
    }

    /**
     * Kept for a POST that arrives without script (the form's Cancel is a plain
     * link now, so nothing in the shipped page posts here). The redirect
     * carries the directory the author was uploading into: without it, cancel
     * dropped them back at the media root and they had to navigate down to the
     * folder again.
     */
    @PostMapping("/mediaFileAdd!cancel.rol")
    public String cancel(HttpServletRequest request,
                         @RequestParam(name = "bean.directoryId", required = false) String directoryId) {
        Weblog weblog = getActionWeblog(request);
        String url = "redirect:/pressroom-ui/authoring/mediaFileView.rol?weblog="
                + (weblog != null ? weblog.getHandle() : "");
        if (StringUtils.isNotBlank(directoryId)) {
            url += "&directoryId=" + URLEncoder.encode(directoryId, StandardCharsets.UTF_8);
        }
        return url;
    }

    private MediaFileDirectory resolveDirectory(HttpServletRequest request, Model model, MediaFileBean bean) {
        try {
            MediaFileManager mgr = weblogger.getMediaFileManager();
            MediaFileDirectory directory;

            if (!StringUtils.isEmpty(bean.getDirectoryId())) {
                // The posted directoryId is where the bytes would land, and
                // getMediaFileDirectory answers for every weblog on the site.
                directory = ownedDirectory(bean.getDirectoryId(), request);
                if (directory == null) {
                    log.warn("Refusing to upload into directory {}: not owned by weblog {}",
                            bean.getDirectoryId(), getActionWeblog(request).getHandle());
                    addError(model, "MediaFile.error.view", request);
                    return null;
                }
            } else {
                String directoryName = request.getParameter("directoryName");
                if (StringUtils.isNotEmpty(directoryName)) {
                    directory = mgr.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
                } else {
                    directory = mgr.getDefaultMediaFileDirectory(getActionWeblog(request));
                    if (directory == null) {
                        directory = mgr.createDefaultMediaFileDirectory(getActionWeblog(request));
                    }
                }
            }

            if (directory != null) {
                bean.setDirectoryId(directory.getId());
                model.addAttribute("directory", directory);
            }
            weblogger.flush();
            return directory;

        } catch (WebloggerException ex) {
            log.error("Error looking up media file directory", ex);
        }
        return null;
    }

    @ModelAttribute("bean")
    public MediaFileBean getBean() {
        return new MediaFileBean();
    }
}
