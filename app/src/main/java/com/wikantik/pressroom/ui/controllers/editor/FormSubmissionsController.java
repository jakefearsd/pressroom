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
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The per-weblog contact-inquiries inbox: lists the {@link FormSubmission}s
 * saved by {@code ContactController} for one weblog, newest first, and lets
 * an editor delete the ones they no longer need.
 *
 * <p>Modelled on {@link PagesController}: a plain paged list plus a bulk
 * removal, no separate confirmation page.
 *
 * <p><b>Ownership.</b> {@code deleteIds} is client input and {@code
 * FormSubmissionManager.get} is a global by-id lookup -- the same
 * {@code lookupCategory} hazard {@link BaseController} guards against for
 * entries, templates, categories and pages. Without the per-id ownership
 * check here, an editor on one weblog could delete another weblog's
 * inquiries by id. A foreign id in the selection is silently skipped rather
 * than removed, and the count reported in {@code submissions.deleted}
 * reflects only what was actually deleted.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class FormSubmissionsController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(FormSubmissionsController.class);
    private static final int COUNT = 30;

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
        return "submissions";
    }

    @Override
    public String getPageTitle() {
        return "submissions.title";
    }

    @GetMapping("/submissions.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "page", required = false, defaultValue = "0") int page) {
        populateCommonModel(request, model);
        loadSubmissions(request, model, page);
        return ".Submissions";
    }

    /**
     * Removes the selected submissions and reloads the first page of the
     * list.
     */
    @PostMapping("/submissions!delete.rol")
    public String delete(@RequestParam(name = "deleteIds", required = false) String[] deleteIds,
                         HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            FormSubmissionManager mgr = weblogger.getFormSubmissionManager();
            Weblog actionWeblog = getActionWeblog(request);
            int deletedCount = 0;

            if (deleteIds != null) {
                for (String id : deleteIds) {
                    FormSubmission submission = mgr.get(id);
                    if (submission != null && actionWeblog.equals(submission.getWeblog())) {
                        mgr.remove(submission);
                        deletedCount++;
                    } else {
                        log.warn("Refusing to delete submission {}: not owned by weblog {}",
                                id, actionWeblog.getHandle());
                    }
                }
            }

            weblogger.flush();
            addMessage(model, "submissions.deleted", Integer.toString(deletedCount), request);
        } catch (WebloggerException ex) {
            log.error("Error deleting form submissions", ex);
            addError(model, "generic.error.check.logs", request);
        }

        loadSubmissions(request, model, 0);
        return ".Submissions";
    }

    private void loadSubmissions(HttpServletRequest request, Model model, int page) {
        Weblog actionWeblog = getActionWeblog(request);
        try {
            FormSubmissionManager mgr = weblogger.getFormSubmissionManager();
            model.addAttribute("submissions", mgr.getSubmissions(actionWeblog, page * COUNT, COUNT));
            model.addAttribute("submissionCount", mgr.getCount(actionWeblog));
            model.addAttribute("page", page);
            model.addAttribute("pageSize", COUNT);
        } catch (WebloggerException ex) {
            log.error("Error getting form submissions for weblog - {}", actionWeblog.getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
    }
}
