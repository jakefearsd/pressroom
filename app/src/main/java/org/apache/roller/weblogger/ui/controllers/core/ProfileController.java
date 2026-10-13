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

package org.apache.roller.weblogger.ui.controllers.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.BaseController;
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
 * Allows user to edit his/her profile.
 */
@Controller
@RequestMapping("/roller-ui")
public class ProfileController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final CommentService comments;

    /** @param comments expires the weblogs showing this account's comments when it is renamed */
    public ProfileController(CommentService comments) {
        this.comments = comments;
    }


    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "yourProfile.title";
    }

    @Override
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        // No prefix — this controller uses Spring form:form tags which don't prefix field names
    }

    @ModelAttribute("bean")
    public ProfileBean getBean() {
        return new ProfileBean();
    }

    @GetMapping("/profile.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") ProfileBean bean) {

        populateCommonModel(request, model);
        addListsToModel(model);

        User ud = getAuthenticatedUser(request);
        // load up the form from the users existing profile data
        bean.copyFrom(ud);
        model.addAttribute("ssoBound", ud.isSsoBound());

        return ".Profile";
    }

    @PostMapping("/profile!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") ProfileBean bean,
                       RedirectAttributes redirectAttributes) {

        populateCommonModel(request, model);
        addListsToModel(model);

        User signedIn = getAuthenticatedUser(request);
        model.addAttribute("ssoBound", signedIn != null && signedIn.isSsoBound());
        myValidate(request, model, bean, signedIn);

        if (!hasErrors(model)) {

            // We ONLY modify the user currently logged in
            User existingUser = getAuthenticatedUser(request);
            String screenNameBefore = existingUser.getScreenName();

            // copy updated attributes
            bean.copyTo(existingUser);

            // If user set both password and passwordConfirm then reset password
            if (!StringUtils.isEmpty(bean.getPasswordText())
                    && !StringUtils.isEmpty(bean.getPasswordConfirm())) {
                existingUser.resetPassword(bean.getPasswordText());
            }

            try {
                UserManager mgr = weblogger.getUserManager();
                mgr.saveUser(existingUser);
                weblogger.flush();
                // The screen name signs this account's comments and replies, and
                // rendered pages expire only through their weblog.
                if (!Objects.equals(screenNameBefore, existingUser.getScreenName())) {
                    comments.expireWhereShown(existingUser);
                }
                addFlashMessage(redirectAttributes, "generic.changes.saved", request);
                return "redirect:/roller-ui/menu.rol";
            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".Profile";
    }

    private void myValidate(HttpServletRequest request, Model model, ProfileBean bean, User signedIn) {
        if (signedIn != null && signedIn.isSsoBound()
                && (!StringUtils.isEmpty(bean.getPasswordText())
                    || !StringUtils.isEmpty(bean.getPasswordConfirm()))) {
            addError(model, "userSettings.error.ssoPassword", request);
        }
        if (!Strings.CS.equals(bean.getPasswordText(), bean.getPasswordConfirm())) {
            // userRegister.* went away with public self-registration; this page
            // has its own key for the same message.
            addFieldError(model, "passwordConfirm", "yourProfile.passwordsNotSame", request);
        }

        // validate timezone
        if (!StringUtils.isEmpty(bean.getTimeZone())) {
            final Optional<String> first = Arrays.stream(TimeZone.getAvailableIDs())
                    .filter(id -> id.equals(bean.getTimeZone())).findFirst();
            if (first.isEmpty()) {
                addFieldError(model, "timeZone", "error.add.user.invalid.timezone", request);
            }
        }

        // validate locale
        if (!StringUtils.isEmpty(bean.getLocale())) {
            final Optional<Locale> first = Arrays.stream(Locale.getAvailableLocales())
                    .filter(locale -> locale.toString().equals(bean.getLocale())).findFirst();
            if (first.isEmpty() || "".equals(first.get().getDisplayName())) {
                addFieldError(model, "locale", "error.add.user.invalid.locale", request);
            }
        }
    }

    private void addListsToModel(Model model) {
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
    }
}
