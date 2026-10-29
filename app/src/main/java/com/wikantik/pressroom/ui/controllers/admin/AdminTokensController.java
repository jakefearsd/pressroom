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
import com.wikantik.pressroom.ui.controllers.TokenFormController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin → API tokens (spec 2026-10-13, section 3; criteria 13 and 14): every
 * live token on the site, people's and agents', with owner, kind and issuer;
 * a toggle adds revoked and expired ones; a site admin revokes any of them.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class AdminTokensController extends TokenFormController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "apiTokens";
    }

    @Override
    public String getPageTitle() {
        return "apiTokens.admin.title";
    }

    /** @param all {@code true} to add revoked and expired tokens */
    @GetMapping("/apiTokens.rol")
    public String list(HttpServletRequest request, Model model,
                       @RequestParam(name = "all", required = false) String all) {
        populateCommonModel(request, model);
        boolean includeInactive = "true".equals(all);
        model.addAttribute("showAll", includeInactive);
        model.addAttribute("tokenRows", TokenRow.load(weblogger, includeInactive));
        return ".ApiTokens";
    }

    @PostMapping("/apiTokens!revoke.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "id", required = false) String id,
                         @RequestParam(name = "all", required = false) String all) {
        switch (revokeToken(getAuthenticatedUser(request), id, null)) {
            case REVOKED -> addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
            case NOT_FOUND -> addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
            case FAILED -> addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        if ("true".equals(all)) {
            redirectAttributes.addAttribute("all", "true");
        }
        return "redirect:/roller-ui/admin/apiTokens.rol";
    }
}
