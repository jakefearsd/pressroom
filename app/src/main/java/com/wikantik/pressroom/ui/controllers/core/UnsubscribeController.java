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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.comments.UnsubscribeTokens;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The link at the foot of a reply email: {@code /pressroom-ui/unsubscribe.rol?t=<token>}.
 *
 * <p>No sign-in: the signed token ({@link UnsubscribeTokens}) is the
 * authority, and the POST is exempt from CSRF in {@code SecurityConfig}
 * because a mail client's one-click POST (RFC 8058) carries no CSRF token.
 * A GET only shows the one-button confirmation, so a mail scanner that
 * follows links changes nothing; the POST, the form's or the mail client's,
 * turns the account's reply emails off. A token that does not verify, or
 * names no account, gets the page's error with 400.
 *
 * <p>Fails closed: a lookup or save that cannot run says so (500), never
 * "done". Logs the account's username, never the token.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class UnsubscribeController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger("pressroom.comments");

    private static final String VIEW = ".Unsubscribe";
    private static final String STATE = "unsubscribeState";

    private final UnsubscribeTokens tokens;

    /** @param tokens verifies the link's token */
    public UnsubscribeController(UnsubscribeTokens tokens) {
        this.tokens = tokens;
    }

    // ------------------------------------------------------------- security

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.emptyList();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    @Override
    public String getPageTitle() {
        return "unsubscribe.title";
    }

    // ------------------------------------------------------------- the page

    /** The confirmation; changes nothing. */
    @GetMapping("/unsubscribe.rol")
    public String confirm(@RequestParam(name = "t", required = false) String token,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        prepare(request, response, model);
        try {
            if (holder(token).isEmpty()) {
                return refuse(response, model);
            }
        } catch (WebloggerException ex) {
            return fail("Could not read the account for an unsubscribe link", ex, response, model);
        }
        model.addAttribute("token", token);
        model.addAttribute(STATE, "confirm");
        return VIEW;
    }

    /** Turns the account's reply emails off: the confirmation's button and the one-click POST alike. */
    @PostMapping("/unsubscribe.rol")
    public String unsubscribe(@RequestParam(name = "t", required = false) String token,
                              HttpServletRequest request, HttpServletResponse response, Model model) {
        prepare(request, response, model);
        try {
            Optional<User> account = holder(token);
            if (account.isEmpty()) {
                return refuse(response, model);
            }
            User user = account.get();
            if (user.isNotifyReplies()) {
                user.setNotifyReplies(false);
                weblogger.getUserManager().saveUser(user);
                weblogger.flush();
                log.info("account {} turned reply emails off by unsubscribe link", user.getUserName());
            }
        } catch (WebloggerException ex) {
            weblogger.release();
            return fail("Could not turn reply emails off by unsubscribe link", ex, response, model);
        }
        model.addAttribute(STATE, "done");
        return VIEW;
    }

    // ------------------------------------------------------------- helpers

    private void prepare(HttpServletRequest request, HttpServletResponse response, Model model) {
        populateCommonModel(request, model);
        // The token is a standing credential for this account's switch: no cache keeps the page.
        response.setHeader("Cache-Control", "private, no-store");
    }

    /** The account the token was issued to, when it verifies and the account still exists. */
    private Optional<User> holder(String token) throws WebloggerException {
        Optional<String> userId = tokens.verify(token, UnsubscribeTokens.REPLIES);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(weblogger.getUserManager().getUser(userId.get()));
    }

    private static String refuse(HttpServletResponse response, Model model) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        model.addAttribute(STATE, "invalid");
        return VIEW;
    }

    private static String fail(String what, WebloggerException ex, HttpServletResponse response, Model model) {
        log.error(what, ex);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        model.addAttribute(STATE, "error");
        return VIEW;
    }
}
