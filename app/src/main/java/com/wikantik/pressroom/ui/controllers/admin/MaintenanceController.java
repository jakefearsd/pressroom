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

package com.wikantik.pressroom.ui.controllers.admin;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MaintenanceService;
import com.wikantik.pressroom.business.discovery.DiscoveryStatus;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Allows a server administrator to perform per-weblog maintenance operations
 * such as flushing the page cache, rebuilding the search index, or
 * regenerating media renditions.
 *
 * <p>This used to live under {@code /pressroom-ui/authoring} and get its target
 * weblog from the action context ({@code getActionWeblog(request)}) the way
 * every other editor screen does. It moved to Global Admin because the three
 * actions are operator work, not authoring work -- a blog author should never
 * face a button whose failure mode ("the search index is corrupt", "the page
 * cache is stale") they cannot reason about.
 *
 * <p>There is no action-context weblog under {@code /pressroom-ui/admin/**}
 * ({@link #isWeblogRequired()} is false, matching {@code
 * GlobalConfigController}/{@code UserAdminController}), so the weblog to
 * operate on is chosen explicitly from a {@code <select>} on the page and
 * resolved here by id, exactly as {@code GlobalConfig.jsp}'s front-page-weblog
 * picker does.
 *
 * <p>The three POST handlers' {@code weblogId} {@code @RequestParam}s are all
 * {@code required = false}. {@code Maintenance.jsp}'s placeholder
 * {@code <option>} is both {@code disabled} and (while nothing is chosen)
 * {@code selected} -- per WHATWG HTML's form-entry-list construction, an
 * option that is disabled contributes NO entry to the submitted form at all,
 * selected or not. An admin who opens the page and clicks an action button
 * without touching the dropdown therefore submits a POST with no
 * {@code weblogId} parameter whatsoever, not an empty one. A required
 * parameter would fail request binding with {@code
 * MissingServletRequestParameterException} (HTTP 400, routed by {@code
 * WebContainerConfig} to the generic 404 page) before any handler method
 * ever ran; {@code required = false} lets a missing parameter reach {@code
 * resolveWeblog(null)} and land on the same {@code
 * maintenance.error.noSuchWeblog} page error as an explicit empty one.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class MaintenanceController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "maintenance";
    }

    @Override
    public String getPageTitle() {
        return "maintenance.title";
    }

    @GetMapping("/maintenance.rol")
    public String execute(HttpServletRequest request, Model model,
            @RequestParam(value = "weblogId", required = false) String weblogId) {
        populateCommonModel(request, model);
        model.addAttribute("weblogs", loadWeblogs());

        Weblog weblog = resolveWeblog(weblogId);
        if (weblog == null && weblogId != null && !weblogId.isBlank()) {
            addError(model, "maintenance.error.noSuchWeblog", request);
        }
        model.addAttribute("selectedWeblog", weblog);
        addDiscoveryStatus(model);

        return ".Maintenance";
    }

    /**
     * Rebuilds the front page popularity snapshot now and reports how it went.
     * Synchronous: the admin pressed the button to see the outcome. The
     * manager never throws; a failed Umami read comes back as a FAILED status.
     */
    @PostMapping("/maintenance!refreshDiscovery.rol")
    public String refreshDiscovery(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("weblogs", loadWeblogs());
        model.addAttribute("selectedWeblog", null);

        weblogger.getDiscoveryManager().refresh();
        DiscoveryStatus status = addDiscoveryStatus(model);

        switch (status.state()) {
            case OK -> addMessage(model, "maintenance.discovery.refreshed", request);
            case FAILED -> {
                // <spring:message arguments> and the messages banner do not
                // escape, and the error text comes from outside.
                String reason = StringEscapeUtils.escapeHtml4(String.valueOf(status.lastError()));
                addError(model, "maintenance.discovery.refreshFailed", reason, request);
            }
            // the refresh ran: Umami unconnected (or not yet read) is a
            // state to report, not an error
            default -> addMessage(model, "maintenance.discovery.state." + status.state().name(), request);
        }
        return ".Maintenance";
    }

    private DiscoveryStatus addDiscoveryStatus(Model model) {
        DiscoveryStatus status = weblogger.getDiscoveryManager().getStatus();
        model.addAttribute("discoveryStatus", status);
        // rc:date renders java.util.Date.
        model.addAttribute("discoveryLastAttempt",
                status.lastAttempt() == null ? null : Date.from(status.lastAttempt()));
        model.addAttribute("discoveryLastSuccess",
                status.lastSuccess() == null ? null : Date.from(status.lastSuccess()));
        return status;
    }

    @PostMapping("/maintenance!flushCache.rol")
    public String flushCache(HttpServletRequest request, Model model,
            @RequestParam(value = "weblogId", required = false) String weblogId) {
        populateCommonModel(request, model);
        model.addAttribute("weblogs", loadWeblogs());

        Weblog weblog = resolveWeblog(weblogId);
        model.addAttribute("selectedWeblog", weblog);
        if (weblog == null) {
            addError(model, "maintenance.error.noSuchWeblog", request);
            return ".Maintenance";
        }

        try {
            new MaintenanceService(weblogger).flushCache(weblog);
            addMessage(model, "maintenance.message.flushed", request);
        } catch (Exception ex) {
            log.error("Error saving weblog - {}", weblog.getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".Maintenance";
    }

    @PostMapping("/maintenance!index.rol")
    public String index(HttpServletRequest request, Model model,
            @RequestParam(value = "weblogId", required = false) String weblogId) {
        populateCommonModel(request, model);
        model.addAttribute("weblogs", loadWeblogs());

        Weblog weblog = resolveWeblog(weblogId);
        model.addAttribute("selectedWeblog", weblog);
        if (weblog == null) {
            addError(model, "maintenance.error.noSuchWeblog", request);
            return ".Maintenance";
        }

        try {
            new MaintenanceService(weblogger).rebuildIndex(weblog);
            addMessage(model, "maintenance.message.indexed", request);
        } catch (Exception ex) {
            log.error("Error doing index rebuild", ex);
            addError(model, "maintenance.message.indexed.failure", request);
        }

        return ".Maintenance";
    }

    @PostMapping("/maintenance!regenerateRenditions.rol")
    public String regenerateRenditions(HttpServletRequest request, Model model,
            @RequestParam(value = "weblogId", required = false) String weblogId) {
        populateCommonModel(request, model);
        model.addAttribute("weblogs", loadWeblogs());

        Weblog weblog = resolveWeblog(weblogId);
        model.addAttribute("selectedWeblog", weblog);
        if (weblog == null) {
            addError(model, "maintenance.error.noSuchWeblog", request);
            return ".Maintenance";
        }

        try {
            int count = new MaintenanceService(weblogger).regenerateRenditions(weblog);
            addMessage(model, "maintenance.message.renditionsRegenerated",
                    String.valueOf(count), request);
        } catch (Exception ex) {
            log.error("Error regenerating renditions - {}", weblog.getHandle(), ex);
            addError(model, "maintenance.message.renditionsRegenerated.failure", request);
        }

        return ".Maintenance";
    }

    /** Every weblog on the install, for the operator's weblog picker. */
    private List<Weblog> loadWeblogs() {
        try {
            return weblogger.getWeblogManager().getWeblogs(true, null, null, null, 0, -1);
        } catch (WebloggerException ex) {
            log.error("Error getting weblogs", ex);
            return Collections.emptyList();
        }
    }

    /**
     * The weblog named by {@code weblogId}, or null when the id is blank,
     * unknown, or the lookup itself failed.
     *
     * <p>Ownership is not the issue on this screen the way it is for {@code
     * BaseController.lookupEntry}/{@code lookupTemplate}/etc. -- this is a
     * global-admin-only page, so there is no "wrong weblog" to guard a caller
     * against. But the id is still client input arriving on a POST, so an
     * unknown one has to become a page error the caller reports, not an NPE
     * two lines later against a null weblog.
     */
    private Weblog resolveWeblog(String weblogId) {
        if (weblogId == null || weblogId.isBlank()) {
            return null;
        }
        try {
            return weblogger.getWeblogManager().getWeblog(weblogId);
        } catch (WebloggerException ex) {
            log.error("Error looking up weblog by id - {}", weblogId, ex);
            return null;
        }
    }
}
