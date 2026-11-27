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

package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base controller for Spring MVC controllers in Pressroom.
 * Replaces the Struts 2 UIAction base class, providing common utilities
 * for authentication, authorization, message resolution, and model population.
 */
public abstract class BaseController implements UISecurityEnforced, UIActionPreparable {

    private static final Logger log = LoggerFactory.getLogger(BaseController.class);

    @Autowired
    protected MessageSource messageSource;

    // @Lazy is load-bearing: controllers are constructed at context refresh,
    // before WebloggerStartup.prepare() has run. The lazy proxy defers
    // building the business-tier graph to first use rather than at wiring time.
    @Autowired
    @org.springframework.context.annotation.Lazy
    protected Weblogger weblogger;

    /**
     * The one place the server-rendered JSPs build weblog-content urls:
     * {@code ${urls.weblogAbsolute(actionWeblog)}}, {@code ${urls.entry(post)}},
     * {@code ${urls.media(f)}} and so on -- the replacement for the url getters
     * the JPA entities used to reach the {@code URLStrategy} through. It is a
     * {@code @ModelAttribute} rather than a line in {@link #populateCommonModel}
     * so that every handler on every subclass gets it, and it resolves the
     * strategy lazily because this runs for the install wizard too, before the
     * business tier behind the {@code @Lazy weblogger} proxy exists.
     */
    @ModelAttribute("urls")
    public AdminUrls urls() {
        return new AdminUrls(weblogger::getUrlStrategy);
    }

    // --- UIActionPreparable default ---

    @Override
    public void myPrepare() {
        // no-op by default; subclasses override as needed
    }

    // --- UISecurityEnforced defaults (safe: require login + weblog admin) ---

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }

    // --- Request attribute accessors ---

    /**
     * Get the authenticated user from the request attributes (set by the interceptor).
     */
    protected User getAuthenticatedUser(HttpServletRequest request) {
        return (User) request.getAttribute("authenticatedUser");
    }

    /**
     * Get the action weblog from the request attributes (set by the interceptor).
     */
    protected Weblog getActionWeblog(HttpServletRequest request) {
        return (Weblog) request.getAttribute("actionWeblog");
    }

    // --- Message / i18n helpers ---

    /**
     * Resolve a message from the message source using the request locale.
     */
    protected String getText(String key, HttpServletRequest request) {
        return messageSource.getMessage(key, null, key, request.getLocale());
    }

    /**
     * Resolve a message with arguments from the message source using the request locale.
     */
    protected String getText(String key, Object[] args, HttpServletRequest request) {
        return messageSource.getMessage(key, args, key, request.getLocale());
    }

    // --- Model error / message helpers ---

    /**
     * Add an error message (resolved from the message source) to the model.
     */
    protected void addError(Model model, String key, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, request));
    }

    /**
     * Add an error message (resolved from the message source) with a parameter to the model.
     */
    protected void addError(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, new Object[]{param}, request));
    }

    /**
     * Add an error message (resolved from the message source) with arbitrary
     * arguments to the model.
     *
     * <p>For a key carrying more than one placeholder -- or whose arguments
     * are already collected as an {@code Object[]}/{@code String[]}, e.g. a
     * {@link com.wikantik.pressroom.util.PressroomMessages.PressroomMessage}
     * replayed from a validation collector -- the single-{@code param}
     * overload above cannot carry them, and dropping them silently returns
     * the raw, unresolved {@code {0}}/{@code {1}} pattern text: {@code
     * alwaysUseMessageFormat} is {@code false} (see {@code
     * MessageFormatRegressionTest}), so a no-args lookup never runs the
     * pattern through {@code MessageFormat} at all.
     */
    protected void addError(Model model, String key, Object[] args, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, args, request));
    }

    /**
     * Shows a rule's violation (no control marked): its message key with
     * every argument HTML-escaped, because they are raw values, user input
     * among them, and {@code tiles/messages.jsp} does not escape. Two
     * exceptions: a {@link RuleViolation.Label} is resolved to the label's
     * text in the reader's language, the bundle's own words, and shown as the
     * screen showed labels before the rule moved out of it; a {@link Number}
     * stays a number, so the message formats it for the reader's locale,
     * grouped (1,000), and its digits and separators need no escaping.
     */
    protected void addError(Model model, RuleViolation violation, HttpServletRequest request) {
        if (violation.args().isEmpty()) {
            addError(model, violation.messageKey(), request);
        } else {
            addError(model, violation.messageKey(), violation.args().stream()
                    .map(arg -> arg instanceof RuleViolation.Label label ? getText(label.key(), request)
                            : arg instanceof Number ? arg
                            : StringEscapeUtils.escapeHtml4(String.valueOf(arg))).toArray(), request);
        }
    }

    /**
     * Check whether any error messages have been added to the model.
     */
    @SuppressWarnings("unchecked")
    protected boolean hasErrors(Model model) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        return errors != null && !errors.isEmpty();
    }

    /**
     * Add a status message (resolved from the message source) to the model.
     */
    protected void addMessage(Model model, String key, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, request));
    }

    /**
     * Add a status message (resolved from the message source) with a parameter to the model.
     */
    protected void addMessage(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, new Object[]{param}, request));
    }

    // --- Flash attribute helpers (for messages that survive redirects) ---

    /**
     * Add a flash message that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, request));
    }

    /**
     * Add a flash message with a parameter that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, new Object[]{param}, request));
    }

    /**
     * Add a flash error that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, request));
    }

    /**
     * Add a flash error with a parameter that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, new Object[]{param}, request));
    }

    @SuppressWarnings("unchecked")
    private void addToModel(Model model, String attrName, String text) {
        List<String> list = (List<String>) model.getAttribute(attrName);
        if (list == null) {
            list = new ArrayList<>();
            model.addAttribute(attrName, list);
        }
        list.add(text);
    }

    @SuppressWarnings("unchecked")
    private void addToFlash(RedirectAttributes redirectAttributes, String attrName, String text) {
        List<String> list = (List<String>) redirectAttributes.getFlashAttributes().get(attrName);
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(text);
        redirectAttributes.addFlashAttribute(attrName, list);
    }

    // --- Configuration property helpers ---

    /**
     * Get a configuration property value. Checks static config first,
     * then runtime config. Returns the key itself if not found.
     */
    protected String getProp(String key) {
        String value = WebloggerConfig.getProperty(key);
        if (value == null) {
            value = WebloggerRuntimeConfig.getProperty(key);
        }
        return (value == null) ? key : value;
    }

    // --- Permission checks ---

    /**
     * Whether {@code user} holds {@code action} on {@code weblog}. This used to
     * be {@code Weblog.hasUserPermission(User, String)}, an entity method that
     * located the user manager statically; a check that cannot be answered is
     * a denial (logged), exactly as before.
     */
    protected boolean hasWeblogAction(Weblog weblog, User user, String action) {
        try {
            WeblogPermission userPerms =
                    new WeblogPermission(weblog, user, Collections.singletonList(action));
            return weblogger.getUserManager().checkPermission(userPerms, user);
        } catch (WebloggerException ex) {
            // something is going seriously wrong, not much we can do here
            log.error("ERROR checking user permssion", ex);
        }
        return false;
    }

    /**
     * Whether {@code user} holds the global {@code admin} action. This used to
     * be {@code User.hasGlobalPermission("admin")}; a check that cannot be
     * answered is a denial, as before.
     */
    protected boolean isGlobalAdmin(User user) {
        try {
            GlobalPermission perm =
                    new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
            return weblogger.getUserManager().checkPermission(perm, user);
        } catch (WebloggerException ex) {
            return false;
        }
    }

    // --- Common model population ---

    /**
     * Populate common model attributes used across all pages:
     * authenticatedUser, userIsAuthor, actionWeblog, pageTitle, siteURL,
     * absoluteSiteURL.
     */
    protected void populateCommonModel(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        Weblog weblog = getActionWeblog(request);

        model.addAttribute("authenticatedUser", user);
        model.addAttribute("userIsAuthor", isAuthor(user));
        model.addAttribute("actionWeblog", weblog);
        model.addAttribute("pageTitle", getPageTitle());
        model.addAttribute("siteURL", WebloggerRuntimeConfig.getRelativeContextURL());
        model.addAttribute("absoluteSiteURL", WebloggerRuntimeConfig.getAbsoluteContextURL());
    }

    /**
     * Whether {@code user} may use the authoring UI at all: holds the global
     * {@code login} action. A reader (SSO sign-in) does not, so the top bar
     * offers them no "Main Menu" link that the app home would refuse.
     * A display path: a check that cannot run answers "not an author".
     */
    protected boolean isAuthor(User user) {
        if (user == null) {
            return false;
        }
        try {
            return weblogger.getUserManager().checkPermission(
                    new GlobalPermission(List.of(GlobalPermission.LOGIN)), user);
        } catch (WebloggerException ex) {
            log.warn("Could not check whether {} may author; the top bar offers no Main Menu",
                    user.getUserName(), ex);
            return false;
        }
    }

    // --- Overridable metadata ---

    /**
     * Returns the page title for the current action. Subclasses should override
     * to provide a meaningful title (typically a message key).
     */
    public String getPageTitle() {
        return "";
    }

}
