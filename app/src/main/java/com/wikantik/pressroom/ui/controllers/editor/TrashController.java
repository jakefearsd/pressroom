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
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.runnable.TrashPurgeTask;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Trash screen: lists a weblog's trashed entries and lets an editor
 * restore one, delete one forever, or empty the trash outright.
 *
 * <p>Same shape as {@link EntriesController} -- a list with per-row actions
 * plus one bulk action -- and it reuses that controller's idiom rather than
 * inventing one: every id arriving on a POST is client input, resolved
 * through {@link BaseController#lookupEntry}, and every outcome is reported
 * with a flash message/error and a redirect back to this list.
 *
 * <p><strong>The security point specific to this screen:</strong>
 * {@code lookupEntry} performs the ownership check but carries no status
 * filter. Without an extra check here, {@code trash!delete.rol} given the id
 * of a <em>live</em> entry would hard-delete it -- an undocumented
 * permanent-delete endpoint for any entry whose id can be guessed, bypassing
 * the trash entirely. {@code trash!restore.rol} has the same shape of hazard:
 * posting a live entry's id would flip it to {@code DRAFT}, silently
 * unpublishing it. {@link #trashedEntry} is the single choke point both
 * restore and delete-forever go through to close this off: it refuses
 * anything {@code lookupEntry} resolves that is not actually
 * {@link PubStatus#TRASHED}, and does so the same indistinguishable way
 * {@code lookupEntry} itself refuses an unknown or foreign id -- the caller
 * cannot tell "no such entry" apart from "that entry exists but is not
 * trashed."
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class TrashController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TrashController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "trash";
    }

    @Override
    public String getPageTitle() {
        return "trash.title";
    }

    @GetMapping("/trash.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        List<WeblogEntry> trashedEntries = Collections.emptyList();
        try {
            trashedEntries = weblogger.getWeblogEntryManager().getTrashedEntries(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error looking up trashed entries", ex);
            addError(model, "generic.error.check.logs", request);
        }
        model.addAttribute("trashedEntries", trashedEntries);
        // Labels printed once through fn:escapeXml (EntriesController.plainTitle).
        model.addAttribute("entryTitles", EntriesController.plainTitles(trashedEntries));
        // Read per request, not latched: entry.trash.retention.days is a
        // RUNTIME property (Admin Settings), so a value cached at init would
        // show a retention the purge task no longer honours. TrashPurgeTask
        // re-reads it per sweep for the same reason.
        model.addAttribute("trashRetentionDays",
                WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.RETENTION_PROPERTY));

        return ".Trash";
    }

    /** Brings a trashed entry back as a draft. */
    @PostMapping("/trash!restore.rol")
    public String restore(HttpServletRequest request,
                          @RequestParam(value = "restoreId", required = false) String restoreId,
                          RedirectAttributes redirectAttributes) {
        WeblogEntry entry = trashedEntry(restoreId, request);
        if (entry == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return backToList(request);
        }

        try {
            weblogger.getWeblogEntryManager().restoreWeblogEntry(entry);
            weblogger.flush();
            addFlashMessage(redirectAttributes, "trash.restored", entry.getDisplayTitle(), request);
        } catch (Exception e) {
            log.error("Error restoring entry {}", restoreId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return backToList(request);
    }

    /** Permanently deletes one already-trashed entry -- the "delete forever" row action. */
    @PostMapping("/trash!delete.rol")
    public String delete(HttpServletRequest request,
                         @RequestParam(value = "deleteId", required = false) String deleteId,
                         RedirectAttributes redirectAttributes) {
        WeblogEntry entry = trashedEntry(deleteId, request);
        if (entry == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return backToList(request);
        }

        try {
            String title = entry.getDisplayTitle();
            deleteEntryForeverWithIndex(entry);
            weblogger.flush();
            addFlashMessage(redirectAttributes, "trash.deletedForever", title, request);
        } catch (Exception e) {
            log.error("Error permanently deleting entry {}", deleteId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return backToList(request);
    }

    /**
     * Permanently deletes every entry currently in this weblog's trash.
     *
     * <p>Reuses {@code purgeTrash} with a retention of {@code 0} rather than a
     * bespoke "delete all" query: every entry already sitting in the trash was
     * trashed strictly before "now", so a zero-day retention purges all of
     * them -- the same one-permanent-deletion-path guarantee
     * {@code purgeTrash} gives the scheduled sweep (Task 4) applies here too,
     * with no second bulk-delete code path to drift from it.
     */
    @PostMapping("/trash!empty.rol")
    public String empty(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            int purged = weblogger.getWeblogEntryManager().purgeTrash(getActionWeblog(request), 0);
            weblogger.flush();
            if (purged > 0) {
                addFlashMessage(redirectAttributes, "trash.emptied", String.valueOf(purged), request);
            } else {
                addFlashMessage(redirectAttributes, "trash.alreadyEmpty", request);
            }
        } catch (Exception e) {
            log.error("Error emptying trash", e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return backToList(request);
    }

    /**
     * The trashed entry named by {@code id}, refusing anything
     * {@link #lookupEntry} resolves that is not actually {@link PubStatus#TRASHED}.
     * See the class javadoc for why this check exists.
     */
    private WeblogEntry trashedEntry(String id, HttpServletRequest request) {
        WeblogEntry entry = lookupEntry(id, request);
        if (entry == null || entry.getStatus() != PubStatus.TRASHED) {
            return null;
        }
        return entry;
    }

    private String backToList(HttpServletRequest request) {
        return "redirect:/roller-ui/authoring/trash.rol?weblog="
                + getActionWeblog(request).getHandle();
    }
}
