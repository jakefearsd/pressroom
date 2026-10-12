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

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.security.sso.SsoSettings;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;


/**
 * Handle user logins.
 */
@Controller
@RequestMapping("/roller-ui")
public class LoginController extends BaseController {

    /** The {@code sso} codes the SSO failure handler sends; anything else is ignored. */
    private static final Map<String, String> SSO_MESSAGES = Map.of(
            "unavailable", "login.sso.unavailable",
            "failed", "login.sso.failed",
            "refused", "login.sso.refused");

    private final SsoSettings sso;

    public LoginController(SsoSettings sso) {
        this.sso = sso;
    }

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "loginPage.title";
    }

    @GetMapping("/login.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "error", required = false) String error,
                          @RequestParam(value = "sso", required = false) String ssoCode) {

        populateCommonModel(request, model);

        // set action error message if there was login error
        if (error != null) {
            addError(model, "error.password.mismatch", request);
        }

        String ssoMessage = ssoCode == null ? null : SSO_MESSAGES.get(ssoCode);
        if (ssoMessage != null) {
            addError(model, ssoMessage, request);
        }

        // spring:message arguments are not escaped, so the label is escaped here.
        model.addAttribute("ssoEnabled", sso.enabled());
        model.addAttribute("ssoLabel", HtmlUtils.htmlEscape(sso.providerLabel()));

        return ".Login";
    }
}
