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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Business;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.CustomDomainRules;
import org.apache.roller.weblogger.ui.controllers.WeblogSettingsRules;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Action for modifying weblog configuration.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class WeblogConfigController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(WeblogConfigController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "weblogConfig";
    }

    @Override
    public String getPageTitle() {
        return "websiteSettings.title";
    }

    @GetMapping("/weblogConfig.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") WeblogConfigBean bean) {
        populateCommonModel(request, model);
        loadFormData(model, request);

        bean.copyFrom(getActionWeblog(request));

        return ".WeblogConfig";
    }

    @PostMapping("/weblogConfig!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") WeblogConfigBean bean) {
        populateCommonModel(request, model);
        loadFormData(model, request);

        myValidate(bean, request, model);

        if (!hasErrors(model)) {
            try {
                Weblog weblog = getActionWeblog(request);

                bean.copyTo(weblog, weblogger.getBusinessManager());

                weblogger.getWeblogManager().saveWeblog(weblog);

                weblogger.flush();
                addMessage(model, "websiteSettings.savedChanges", request);
                CacheManager.invalidate(weblog);

            } catch (Exception ex) {
                log.error("Error updating weblog config", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".WeblogConfig";
    }

    private void myValidate(WeblogConfigBean bean, HttpServletRequest request, Model model) {
        // The real action weblog's handle, never bean.getHandle(): the "bean." prefix binds
        // bean.handle from POST data the JSP never renders (I4), so it cannot be trusted here.
        WeblogSettingsRules.Context context =
                WeblogSettingsRules.Context.current(getActionWeblog(request).getHandle(), weblogger);
        for (WeblogSettingsRules.Violation v : WeblogSettingsRules.check(bean, context)) {
            String fieldId = "weblog_bean_" + v.field();
            if (v.labelKey() == null) {
                addFieldError(model, fieldId, v.messageKey(), request);
            } else {
                addFieldError(model, fieldId, v.messageKey(),
                        new Object[] {getText(v.labelKey(), request), v.max()}, request);
            }
        }
        // Only warn about the zone when the save is actually going to happen (see the history of this check).
        if (!hasErrors(model) && CustomDomainRules.isOutsideCertZones(bean.getCustomDomain(),
                WebloggerConfig.getProperty("vhost.cert.zones"))) {
            model.addAttribute("customDomainWarning", bean.getCustomDomain());
        }
    }

    /**
     * The businesses to choose from. A list that could not load is an error,
     * not an empty list: a select offering only "None" would post an empty id
     * and the save would unlink the blog's business. The error refuses this
     * request's save, and the {@code businessesUnavailable} flag makes the
     * form carry the current business id in a hidden input instead.
     */
    private void loadBusinesses(Model model, HttpServletRequest request) {
        List<Business> businesses = new ArrayList<>();
        try {
            businesses = weblogger.getBusinessManager().getBusinesses();
        } catch (WebloggerException e) {
            log.error("Error loading businesses", e);
            addError(model, "websiteSettings.business.listUnavailable", request);
            model.addAttribute("businessesUnavailable", Boolean.TRUE);
        }
        model.addAttribute("businesses", businesses);
    }

    private void loadFormData(Model model, HttpServletRequest request) {
        loadBusinesses(model, request);
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
        // The same ceiling myValidate() rejects against, so the field's max=
        // and the server's refusal cannot drift apart. Read per request: it is
        // a runtime property (Admin Settings), not a startup one.
        model.addAttribute("maxEntriesPerPage",
                WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"));
    }

    @ModelAttribute("bean")
    public WeblogConfigBean getBean() {
        return new WeblogConfigBean();
    }
}
