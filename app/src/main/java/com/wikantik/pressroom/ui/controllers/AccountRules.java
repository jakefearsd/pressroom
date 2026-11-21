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

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * The rules for an account's identity, in one place for every surface that
 * creates or changes one (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 2): the new-person and edit-person screens ({@code UserEditController}),
 * the new-agent screen ({@code AgentController}) and {@code POST/PATCH
 * /api/v1/admin/users} ({@code AdminApi}). Each rule takes the value as its
 * surface has already shaped it (the API trims and defaults; the screens pass
 * what was typed) and answers a {@link RuleViolation}, or null when the value
 * passes. A field the screens and the API both have is named as the API names
 * it ({@link #USER_NAME}, {@link #EMAIL}, {@link #SCREEN_NAME}).
 */
public final class AccountRules {

    private static final Logger log = LoggerFactory.getLogger(AccountRules.class);

    /** pressroom_user.username, screenname and emailaddress are varchar(255). */
    public static final int MAX_LENGTH = 255;

    public static final String USER_NAME = "userName";
    public static final String EMAIL = "emailAddress";
    public static final String SCREEN_NAME = "screenName";

    /** What an address is for, which decides whether none at all will do, and the API's code. */
    public enum EmailUse {
        /** A new person's address: there must be one ({@code user.emailRequired}). */
        REQUIRED,
        /** An address sent to replace the stored one: it must be one ({@code user.emailInvalid}). */
        REPLACEMENT,
        /** A new agent's address: none is fine, the store fills one in ({@code user.emailInvalid}). */
        OPTIONAL
    }

    private AccountRules() {
    }

    /**
     * The username's shape, for a new account: present, at most
     * {@link #MAX_LENGTH} characters, and inside {@code username.allowedChars}
     * (letters and digits when the property is blank). Judged in that order,
     * so the first broken one is the answer. Whether it is taken is
     * {@link #userNameTaken}.
     */
    public static RuleViolation userNameProblem(String userName) {
        if (StringUtils.isEmpty(userName)) {
            return RuleViolation.invalid(USER_NAME, "error.add.user.missingUserName", List.of(),
                    ProblemCode.FIELD_REQUIRED, USER_NAME);
        }
        if (userName.length() > MAX_LENGTH) {
            return tooLong(USER_NAME, "personForm.error.userNameTooLong");
        }
        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (StringUtils.isBlank(allowed)) {
            allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
        }
        if (!CharSetUtils.keep(userName, allowed).equals(userName)) {
            return RuleViolation.invalid(USER_NAME, "error.add.user.badUserName", List.of(),
                    ProblemCode.USER_NAME_CHARACTERS);
        }
        return null;
    }

    /**
     * Whether {@code userName} is taken by an account in any enabled state,
     * as written or in lower case (the store refuses a name whose lower-case
     * form exists: {@code JPAUserManagerImpl.addUser}/{@code addAgent}): a
     * conflict, or null. A lookup that fails throws, so the caller refuses;
     * the API answers it as its opaque 500.
     */
    public static RuleViolation userNameTaken(String userName, UserManager users) throws WebloggerException {
        boolean taken = users.getUserByUserName(userName, null) != null
                || users.getUserByUserName(userName.toLowerCase(Locale.ROOT), null) != null;
        return taken ? RuleViolation.conflict(USER_NAME, "error.add.user.userNameInUse", List.of(),
                ProblemCode.USER_NAME_IN_USE, userName) : null;
    }

    /**
     * A new account's username, as the create screens judge it: its shape,
     * then whether it is taken. A taken-check that cannot run refuses it
     * ({@code generic.error.check.logs}): a check that could not run is not a
     * check that passed.
     */
    public static RuleViolation newUserNameProblem(String userName, UserManager users) {
        RuleViolation shape = userNameProblem(userName);
        if (shape != null) {
            return shape;
        }
        try {
            return userNameTaken(userName, users);
        } catch (WebloggerException e) {
            log.error("Could not check whether the username {} is taken", userName, e);
            return RuleViolation.invalid(USER_NAME, "generic.error.check.logs", List.of(),
                    ProblemCode.SERVER_INTERNAL_ERROR);
        }
    }

    /**
     * An email address, judged trimmed by commons {@link EmailValidator} (which
     * knows the top-level domains), then held to {@link #MAX_LENGTH}. An
     * {@link EmailUse#OPTIONAL} address may be absent or blank.
     */
    public static RuleViolation emailProblem(String email, EmailUse use) {
        String trimmed = StringUtils.trimToEmpty(email);
        if (use == EmailUse.OPTIONAL && trimmed.isEmpty()) {
            return null;
        }
        if (!EmailValidator.getInstance().isValid(trimmed)) {
            return RuleViolation.invalid(EMAIL, "personForm.error.email", List.of(),
                    use == EmailUse.REQUIRED ? ProblemCode.USER_EMAIL_REQUIRED : ProblemCode.USER_EMAIL_INVALID);
        }
        if (trimmed.length() > MAX_LENGTH) {
            return tooLong(EMAIL, "personForm.error.emailTooLong");
        }
        return null;
    }

    /**
     * A screen name, after the surface's own defaulting (the API stands the
     * username in for one it was not sent): not blank, and at most
     * {@link #MAX_LENGTH} characters as given.
     */
    public static RuleViolation screenNameProblem(String screenName) {
        if (StringUtils.isBlank(screenName)) {
            return RuleViolation.invalid(SCREEN_NAME, "personForm.error.screenName", List.of(),
                    ProblemCode.FIELD_BLANK, SCREEN_NAME);
        }
        if (screenName.length() > MAX_LENGTH) {
            return tooLong(SCREEN_NAME, "personForm.error.screenNameTooLong");
        }
        return null;
    }

    /**
     * The message key refusing a password a person is given, or null: none
     * at all when one is {@code required} (a new person who is not emailed a
     * set-password link), and under {@link PasswordRules#MIN_LENGTH} whenever
     * one is typed. A key, not a violation: no API takes a password.
     */
    public static String passwordProblem(String password, boolean required) {
        if (StringUtils.isEmpty(password)) {
            return required ? "error.add.user.missingPassword" : null;
        }
        return PasswordRules.isLongEnough(password) ? null : "personForm.error.passwordTooShort";
    }

    private static RuleViolation tooLong(String field, String messageKey) {
        return RuleViolation.invalid(field, messageKey, List.of(), ProblemCode.FIELD_TOO_LONG, field,
                Integer.toString(MAX_LENGTH));
    }
}
