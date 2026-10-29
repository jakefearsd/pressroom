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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * Edit a new or existing weblog category.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
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
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryAdd");
        model.addAttribute("pageTitle", getText("categoryForm.add.title", request));

        myValidate(bean, true, request, model);

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
                addFlashMessage(redirectAttributes, "categoryForm.created", category.getName(), request);

                return "redirect:/roller-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
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
        return "redirect:/roller-ui/authoring/categories.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

    // --- categoryEdit ---


    @PostMapping("/categoryEdit!save.rol")
    public String categoryEditSave(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") CategoryBean bean,
                                   RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryEdit");
        model.addAttribute("pageTitle", getText("categoryForm.edit.title", request));

        myValidate(bean, false, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();

                // Ownership-checked: bean.id is client input and the permission
                // interceptor only vouches for the action weblog, so a global
                // by-id lookup here let an editor rename another weblog's
                // categories.
                WeblogCategory category = lookupCategory(bean.getId(), request);
                if (category == null) {
                    addFlashError(redirectAttributes, "categoryForm.error.notFound", request);
                    return "redirect:/roller-ui/authoring/categories.rol?weblog="
                            + getActionWeblog(request).getHandle();
                }
                bean.copyTo(category);

                wmgr.saveWeblogCategory(category);
                weblogger.flush();

                CacheManager.invalidate(getActionWeblog(request));
                addFlashMessage(redirectAttributes, "categoryForm.changesSaved", category.getName(), request);

                return "redirect:/roller-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error saving category", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        // Same reason as categoryAddSave's fallback above.
        carryModelErrorsToFlash(model, redirectAttributes);
        return "redirect:/roller-ui/authoring/categories.rol?weblog="
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

    private void myValidate(CategoryBean bean, boolean isAdd, HttpServletRequest request, Model model) {
        if (bean.getName() == null || !bean.getName().equals(StringEscapeUtils.escapeHtml4(bean.getName()))) {
            addFieldError(model, "category_bean_name", "categoryForm.error.invalidName", request);
        } else if (isAdd) {
            if (getActionWeblog(request).hasCategory(bean.getName())) {
                addFieldError(model, "category_bean_name", "categoryForm.error.duplicateName",
                        new Object[]{bean.getName()}, request);
            }
        } else {
            try {
                WeblogCategory wc = categoryNamed(getActionWeblog(request), bean.getName());
                if (wc != null && !wc.getId().equals(bean.getId())) {
                    addFieldError(model, "category_bean_name", "categoryForm.error.duplicateName",
                            new Object[]{bean.getName()}, request);
                }
            } catch (WebloggerException ex) {
                // Fail closed. A uniqueness check that could not run is not a
                // check that passed, and the caller's hasErrors() gate would
                // otherwise read the empty error list as "this name is free" --
                // the same shape TemplateEditController's name/link checks had.
                log.error("Error checking category name uniqueness for {}", bean.getName(), ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    /**
     * The weblog's category of that name, or null when there is none. A
     * failed lookup propagates rather than answering null: the duplicate-name
     * check in {@link #myValidate} reads null as "no duplicate", so swallowing
     * here would let a rename through unchecked exactly when the category
     * store is unreachable. (It used to, as a move of the fail-open
     * {@code Weblog.getWeblogCategory} getter it replaced.)
     */
    private WeblogCategory categoryNamed(Weblog weblog, String name) throws WebloggerException {
        return weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, name);
    }

    @ModelAttribute("bean")
    public CategoryBean getBean() {
        return new CategoryBean();
    }
}
