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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin → Users (spec 2026-10-13, section 4): every account, people and
 * agents, with All / People / Agents tabs and a filter the server applies.
 * It replaced a type-ahead search box that listed nothing until searched.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserAdminController extends BaseController {

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
        return "userAdmin.title.searchUser";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "userAdmin";
    }

    /**
     * @param kind {@code people} or {@code agents} for a tab; anything else is All
     * @param q    matched against username, name and email, ignoring case
     */
    @GetMapping("/userAdmin.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "kind", required = false) String kind,
                          @RequestParam(name = "q", required = false) String q) {
        populateCommonModel(request, model);
        return AccountRows.showList(weblogger, model, kind, q);
    }
}
