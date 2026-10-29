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

import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Action which handles editing for a single WeblogTemplate.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class TemplateEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TemplateEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "templateEdit";
    }

    @Override
    public String getPageTitle() {
        return "pagesForm.title";
    }

    @GetMapping("/templateEdit.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") TemplateEditBean bean) {
        populateCommonModel(request, model);
        addTemplateLanguages(model);

        WeblogTemplate template = lookupTemplate(bean.getId(), request);
        if (template == null) {
            addError(model, "pageForm.error.notFound", request);
            return ".Templates";
        }

        try {
            bean.copyFrom(template);
            model.addAttribute("template", template);

            if (StringUtils.isEmpty(template.getOutputContentType())) {
                bean.setAutoContentType(Boolean.TRUE);
            } else {
                bean.setAutoContentType(Boolean.FALSE);
                bean.setManualContentType(template.getOutputContentType());
            }
        } catch (WebloggerException ex) {
            log.error("Error updating page - {}", bean.getId(), ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".TemplateEdit";
    }

    @PostMapping("/templateEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") TemplateEditBean bean) {
        populateCommonModel(request, model);
        addTemplateLanguages(model);

        WeblogTemplate template = lookupTemplate(bean.getId(), request);
        if (template == null) {
            addError(model, "pageForm.error.notFound", request);
            return ".Templates";
        }

        model.addAttribute("template", template);

        myValidate(bean, template, request, model);

        if (!hasErrors(model)) {
            try {
                bean.copyTo(template);
                // The bean writes the edited markup onto the STANDARD rendition
                // (creating one if the template had none) but persists nothing;
                // saving it is this controller's job, before the template
                // itself, as it always was.
                weblogger.getWeblogManager().saveTemplateRendition(
                        template.getTemplateRendition(RenditionType.STANDARD));
                template.setLastModified(new Date());

                if (bean.getAutoContentType() == null || !bean.getAutoContentType()) {
                    template.setOutputContentType(bean.getManualContentType());
                } else {
                    template.setOutputContentType(null);
                }

                weblogger.getWeblogManager().saveTemplate(template);
                weblogger.flush();
                CacheManager.invalidate(template);

                addMessage(model, "pageForm.save.success",
                        StringEscapeUtils.escapeHtml4(template.getName()), request);

            } catch (Exception ex) {
                log.error("Error updating page - {}", bean.getId(), ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".TemplateEdit";
    }

    private void myValidate(TemplateEditBean bean, WeblogTemplate template,
                            HttpServletRequest request, Model model) {
        if (!template.getName().equals(bean.getName())) {
            try {
                if (weblogger.getWeblogManager()
                        .getTemplateByName(getActionWeblog(request), bean.getName()) != null) {
                    addError(model, "pagesForm.error.alreadyExists",
                            StringEscapeUtils.escapeHtml4(bean.getName()), request);
                }
            } catch (WebloggerException ex) {
                // Fail closed. A uniqueness check that could not run is not a
                // check that passed, and the caller's hasErrors() gate would
                // otherwise read the empty error list as "this name is free".
                log.error("Error checking page name uniqueness", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        if (!StringUtils.isEmpty(bean.getLink()) && !bean.getLink().equals(template.getLink())) {
            try {
                if (weblogger.getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request), bean.getLink()) != null) {
                    addError(model, "pagesForm.error.alreadyExists",
                            StringEscapeUtils.escapeHtml4(bean.getLink()), request);
                }
            } catch (WebloggerException ex) {
                // Fail closed, as above -- the link is what the page is served
                // under, so a collision is worse here than for the name.
                log.error("Error checking page link uniqueness", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    private void addTemplateLanguages(Model model) {
        Map<TemplateLanguage, String> langMap = new EnumMap<>(TemplateLanguage.class);
        for (TemplateLanguage lang : TemplateLanguage.values()) {
            langMap.put(lang, lang.getReadableName());
        }
        model.addAttribute("templateLanguages", langMap);
    }

    @ModelAttribute("bean")
    public TemplateEditBean getBean() {
        return new TemplateEditBean();
    }
}
