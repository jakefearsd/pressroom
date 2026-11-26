package com.wikantik.pressroom.ui.controllers;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * An agent account's rules and audited writes, in one place for the agent screens ({@code AgentController})
 * and the session-only {@code /v1/admin/agents} routes (SA-18). An agent is a {@code pressroom_user} of kind
 * AGENT that never signs in; its Site administrator switch is the admin role. Every change writes the
 * {@code pressroom.admin} line the screens always wrote, so one grep finds every grant.
 */
public final class AgentAccounts {

    public static final String NAME = "name";
    public static final String NOTES = "notes";

    /** pressroom_user.screenname and fullname are varchar(255). */
    public static final int MAX_NAME_LENGTH = 255;

    private AgentAccounts() {
    }

    /** A name is required and at most {@value #MAX_NAME_LENGTH} characters, as trimmed; or null when it passes. */
    public static RuleViolation nameProblem(String trimmedName) {
        if (trimmedName == null) {
            return RuleViolation.invalid(NAME, "agent.error.nameRequired", List.of(), ProblemCode.FIELD_BLANK, NAME);
        }
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            return RuleViolation.invalid(NAME, "agent.error.nameTooLong", List.of(), ProblemCode.FIELD_TOO_LONG,
                    NAME, Integer.toString(MAX_NAME_LENGTH));
        }
        return null;
    }

    /** Notes are at most {@link User#NOTES_MAX_LENGTH} characters, as trimmed; or null when they pass. */
    public static RuleViolation notesProblem(String trimmedNotes) {
        if (trimmedNotes != null && trimmedNotes.length() > User.NOTES_MAX_LENGTH) {
            return RuleViolation.invalid(NOTES, "agent.error.notesTooLong", List.of(), ProblemCode.FIELD_TOO_LONG,
                    NOTES, Integer.toString(User.NOTES_MAX_LENGTH));
        }
        return null;
    }

    /**
     * The agent named, or null for a person, an unknown name or none. A lookup that fails throws: this is a
     * decision path, and nothing proceeds on a lookup that could not run.
     */
    public static User find(Weblogger weblogger, String userName) throws WebloggerException {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        User user = weblogger.getUserManager().getUserByUserName(userName, null);
        return user != null && user.isAgent() ? user : null;
    }

    /** Whether the agent holds the site administrator role; a role that cannot be read throws, never "no". */
    public static boolean isSiteAdmin(UserManager users, User agent) throws WebloggerException {
        return users.checkPermission(new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN)), agent);
    }

    /** Creates the agent, enabled, with the screen name and full name {@code name}, and records it. */
    public static User create(Weblogger weblogger, User actor, String userName, String name, String email,
            String notes, boolean siteAdmin) throws WebloggerException {
        User agent = new User();
        agent.setUserName(userName);
        agent.setScreenName(name);
        agent.setFullName(name);
        agent.setEmailAddress(email);
        agent.setNotes(notes);
        agent.setLocale(Locale.getDefault().toString());
        agent.setTimeZone(TimeZone.getDefault().getID());
        agent.setDateCreated(new Date());
        weblogger.getUserManager().addAgent(agent, siteAdmin);
        weblogger.flush();
        AdminLog.record("agent.created", actor, "agent=" + agent.getUserName() + " siteAdmin=" + siteAdmin);
        if (siteAdmin) {
            // The same line the person editor and the agent page write, so one grep finds every grant.
            AdminLog.record("siteAdmin.granted", actor, "account=" + agent.getUserName());
        }
        return agent;
    }

    /** Saves the agent's name and notes and makes its admin role match {@code siteAdmin}, recording a change. */
    public static void update(Weblogger weblogger, User actor, User agent, String name, String notes,
            boolean siteAdmin) throws WebloggerException {
        UserManager users = weblogger.getUserManager();
        agent.setScreenName(name);
        agent.setFullName(name);
        agent.setNotes(notes);
        users.saveUser(agent);
        boolean wasSiteAdmin = users.hasRole("admin", agent);
        String event = AccountRoles.sync(users, agent, "admin", siteAdmin, wasSiteAdmin);
        weblogger.flush();
        if (event != null) {
            AdminLog.record(event, actor, "account=" + agent.getUserName());
        }
    }

    /**
     * Enables or disables the agent (disabling ends every token on its next request), recording a change.
     *
     * @return whether the state changed
     */
    public static boolean changeEnabled(Weblogger weblogger, User actor, User agent, boolean enable)
            throws WebloggerException {
        boolean wasEnabled = !Boolean.FALSE.equals(agent.getEnabled());
        agent.setEnabled(enable);
        weblogger.getUserManager().saveUser(agent);
        weblogger.flush();
        if (enable != wasEnabled) {
            AdminLog.record(enable ? "account.enabled" : "account.disabled", actor,
                    "account=" + agent.getUserName());
        }
        return enable != wasEnabled;
    }
}
