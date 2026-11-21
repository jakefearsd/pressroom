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


import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.ui.view.MainMenuView;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Allows user to view and pick from list of his/her websites.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class MainMenuController extends BaseController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "yourWebsites.title";
    }

    @GetMapping("/menu.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        populateMenuData(request, model);
        return ".MainMenu";
    }

    private void populateMenuData(HttpServletRequest request, Model model) {
        MainMenuView view = MainMenuView.of(getAuthenticatedUser(request), weblogger);
        model.addAttribute("existingPermissions", view.weblogs());
        model.addAttribute("userIsAdmin", view.siteAdmin());
        model.addAttribute("siteSpace", view.siteSpace());
    }
}
