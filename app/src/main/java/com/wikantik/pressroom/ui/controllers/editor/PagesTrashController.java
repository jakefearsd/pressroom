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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.runnable.TrashPurgeTask;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.Flash;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The page trash: the pages deleted from a weblog (or from the site), each
 * restorable as a draft or deletable for good until the purge takes it.
 *
 * <p>The only screen that resolves a trashed page by id, and through
 * {@link #lookupTrashedPage} it resolves nothing else: a live page's id here
 * is not found, so "restore" cannot demote a published page to a draft and
 * "delete forever" cannot skip the trash.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class PagesTrashController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PagesTrashController.class);

    /** Site pages have a trash too. */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    /** A view of the Pages screen: its tab stays current, on a blog and on the site space. */
    @Override
    public String getActionName() {
        return "pages";
    }

    @Override
    public String getPageTitle() {
        return "pages.trash.title";
    }

    @GetMapping("/pagesTrash.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        List<WeblogPage> trashed = Collections.emptyList();
        try {
            trashed = weblogger.getWeblogPageManager().getTrashedPages(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error looking up trashed pages", ex);
            addError(model, "generic.error.check.logs", request);
        }
        // Read per request, as the purge reads it per sweep: the date shown
        // is the one the next sweep will honour.
        int retentionDays = WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.PAGE_RETENTION_PROPERTY);
        model.addAttribute("trashedPages", trashed);
        model.addAttribute("pageTrashRetentionDays", retentionDays);
        model.addAttribute("purgeDates", purgeDates(trashed, retentionDays));
        return ".PagesTrash";
    }

    /** When each page is purged, by page id; empty when trash is kept until deleted by hand. */
    private static Map<String, Date> purgeDates(List<WeblogPage> trashed, int retentionDays) {
        Map<String, Date> dates = new HashMap<>();
        if (retentionDays < 0) {
            return dates;
        }
        for (WeblogPage page : trashed) {
            if (page.getTrashedAt() != null) {
                dates.put(page.getId(),
                        new Date(page.getTrashedAt().getTime() + TimeUnit.DAYS.toMillis(retentionDays)));
            }
        }
        return dates;
    }

    @PostMapping("/pagesTrash!restore.rol")
    public String restore(HttpServletRequest request,
                          @RequestParam(name = "pageId", required = false) String pageId,
                          RedirectAttributes redirectAttributes) {
        WeblogPage page = lookupTrashedPage(pageId, request);
        if (page == null) {
            addFlashError(redirectAttributes, "pageEdit.notFound", request);
            return backToList(request);
        }
        return flashAction(request, redirectAttributes, backToList(request),
                "restoring page " + page.getId(), () -> {
            weblogger.getWeblogPageManager().restorePage(page);
            weblogger.flush();
            return Flash.message("pages.trash.restored", StringEscapeUtils.escapeHtml4(page.getSlug()));
        });
    }

    @PostMapping("/pagesTrash!delete.rol")
    public String delete(HttpServletRequest request,
                         @RequestParam(name = "pageId", required = false) String pageId,
                         RedirectAttributes redirectAttributes) {
        WeblogPage page = lookupTrashedPage(pageId, request);
        if (page == null) {
            addFlashError(redirectAttributes, "pageEdit.notFound", request);
            return backToList(request);
        }
        return flashAction(request, redirectAttributes, backToList(request),
                "permanently deleting page " + page.getId(), () -> {
            String slug = page.getSlug();
            weblogger.getWeblogPageManager().removePage(page);
            weblogger.flush();
            return Flash.message("pages.trash.deletedForever", StringEscapeUtils.escapeHtml4(slug));
        });
    }

    private String backToList(HttpServletRequest request) {
        return "redirect:/pressroom-ui/authoring/pagesTrash.rol?weblog="
                + getActionWeblog(request).getHandle();
    }
}
