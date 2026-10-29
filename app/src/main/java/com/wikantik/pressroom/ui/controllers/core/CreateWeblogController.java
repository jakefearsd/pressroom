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

package com.wikantik.pressroom.ui.controllers.core;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.WeblogCreationRules;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


/**
 * Allows user to create a new website.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class CreateWeblogController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CreateWeblogController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "createWebsite.title";
    }

    @Override
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        // No prefix — this controller uses Spring form:form tags
    }

    @ModelAttribute("bean")
    public CreateWeblogBean getBean() {
        return new CreateWeblogBean();
    }

    @GetMapping("/createWeblog.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") CreateWeblogBean bean) {

        populateCommonModel(request, model);
        List<SharedTheme> themes = addListsToModel(model);

        User user = getAuthenticatedUser(request);

        WeblogCreationRules.Violation refusal;
        try {
            refusal = WeblogCreationRules.mayCreate(user, WeblogCreationRules.Context.current(weblogger));
        } catch (WebloggerException ex) {
            log.error("error checking whether this user may create a weblog", ex);
            addError(model, "generic.error.check.logs", request);
            return ".GenericError";
        }
        if (refusal != null) {
            addError(model, refusal.messageKey(), request);
            return ".GenericError";
        }

        // pre-populate with some logical defaults
        bean.setLocale(user.getLocale());
        bean.setTimeZone(user.getTimeZone());
        bean.setEmailAddress(user.getEmailAddress());
        // Journal is the neutral default its own description promises; with
        // no preselection the picker fell to whichever theme listed first.
        if (bean.getTheme() == null
                && themes.stream().anyMatch(t -> DEFAULT_THEME_ID.equals(t.getId()))) {
            bean.setTheme(DEFAULT_THEME_ID);
        }

        return ".CreateWeblog";
    }

    @PostMapping("/createWeblog!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") CreateWeblogBean bean,
                       RedirectAttributes redirectAttributes) {

        populateCommonModel(request, model);
        addListsToModel(model);

        User user = getAuthenticatedUser(request);
        WeblogCreationRules.Violation refusal;
        try {
            refusal = WeblogCreationRules.mayCreate(user, WeblogCreationRules.Context.current(weblogger));
        } catch (WebloggerException ex) {
            // A check that could not run is not one that passed.
            log.error("error checking whether this user may create a weblog", ex);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
            return "redirect:/pressroom-ui/menu.rol";
        }
        if (refusal != null) {
            addFlashError(redirectAttributes, refusal.messageKey(), request);
            return "redirect:/pressroom-ui/menu.rol";
        }

        myValidate(request, model, bean);

        if (!hasErrors(model)) {

            try {
                WeblogCreationRules.create(bean, user, weblogger.getWeblogManager());
                weblogger.flush();

                addFlashMessage(redirectAttributes, "createWebsite.created", bean.getHandle(), request);
                return "redirect:/pressroom-ui/menu.rol";

            } catch (WebloggerException e) {
                log.error("ERROR adding weblog", e);
                addError(model, e.getMessage(), request);
            }
        }

        return ".CreateWeblog";
    }

    private void myValidate(HttpServletRequest request, Model model, CreateWeblogBean bean) {
        try {
            for (WeblogCreationRules.Violation v : WeblogCreationRules.check(bean,
                    WeblogCreationRules.Context.current(weblogger))) {
                addFieldError(model, v.field(), v.messageKey(), request);
                if ("createWeblog.error.handleExists".equals(v.messageKey())
                        || "createWeblog.error.handleIsSitePage".equals(v.messageKey())) {
                    bean.setHandle(null);
                }
            }
        } catch (WebloggerException ex) {
            // A check that could not run is not one that passed.
            log.error("error checking the new weblog", ex);
            addError(model, "generic.error.check.logs", request);
        }
    }

    /**
     * The {@code frontpage} theme is the {@code $site}-wide aggregator theme
     * (see the matching constant/comment on {@code ThemeEditController}) and
     * breaks any weblog that adopts it. A brand-new weblog can never
     * already be on it, so -- unlike {@code ThemeEditController}'s picker --
     * there is no grandfathering case to preserve here; it is excluded
     * unconditionally.
     */
    private static final String SITEWIDE_ONLY_THEME_ID = "frontpage";

    /** The theme a fresh form preselects, when it is enabled. */
    private static final String DEFAULT_THEME_ID = "journal";

    private List<SharedTheme> addListsToModel(Model model) {
        model.addAttribute("localesList", UIUtils.getLocales());
        model.addAttribute("timeZonesList", UIUtils.getTimeZones());

        ThemeManager themeMgr = weblogger.getThemeManager();
        List<SharedTheme> themes = themeMgr.getEnabledThemesList().stream()
                .filter(t -> !SITEWIDE_ONLY_THEME_ID.equals(t.getId()))
                .toList();
        model.addAttribute("themes", themes);
        return themes;
    }
}
