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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.apache.commons.text.StringEscapeUtils;

/**
 * List view of static pages in a weblog, plus their removal.
 *
 * <p>Modelled on {@link TemplatesController}: a plain list with an inline
 * remove, no bulk actions, no separate confirmation page -- {@code remove}
 * reloads the same list rather than redirecting, exactly like
 * {@code TemplatesController.remove}.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class PagesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PagesController.class);

    /** The site's pages are listed and removed here, on the site space as on a blog. */
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

    @Override
    public String getActionName() {
        return "pages";
    }

    @Override
    public String getPageTitle() {
        return "weblogPagesForm.title";
    }

    @GetMapping("/pages.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadPagesList(request, model);
        return ".Pages";
    }

    /**
     * Moves one page to the trash and reloads the list. A page already in
     * the trash answers as not found ({@link #lookupPage} refuses trash).
     *
     * <p>Ownership-checked through {@link #lookupPage}: {@code removeId} is
     * client input and {@code getPage} is a global by-id lookup, so without
     * the check an editor on one weblog could delete another weblog's pages.
     */
    @PostMapping("/pageRemove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(name = "removeId", required = false) String removeId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "pageRemove");

        WeblogPage page = lookupPage(removeId, request);
        if (page == null) {
            log.warn("Refusing to delete page {}: not owned by weblog {}",
                    removeId, getActionWeblog(request).getHandle());
            addError(model, "pageEdit.notFound", request);
        } else {
            try {
                // To the trash: recoverable for page.trash.retention.days.
                // Deleting for good is the trash screen's second step.
                weblogger.getWeblogPageManager().trashPage(page);
                weblogger.flush();
                addMessage(model, "pages.trash.moved",
                        StringEscapeUtils.escapeHtml4(page.getSlug()), request);
            } catch (Exception ex) {
                log.error("Error removing page {}", removeId, ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        loadPagesList(request, model);
        return ".Pages";
    }

    /**
     * Saves the order the Pages screen posted: {@code pageIds}, repeated, in
     * row order. The manager numbers only this weblog's own pages, skipping
     * stale, foreign and repeated ids, so a tampered list moves nothing of
     * another weblog's. A POST naming no ids changes nothing and is not an
     * error. The permission is the screen's own ({@code POST}); a GET of the
     * route is a 405.
     */
    @PostMapping("/pagesOrder.rol")
    public String reorder(HttpServletRequest request, RedirectAttributes redirectAttributes,
                          @RequestParam(name = "pageIds", required = false) List<String> pageIds) {
        String back = "redirect:/pressroom-ui/authoring/pages.rol?weblog="
                + getActionWeblog(request).getHandle();
        if (pageIds == null || pageIds.isEmpty()) {
            return back;
        }
        try {
            weblogger.getWeblogPageManager().setNavOrder(getActionWeblog(request), pageIds);
            weblogger.flush();
            addFlashMessage(redirectAttributes, "weblogPagesForm.orderSaved", request);
        } catch (Exception ex) {
            log.error("Error saving the page order of weblog {}", getActionWeblog(request).getHandle(), ex);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return back;
    }

    private void loadPagesList(HttpServletRequest request, Model model) {
        try {
            List<WeblogPage> pages = weblogger.getWeblogPageManager().getPages(getActionWeblog(request));
            model.addAttribute("pages", pages);
            model.addAttribute("strandPartCounts", strandPartCounts(pages));
        } catch (WebloggerException ex) {
            log.error("Error getting pages for weblog - {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
        model.addAttribute("trashedPageCount", trashedPageCount(request));
    }

    /**
     * How many pages are in the trash, for the link to it. A display path: a
     * count that cannot be read leaves the link out and the list alone.
     */
    private int trashedPageCount(HttpServletRequest request) {
        try {
            return weblogger.getWeblogPageManager().getTrashedPages(getActionWeblog(request)).size();
        } catch (WebloggerException ex) {
            log.error("Error counting trashed pages of weblog {}", getActionWeblog(request).getHandle(), ex);
            return 0;
        }
    }

    /**
     * How many posts each strand with parts holds, by page id, for that row's
     * trash confirmation: a trashed strand keeps its parts, which lose their
     * strand bar until it is restored and published, and the confirmation
     * says how many. A count that cannot be read leaves the row with the
     * plain confirmation.
     */
    private Map<String, Long> strandPartCounts(List<WeblogPage> pages) {
        Map<String, Long> counts = new HashMap<>();
        for (WeblogPage page : pages) {
            if (!Boolean.TRUE.equals(page.getStrand())) {
                continue;
            }
            try {
                long count = weblogger.getWeblogEntryManager().getStrandPartCount(page);
                if (count > 0) {
                    counts.put(page.getId(), count);
                }
            } catch (WebloggerException ex) {
                log.error("Error counting the parts of strand {}", page.getId(), ex);
            }
        }
        return counts;
    }
}
