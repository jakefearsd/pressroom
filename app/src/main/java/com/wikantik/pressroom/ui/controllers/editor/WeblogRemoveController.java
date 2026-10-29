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

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Action for removing a weblog.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class WeblogRemoveController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(WeblogRemoveController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "weblogRemove";
    }

    @Override
    public String getPageTitle() {
        return "websiteRemove.title";
    }

    @GetMapping("/weblogRemove.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return ".WeblogRemoveConfirm";
    }

    @PostMapping("/weblogRemove!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        try {
            weblogger.getWeblogManager().removeWeblog(getActionWeblog(request));
            weblogger.flush();
            CacheManager.invalidate(getActionWeblog(request));
            addFlashMessage(redirectAttributes, "websiteRemove.success",
                    StringEscapeUtils.escapeHtml4(getActionWeblog(request).getName()), request);

            return "redirect:/pressroom-ui/menu.rol";
        } catch (Exception ex) {
            log.error("Error removing weblog - {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "websiteRemove.error",
                    StringEscapeUtils.escapeHtml4(getActionWeblog(request).getName()), request);
        }

        return ".WeblogRemoveConfirm";
    }
}
