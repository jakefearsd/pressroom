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
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.HashSet;
import java.util.Set;
import com.wikantik.pressroom.pojos.WeblogCategory;

/**
 * Manage weblog categories.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class CategoriesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CategoriesController.class);

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
        return "categories";
    }

    @Override
    public String getPageTitle() {
        return "categoriesForm.rootTitle";
    }

    @GetMapping("/categories.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            List<WeblogCategory> categories = wmgr.getWeblogCategories(getActionWeblog(request));
            model.addAttribute("allCategories", categories);
            // Categories.jsp used to ask each category ${category.inUse} -- a
            // query behind an entity getter. Answered here, once per row.
            Set<String> inUse = new HashSet<>();
            for (WeblogCategory category : categories) {
                if (wmgr.isWeblogCategoryInUse(category)) {
                    inUse.add(category.getId());
                }
            }
            model.addAttribute("categoriesInUse", inUse);
        } catch (WebloggerException ex) {
            log.error("Error building categories list", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".Categories";
    }
}
