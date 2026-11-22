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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.BusinessRules;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Site admins manage the shared business records that blogs point at. A
 * business is edited once and emitted as schema.org JSON-LD on every blog
 * that uses it, so a value that fails validation is refused, never cleaned.
 * The rules, and what a save stores, are {@link BusinessRules}'s, shared with
 * {@code /v1/admin/businesses}.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class BusinessesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(BusinessesController.class);

    private static final String LIST_REDIRECT = "redirect:/pressroom-ui/admin/businesses.rol";
    private static final String LIST_VIEW = ".Businesses";
    private static final String EDIT_VIEW = ".BusinessEdit";

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "businesses.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "businesses";
    }

    @ModelAttribute("bean")
    public BusinessBean getBean() {
        return new BusinessBean();
    }

    @GetMapping("/businesses.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadList(request, model);
        return LIST_VIEW;
    }

    @GetMapping("/businesses!edit.rol")
    public String edit(HttpServletRequest request, Model model,
                       @RequestParam(name = "id", required = false) String id) {
        populateCommonModel(request, model);
        BusinessBean bean = new BusinessBean();
        if (StringUtils.isNotBlank(id)) {
            Business existing;
            try {
                existing = find(id);
            } catch (WebloggerException ex) {
                return lookupFailed(id, ex, request, model);
            }
            if (existing == null) {
                addError(model, "businesses.error.notFound", request);
                loadList(request, model);
                return LIST_VIEW;
            }
            bean.copyFrom(existing);
        }
        model.addAttribute("bean", bean);
        model.addAttribute("maxSameAs", BusinessRules.MAX_SAME_AS);
        return EDIT_VIEW;
    }

    @PostMapping("/businesses!save.rol")
    public String save(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                       @ModelAttribute("bean") BusinessBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("bean", bean);
        model.addAttribute("maxSameAs", BusinessRules.MAX_SAME_AS);

        Business business = new Business();
        if (StringUtils.isNotBlank(bean.getId())) {
            try {
                business = find(bean.getId());
            } catch (WebloggerException ex) {
                return lookupFailed(bean.getId(), ex, request, model);
            }
            if (business == null) {
                addError(model, "businesses.error.notFound", request);
                loadList(request, model);
                return LIST_VIEW;
            }
        }

        BusinessRules.Values values = values(bean);
        for (RuleViolation violation : BusinessRules.problems(values)) {
            showViolation(model, violation, request);
        }
        if (hasErrors(model)) {
            return EDIT_VIEW;
        }

        BusinessRules.apply(values, business);
        try {
            weblogger.getBusinessManager().saveBusiness(business);
            weblogger.flush();
        } catch (WebloggerException ex) {
            log.error("Error saving business", ex);
            addError(model, "generic.error.check.logs", request);
            return EDIT_VIEW;
        }
        BusinessRules.invalidateUsers(business, weblogger.getBusinessManager());
        addFlashMessage(redirectAttributes, "businesses.saved", request);
        return LIST_REDIRECT;
    }

    @PostMapping("/businesses!delete.rol")
    public String delete(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "id") String id) {
        populateCommonModel(request, model);
        Business business;
        try {
            business = find(id);
        } catch (WebloggerException ex) {
            return lookupFailed(id, ex, request, model);
        }
        if (business == null) {
            addError(model, "businesses.error.notFound", request);
            loadList(request, model);
            return LIST_VIEW;
        }
        BusinessManager manager = weblogger.getBusinessManager();
        try {
            long uses = manager.countWeblogsUsing(business);
            if (uses > 0) {
                addError(model, "businesses.error.inUse",
                        new Object[] {StringEscapeUtils.escapeHtml4(business.getName()), uses}, request);
                loadList(request, model);
                return LIST_VIEW;
            }
            manager.removeBusiness(business);
            weblogger.flush();
        } catch (WebloggerException ex) {
            // The manager also refuses a business that is in use, so a throw here is an error, not a pass.
            log.error("Error removing business", ex);
            addError(model, "generic.error.check.logs", request);
            loadList(request, model);
            return LIST_VIEW;
        }
        addFlashMessage(redirectAttributes, "businesses.deleted", StringEscapeUtils.escapeHtml4(business.getName()), request);
        return LIST_REDIRECT;
    }

    /** The business, or null when there is none. A lookup that could not run throws: that is not "none". */
    private Business find(String id) throws WebloggerException {
        return weblogger.getBusinessManager().getBusiness(id);
    }

    /** The lookup itself failed: say so, show the list, and do nothing further. */
    private String lookupFailed(String id, WebloggerException ex, HttpServletRequest request, Model model) {
        log.error("Error looking up business {}", id, ex);
        addError(model, "businesses.error.lookupFailed", request);
        loadList(request, model);
        return LIST_VIEW;
    }

    private void loadList(HttpServletRequest request, Model model) {
        List<Business> businesses = new ArrayList<>();
        Map<String, Long> usage = new HashMap<>();
        try {
            BusinessManager manager = weblogger.getBusinessManager();
            businesses = manager.getBusinesses();
            for (Business b : businesses) {
                usage.put(b.getId(), manager.countWeblogsUsing(b));
            }
        } catch (WebloggerException ex) {
            log.error("Error loading businesses", ex);
            addError(model, "generic.error.check.logs", request);
        }
        model.addAttribute("businesses", businesses);
        model.addAttribute("usage", usage);
    }

    /**
     * Shows a rule's violation and marks its control. Each id is written out, not built from the
     * field name, so JspConsistencyTest checks it against the page; a field the form has no
     * control for gets the message alone.
     */
    private void showViolation(Model model, RuleViolation violation, HttpServletRequest request) {
        switch (violation.field()) {
            case "name" -> addFieldError(model, "bean_name", violation, request);
            case "businessType" -> addFieldError(model, "bean_businessType", violation, request);
            case "websiteUrl" -> addFieldError(model, "bean_websiteUrl", violation, request);
            case "bookingUrl" -> addFieldError(model, "bean_bookingUrl", violation, request);
            case "telephone" -> addFieldError(model, "bean_telephone", violation, request);
            case "email" -> addFieldError(model, "bean_email", violation, request);
            case "logoUrl" -> addFieldError(model, "bean_logoUrl", violation, request);
            case "sameAs" -> addFieldError(model, "bean_sameAs", violation, request);
            case "areaServed" -> addFieldError(model, "bean_areaServed", violation, request);
            default -> addError(model, violation, request);
        }
    }

    /** The form's fields as the shared rules take them. */
    private static BusinessRules.Values values(BusinessBean bean) {
        return new BusinessRules.Values(bean.getName(), bean.getBusinessType(), bean.getWebsiteUrl(),
                bean.getBookingUrl(), bean.getTelephone(), bean.getEmail(), bean.getLogoUrl(), bean.getSameAs(),
                bean.getAreaServed(), bean.getDescription());
    }
}
