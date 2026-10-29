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


import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.MediaFile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Prepares creation of a new weblog entry with an embedded pointer to a media file.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntryAddWithMediaFileController extends MediaFileBase {

    private static final Logger log = LoggerFactory.getLogger(EntryAddWithMediaFileController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "entryAdd";
    }

    @Override
    public String getPageTitle() {
        return "weblogEdit.title.newEntry";
    }

    @RequestMapping(value = "/entryAddWithMediaFile.rol", method = {RequestMethod.GET, RequestMethod.POST})
    @SuppressFBWarnings(
            value = "SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING",
            justification = "Changes nothing: it builds an unsaved EntryBean from the selected media files "
                    + "and forwards to the entry editor, whose own save is a CSRF-checked POST.")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") EntryBean bean,
                          @RequestParam(value = "selectedImages", required = false) String[] selectedImages,
                          @RequestParam(value = "selectedImage", required = false) String selectedImage) {
        populateCommonModel(request, model);

        try {
            if (StringUtils.isNotEmpty(selectedImage) && selectedImages == null) {
                selectedImages = new String[]{selectedImage};
            }

            StringBuilder sb = new StringBuilder();
            if (selectedImages != null) {
                for (String image : selectedImages) {
                    // getMediaFile is a global by-id lookup; the snippet built
                    // below embeds the file's name and permalink in the draft.
                    MediaFile mediaFile = ownedMediaFile(image, request);
                    if (mediaFile == null) {
                        log.warn("Refusing to link media file {}: not owned by weblog {}",
                                image, getActionWeblog(request).getHandle());
                        continue;
                    }
                    String link;
                    if (mediaFile.isImageFile()) {
                        // The [image] shortcode is what the editor's own media
                        // insert pastes (see CLAUDE.md's Entry editing section):
                        // it expands at render time into the full responsive
                        // <figure><picture> block -- srcset, the rendition
                        // ladder, and alt text pulled from the media file's own
                        // altText/name chain automatically. Hand-building an
                        // <img> here bypassed all of that (no srcset, no
                        // renditions, no alt text) and emitted an invalid
                        // self-closing </img>.
                        link = "[image id=\"" + mediaFile.getId() + "\"]";
                    } else {
                        link = "<a href='<url>'><name></a> (<size> bytes, <type>)";
                        link = link.replace("<url>", weblogger.getUrlStrategy()
                                           .getMediaFileURL(mediaFile.getWeblog(), mediaFile.getId(), true))
                                   .replace("<name>", mediaFile.getName())
                                   .replace("<size>", String.valueOf(mediaFile.getLength()))
                                   .replace("<type>", mediaFile.getContentType());
                    }
                    // A blank line between entries, not concatenated flush
                    // against one another: adjacent [image id="a"][image
                    // id="b"] shortcodes with nothing between them land as one
                    // unbroken run in the editor with no cursor position to
                    // insert a caption or split them onto separate lines.
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(link);
                }
            }

            bean.setText(sb.toString());

        } catch (Exception e) {
            log.error("Error while constructing media file link for new entry", e);
        }

        // Forward to entryAdd
        return "forward:/roller-ui/authoring/entryAdd.rol";
    }

    @ModelAttribute("bean")
    public EntryBean getBean() {
        return new EntryBean();
    }
}
