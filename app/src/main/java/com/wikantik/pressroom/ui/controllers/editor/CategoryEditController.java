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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.DuplicateCategoryNameException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.CategoryRules;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Edit a new or existing weblog category.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class CategoryEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CategoryEditController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- categoryAdd ---


    @PostMapping("/categoryAdd!save.rol")
    public String categoryAddSave(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") CategoryBean bean,
                                  RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model, new Screen("categoryAdd", getText("categoryForm.add.title", request)));

        myValidate(bean, null, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogCategory category = new WeblogCategory();
                category.setWeblog(getActionWeblog(request));
                bean.copyTo(category);

                getActionWeblog(request).addCategory(category);
                category.calculatePosition();

                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                wmgr.saveWeblogCategory(category);
                weblogger.flush();

                CacheManager.invalidate(getActionWeblog(request));
                addFlashMessage(redirectAttributes, "categoryForm.created",
                        StringEscapeUtils.escapeHtml4(category.getName()), request);

                return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (DuplicateCategoryNameException ex) {
                // An overlapping add took the name after myValidate's check.
                addFieldError(model, "category_bean_name", "categoryForm.error.duplicateName",
                        new Object[]{StringEscapeUtils.escapeHtml4(bean.getName())}, request);
            } catch (Exception ex) {
                log.error("Error saving category", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        // Add/edit is driven by #category-edit-modal on the category list; there
        // is no standalone form page to redisplay, so the specific complaint
        // (myValidate's field error, or the generic save-failure message just
        // added above) has to survive the redirect as a flash, or the list
        // page shows a bland "check the logs" banner no matter what was wrong
        // and never marks the field that caused it.
        carryModelErrorsToFlash(model, redirectAttributes);
        return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

    // --- categoryEdit ---


    @PostMapping("/categoryEdit!save.rol")
    public String categoryEditSave(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") CategoryBean bean,
                                   RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model, new Screen("categoryEdit", getText("categoryForm.edit.title", request)));

        // Ownership-checked: bean.id is client input and the permission
        // interceptor only vouches for the action weblog, so a global
        // by-id lookup here let an editor rename another weblog's
        // categories. Looked up before validating, which needs its name.
        WeblogCategory category = lookupCategory(bean.getId(), request);
        myValidate(bean, category, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                if (category == null) {
                    addFlashError(redirectAttributes, "categoryForm.error.notFound", request);
                    return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                            + getActionWeblog(request).getHandle();
                }
                bean.copyTo(category);

                wmgr.saveWeblogCategory(category);
                weblogger.flush();

                CacheManager.invalidate(getActionWeblog(request));
                addFlashMessage(redirectAttributes, "categoryForm.changesSaved",
                        StringEscapeUtils.escapeHtml4(category.getName()), request);

                return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error saving category", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        // Same reason as categoryAddSave's fallback above.
        carryModelErrorsToFlash(model, redirectAttributes);
        return "redirect:/pressroom-ui/authoring/categories.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

    /**
     * Carries whatever {@link #myValidate} (or a caught save failure) put on
     * the model's {@code errors}/{@code invalidFields} across a redirect, the
     * same way {@link BaseController#addFlashError} carries a single message.
     * By the time either save method reaches this call the model is
     * guaranteed to hold at least one error -- either a field-specific
     * complaint from {@code myValidate} (the {@code hasErrors} gate above
     * never entered the try block) or the generic
     * {@code generic.error.check.logs} the catch block just added -- so this
     * replaces a separate explicit flash of that generic key rather than
     * supplementing it.
     */
    @SuppressWarnings("unchecked")
    private void carryModelErrorsToFlash(Model model, RedirectAttributes redirectAttributes) {
        List<String> modelErrors = (List<String>) model.getAttribute("errors");
        if (modelErrors != null && !modelErrors.isEmpty()) {
            redirectAttributes.addFlashAttribute("errors", new ArrayList<>(modelErrors));
        }
        Object invalidFields = model.getAttribute(INVALID_FIELDS);
        if (invalidFields != null) {
            redirectAttributes.addFlashAttribute(INVALID_FIELDS, invalidFields);
        }
        Object invalidFieldIds = model.getAttribute(INVALID_FIELD_IDS);
        if (invalidFieldIds != null) {
            redirectAttributes.addFlashAttribute(INVALID_FIELD_IDS, invalidFieldIds);
        }
    }

    /**
     * {@code edited} is the category being edited, null on an add (or when
     * the id names none of this weblog's). An edit that sends back the
     * category's own name is not judged again, as on the API's PATCH.
     */
    private void myValidate(CategoryBean bean, WeblogCategory edited, HttpServletRequest request, Model model) {
        try {
            RuleViolation problem = CategoryRules.keepsItsName(edited, bean.getName()) ? null
                    : CategoryRules.nameProblem(getActionWeblog(request), bean.getName(),
                            edited == null ? null : edited.getId(), weblogger.getWeblogEntryManager());
            if (problem != null) {
                addFieldError(model, "category_bean_name", problem, request);
            }
        } catch (WebloggerException e) {
            // Fail closed. A uniqueness check that could not run is not a check that passed: the
            // caller's hasErrors() gate would otherwise read the empty error list as "this name is free".
            log.error("Error checking category name uniqueness for {}", bean.getName(), e);
            addError(model, "generic.error.check.logs", request);
        }
        // The API's rule too: an over-long description is the author's to fix, not a column
        // overflow the save reports as "check the logs".
        RuleViolation description = CategoryRules.descriptionProblem(bean.getDescription());
        if (description != null) {
            addFieldError(model, "category_bean_description", description, request);
        }
    }

    @ModelAttribute("bean")
    public CategoryBean getBean() {
        return new CategoryBean();
    }
}
