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
import jakarta.servlet.http.HttpServletResponse;

import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.TokenFormController;
import com.wikantik.pressroom.ui.controllers.TokenFormValues;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Profile → API tokens (spec 2026-10-13, section 3; criterion 15): every
 * signed-in author mints, lists and revokes their own tokens, and every
 * mint -- here or on an agent's page -- lands on this controller's reveal.
 * Under {@code /roller-ui/profile*}, so SecurityConfig admits editors and
 * admins only; a reader holds no login action and is refused.
 */
@Controller
@RequestMapping("/roller-ui")
public class ProfileTokensController extends TokenFormController {

    private static final String CREATE_ACTION = "/roller-ui/profileTokens!create.rol";
    private static final String REVOKE_ACTION = "/roller-ui/profileTokens!revoke.rol";

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "apiTokens.profile.title";
    }

    @GetMapping("/profileTokens.rol")
    public String list(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return showPage(request, model, new TokenFormValues());
    }

    @PostMapping("/profileTokens!create.rol")
    public String create(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "label", required = false) String label,
                         @RequestParam(name = "role", required = false) String role,
                         @RequestParam(name = "pin", required = false) String pin,
                         @RequestParam(name = "expiry", required = false) String expiry) {
        populateCommonModel(request, model);
        User me = getAuthenticatedUser(request);
        TokenFormValues values = new TokenFormValues(label, role, pin, expiry);
        ApiTokenManager.Issued issued = mintToken(me, me, values, model, request);
        if (issued == null) {
            return showPage(request, model, values);
        }
        return revealRedirect(issued, null, redirectAttributes);
    }

    /** A person revokes only their own tokens; any other id answers "no such token". */
    @PostMapping("/profileTokens!revoke.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "id", required = false) String id) {
        User me = getAuthenticatedUser(request);
        switch (revokeToken(me, id, me)) {
            case REVOKED -> addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
            case NOT_FOUND -> addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
            case FAILED -> addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return "redirect:/roller-ui/profileTokens.rol";
    }

    @GetMapping("/profileTokens!reveal.rol")
    public String reveal(HttpServletRequest request, HttpServletResponse response, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "apiTokens.reveal.title");
        return revealPage(response, model);
    }

    private String showPage(HttpServletRequest request, Model model, TokenFormValues values) {
        addTokenSection(model, getAuthenticatedUser(request), values, CREATE_ACTION, REVOKE_ACTION, null);
        return ".ProfileTokens";
    }
}
