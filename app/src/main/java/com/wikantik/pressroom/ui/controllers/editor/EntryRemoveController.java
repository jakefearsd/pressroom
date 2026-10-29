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

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Remove a weblog entry.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntryRemoveController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntryRemoveController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getPageTitle() {
        return "weblogEdit.deleteEntry";
    }


    @PostMapping("/entryRemove!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(value = "removeId", required = false) String removeId,
                         RedirectAttributes redirectAttributes) {
        // Orphaned in the UI -- nothing posts here directly any more -- but it
        // still has to behave like its sibling below rather than rot into a
        // different answer.
        return removeEntry(request, model, removeId, redirectAttributes, "entryRemove");
    }


    @PostMapping("/entryRemoveViaList!remove.rol")
    public String entryRemoveViaListRemove(HttpServletRequest request, Model model,
                                           @RequestParam(value = "removeId", required = false)
                                           String removeId,
                                           RedirectAttributes redirectAttributes) {
        return removeEntry(request, model, removeId, redirectAttributes, "entryRemoveViaList");
    }


    /**
     * Trashes one entry and sends the author back to the entry list.
     *
     * <p>The two endpoints above differed in nothing but their mapping and the
     * actionName they report, and were otherwise the same thirty lines written
     * twice -- including the recovery paths, which are the part that would
     * quietly diverge.
     *
     * @param actionName what the view calls this action; the only thing the two
     *                   callers disagree about
     */
    private String removeEntry(HttpServletRequest request, Model model, String removeId,
                               RedirectAttributes redirectAttributes, String actionName) {

        populateCommonModel(request, model);
        model.addAttribute("actionName", actionName);

        WeblogEntry entry = lookupEntry(removeId, request);
        if (entry == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return "redirect:/roller-ui/menu.rol";
        }

        try {
            trashEntryWithIndex(entry);
            weblogger.flush();

            addFlashMessage(redirectAttributes, "weblogEdit.entryRemoved", entry.getTitle(),
                    request);
            return entryListRedirect(request);

        } catch (Exception e) {
            log.error("Error removing entry {}", removeId, e);
        }

        // The delete is driven by a modal on the entry list, so there is no
        // confirmation page to fall back to; return to the list with the error.
        addFlashError(redirectAttributes, "generic.error.check.logs", request);
        return entryListRedirect(request);
    }

    private String entryListRedirect(HttpServletRequest request) {
        return "redirect:/roller-ui/authoring/entries.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

}
