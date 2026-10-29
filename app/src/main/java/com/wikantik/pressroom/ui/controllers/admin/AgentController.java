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

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.validator.routines.EmailValidator;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.ui.controllers.TokenFormController;
import com.wikantik.pressroom.ui.controllers.TokenFormValues;
import com.wikantik.pressroom.ui.controllers.WeblogPermissionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * New agent and an agent's page (spec 2026-10-13, sections 1, 3 and 4). An
 * agent is a {@code pressroom_user} with kind AGENT that never signs in: its page
 * has no password and no set-password link, only its details, one confirmed
 * Disable or Enable, its weblogs, and the tokens a site admin mints for it.
 * Every change writes a {@code pressroom.admin} line.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class AgentController extends TokenFormController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private static final String MINT_ACTION = "/pressroom-ui/admin/agent!mintToken.rol";
    private static final String REVOKE_ACTION = "/pressroom-ui/admin/agent!revokeToken.rol";

    /** pressroom_user.screenname and fullname are varchar(255). */
    private static final int MAX_NAME_LENGTH = 255;

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
        return "agent";
    }

    @Override
    public String getPageTitle() {
        return "agent.title";
    }

    @ModelAttribute("bean")
    public AgentBean getBean() {
        return new AgentBean();
    }

    @GetMapping("/createAgent.rol")
    public String createForm(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "agent.create.title");
        model.addAttribute("bean", new AgentBean());
        return ".AgentCreate";
    }

    @PostMapping("/createAgent!save.rol")
    public String create(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                         @ModelAttribute("bean") AgentBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "agent.create.title");
        model.addAttribute("bean", bean);
        String userNameProblem = UserNames.problem(bean.getUserName(), weblogger.getUserManager());
        if (userNameProblem != null) {
            addFieldError(model, "bean_userName", userNameProblem, request);
        }
        String name = checkedName(bean, model, request);
        String email = StringUtils.trimToNull(bean.getEmailAddress());
        if (email != null && !EmailValidator.getInstance().isValid(email)) {
            addFieldError(model, "bean_emailAddress", "personForm.error.email", request);
        }
        String notes = checkedNotes(bean, model, request);
        if (hasErrors(model)) {
            return ".AgentCreate";
        }

        User agent = new User();
        agent.setUserName(bean.getUserName());
        agent.setScreenName(name);
        agent.setFullName(name);
        agent.setEmailAddress(email);
        agent.setNotes(notes);
        agent.setLocale(Locale.getDefault().toString());
        agent.setTimeZone(TimeZone.getDefault().getID());
        agent.setDateCreated(new Date());
        try {
            weblogger.getUserManager().addAgent(agent, bean.isSiteAdmin());
            weblogger.flush();
        } catch (WebloggerException e) {
            log.error("Could not create the agent {}", bean.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return ".AgentCreate";
        }
        AdminLog.record("agent.created", getAuthenticatedUser(request),
                "agent=" + agent.getUserName() + " siteAdmin=" + bean.isSiteAdmin());
        if (bean.isSiteAdmin()) {
            // The same line the person editor and the agent page write, so one grep finds every grant.
            AdminLog.record("siteAdmin.granted", getAuthenticatedUser(request), "account=" + agent.getUserName());
        }
        addFlashMessage(redirectAttributes, "agent.created",
                StringEscapeUtils.escapeHtml4(agent.getUserName()), request);
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/pressroom-ui/admin/agent.rol";
    }

    @GetMapping("/agent.rol")
    public String page(HttpServletRequest request, Model model,
                       @RequestParam(name = "userName", required = false) String userName) {
        populateCommonModel(request, model);
        User agent = lookupAgent(userName);
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        return showAgentFromStore(request, model, agent, new TokenFormValues());
    }

    @PostMapping("/agent!save.rol")
    public String save(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                       @ModelAttribute("bean") AgentBean bean) {
        populateCommonModel(request, model);
        User agent = lookupAgent(bean.getUserName());
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        String name = checkedName(bean, model, request);
        String notes = checkedNotes(bean, model, request);
        if (hasErrors(model)) {
            return showAgent(model, agent, bean, new TokenFormValues());
        }
        try {
            UserManager users = weblogger.getUserManager();
            agent.setScreenName(name);
            agent.setFullName(name);
            agent.setNotes(notes);
            users.saveUser(agent);
            boolean wasSiteAdmin = users.hasRole("admin", agent);
            if (bean.isSiteAdmin() && !wasSiteAdmin) {
                users.grantRole("admin", agent);
            } else if (!bean.isSiteAdmin() && wasSiteAdmin) {
                users.revokeRole("admin", agent);
            }
            weblogger.flush();
            if (bean.isSiteAdmin() != wasSiteAdmin) {
                AdminLog.record(bean.isSiteAdmin() ? "siteAdmin.granted" : "siteAdmin.removed",
                        getAuthenticatedUser(request), "account=" + agent.getUserName());
            }
        } catch (WebloggerException e) {
            log.error("Could not save the agent {}", agent.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return showAgent(model, agent, bean, new TokenFormValues());
        }
        addFlashMessage(redirectAttributes, "agent.saved", request);
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/pressroom-ui/admin/agent.rol";
    }

    /** One button behind data-confirm; disabling ends every token on its next request. */
    @PostMapping("/agent!setEnabled.rol")
    public String setEnabled(HttpServletRequest request, RedirectAttributes redirectAttributes,
                             @RequestParam(name = "userName", required = false) String userName,
                             @RequestParam(name = "enabled", required = false) String enabled) {
        User agent = lookupAgent(userName);
        if (agent == null) {
            addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
            return "redirect:/pressroom-ui/admin/userAdmin.rol";
        }
        boolean enable = Boolean.parseBoolean(enabled);
        boolean wasEnabled = !Boolean.FALSE.equals(agent.getEnabled());
        try {
            agent.setEnabled(enable);
            weblogger.getUserManager().saveUser(agent);
            weblogger.flush();
            if (enable != wasEnabled) {
                AdminLog.record(enable ? "account.enabled" : "account.disabled", getAuthenticatedUser(request),
                        "account=" + agent.getUserName());
            }
            addFlashMessage(redirectAttributes, enable ? "agent.enabled" : "agent.disabled", request);
        } catch (WebloggerException e) {
            log.error("Could not change whether the agent {} is enabled", agent.getUserName(), e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/pressroom-ui/admin/agent.rol";
    }

    @PostMapping("/agent!mintToken.rol")
    public String mint(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                       @RequestParam(name = "userName", required = false) String userName,
                       @RequestParam(name = "label", required = false) String label,
                       @RequestParam(name = "role", required = false) String role,
                       @RequestParam(name = "pin", required = false) String pin,
                       @RequestParam(name = "expiry", required = false) String expiry) {
        populateCommonModel(request, model);
        User agent = lookupAgent(userName);
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        TokenFormValues values = new TokenFormValues(label, role, pin, expiry);
        ApiTokenManager.Issued issued = mintToken(agent, getAuthenticatedUser(request), values, model, request);
        if (issued == null) {
            return showAgentFromStore(request, model, agent, values);
        }
        return revealRedirect(issued, agent.getUserName(), redirectAttributes);
    }

    /** From an agent's page only that agent's tokens; any other id answers "no such token". */
    @PostMapping("/agent!revokeToken.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "id", required = false) String id) {
        User agent = lookupAgent(userName);
        if (agent == null) {
            addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
            return "redirect:/pressroom-ui/admin/userAdmin.rol";
        }
        switch (revokeToken(getAuthenticatedUser(request), id, agent)) {
            case REVOKED -> addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
            case NOT_FOUND -> addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
            case FAILED -> addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/pressroom-ui/admin/agent.rol";
    }

    /**
     * The agent page with its details read from the store. This is a decision
     * path, not a display one: the Site administrator box shows what a later
     * Save will write, so a role that cannot be read must not render as
     * "unticked" and let that Save remove it. The page is replaced by an error.
     */
    private String showAgentFromStore(HttpServletRequest request, Model model, User agent, TokenFormValues values) {
        boolean siteAdmin;
        try {
            siteAdmin = weblogger.getUserManager().checkPermission(
                    new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN)), agent);
        } catch (WebloggerException e) {
            log.error("Could not read whether the agent {} is a site administrator", agent.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        return showAgent(model, agent, AgentBean.of(agent, siteAdmin), values);
    }

    private String showAgent(Model model, User agent, AgentBean bean, TokenFormValues values) {
        model.addAttribute("bean", bean);
        model.addAttribute("agentEnabled", Boolean.TRUE.equals(agent.getEnabled()));
        model.addAttribute("permissions", permissionsOf(agent));
        UserWeblogsModel.populate(weblogger, model, agent);
        addTokenSection(model, agent, values, MINT_ACTION, REVOKE_ACTION, agent.getUserName());
        return ".Agent";
    }

    /** The trimmed name, with a field error on {@code bean_name} when it is missing or too long. */
    private String checkedName(AgentBean bean, Model model, HttpServletRequest request) {
        String name = StringUtils.trimToNull(bean.getName());
        if (name == null) {
            addFieldError(model, "bean_name", "agent.error.nameRequired", request);
        } else if (name.length() > MAX_NAME_LENGTH) {
            addFieldError(model, "bean_name", "agent.error.nameTooLong", request);
        }
        return name;
    }

    private String checkedNotes(AgentBean bean, Model model, HttpServletRequest request) {
        String notes = StringUtils.trimToNull(bean.getNotes());
        if (notes != null && notes.length() > User.NOTES_MAX_LENGTH) {
            addFieldError(model, "bean_notes", "agent.error.notesTooLong", request);
        }
        return notes;
    }

    /** A display path: a store that cannot list shows no memberships. */
    private List<WeblogPermissionView> permissionsOf(User agent) {
        try {
            return WeblogPermissionView.resolve(weblogger.getUserManager().getWeblogPermissions(agent), weblogger);
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs of {}", agent.getUserName(), e);
            return List.of();
        }
    }

    /** The agent named, or null -- for a person, an unknown name, or a lookup that failed; nothing proceeds. */
    private User lookupAgent(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        try {
            User user = weblogger.getUserManager().getUserByUserName(userName, null);
            return user != null && user.isAgent() ? user : null;
        } catch (WebloggerException e) {
            log.error("Could not look up the agent {}", userName, e);
            return null;
        }
    }
}
