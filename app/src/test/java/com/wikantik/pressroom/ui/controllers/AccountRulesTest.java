/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AccountRules} with no screen and no API behind it (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 2, criterion 2): each
 * rule's answer in both surfaces' terms -- the field, the screen's key, the
 * API's code and its arguments, 400 or 409.
 */
class AccountRulesTest {

    private static final String ALLOWED = "username.allowedChars";
    /** A 258-character address the validator accepts but for its length. */
    private static final String LONG_ADDRESS = "abcdefghij@" + ("x".repeat(60) + ".").repeat(4) + "com";

    private UserManager users;
    private String previousAllowed;

    @BeforeEach
    void setUp() {
        users = mock(UserManager.class);
        previousAllowed = config().getProperty(ALLOWED);
        config().setProperty(ALLOWED, "A-Za-z0-9");
    }

    @AfterEach
    void tearDown() {
        if (previousAllowed == null) {
            config().remove(ALLOWED);
        } else {
            config().setProperty(ALLOWED, previousAllowed);
        }
    }

    // --- username ---

    @Test
    void aMissingUserNameIsRequired() {
        RuleViolation expected = RuleViolation.invalid("userName", "error.add.user.missingUserName", List.of(),
                ProblemCode.FIELD_REQUIRED, "userName");

        assertEquals(expected, AccountRules.userNameProblem(null));
        assertEquals(expected, AccountRules.userNameProblem(""));
    }

    @Test
    void aUserNameOverTheColumnIsTooLongBeforeItsCharactersAreJudged() {
        RuleViolation expected = RuleViolation.invalid("userName", "personForm.error.userNameTooLong", List.of(),
                ProblemCode.FIELD_TOO_LONG, "userName", "255");

        assertEquals(expected, AccountRules.userNameProblem("j".repeat(256)));
        assertEquals(expected, AccountRules.userNameProblem("j ".repeat(128)));
        assertNull(AccountRules.userNameProblem("j".repeat(255)));
    }

    @Test
    void aUserNameOutsideTheAllowedCharactersIsRefused() {
        RuleViolation expected = RuleViolation.invalid("userName", "error.add.user.badUserName", List.of(),
                ProblemCode.USER_NAME_CHARACTERS);

        assertEquals(expected, AccountRules.userNameProblem("jake fear"));
        assertEquals(expected, AccountRules.userNameProblem("   "), "untrimmed spaces are characters");
        assertNull(AccountRules.userNameProblem("Jake2"));
    }

    @Test
    void theAllowedCharactersAreTheDeploymentsOrLettersAndDigits() {
        config().setProperty(ALLOWED, "A-Za-z0-9_");
        assertNull(AccountRules.userNameProblem("jake_fear"));

        config().setProperty(ALLOWED, " ");
        assertEquals("error.add.user.badUserName", AccountRules.userNameProblem("jake_fear").messageKey());

        config().remove(ALLOWED);
        assertEquals("error.add.user.badUserName", AccountRules.userNameProblem("jake_fear").messageKey());
        assertNull(AccountRules.userNameProblem("jake"));
    }

    @Test
    void aUserNameTakenAsWrittenOrInLowerCaseIsAConflict() throws Exception {
        when(users.getUserByUserName("jake", null)).thenReturn(new User());

        RuleViolation expected = RuleViolation.conflict("userName", "error.add.user.userNameInUse", List.of(),
                ProblemCode.USER_NAME_IN_USE, "Jake");
        assertEquals(expected, AccountRules.userNameTaken("Jake", users));
        assertEquals(RuleViolation.Kind.CONFLICT, AccountRules.userNameTaken("jake", users).kind());
        assertNull(AccountRules.userNameTaken("bob", users));
    }

    @Test
    void aTakenCheckThatCannotRunThrows() throws Exception {
        when(users.getUserByUserName("jake", null)).thenThrow(new WebloggerException("down"));

        assertThrows(WebloggerException.class, () -> AccountRules.userNameTaken("jake", users));
    }

    @Test
    void aNewUserNameIsItsShapeThenWhetherItIsTaken() throws Exception {
        when(users.getUserByUserName("jake", null)).thenReturn(new User());

        assertEquals("error.add.user.badUserName", AccountRules.newUserNameProblem("ja ke", users).messageKey());
        assertEquals("error.add.user.userNameInUse", AccountRules.newUserNameProblem("JAKE", users).messageKey());
        assertNull(AccountRules.newUserNameProblem("bob", users));
    }

    @Test
    void aNewUserNameWhoseTakenCheckCannotRunThrows() throws Exception {
        when(users.getUserByUserName("jake", null)).thenThrow(new WebloggerException("down"));

        assertThrows(WebloggerException.class, () -> AccountRules.newUserNameProblem("jake", users));
    }

    // --- email ---

    @Test
    void aRequiredAddressThatIsMissingOrNotAnAddressIsRequired() {
        RuleViolation expected = RuleViolation.invalid("emailAddress", "personForm.error.email", List.of(),
                ProblemCode.USER_EMAIL_REQUIRED);

        for (String address : new String[] {null, "", "  ", "nope", "a@b.c", "new@example.test", "a b@example.com"}) {
            assertEquals(expected, AccountRules.emailProblem(address, AccountRules.EmailUse.REQUIRED), address);
        }
    }

    @Test
    void anAddressIsJudgedTrimmed() {
        assertNull(AccountRules.emailProblem("  jake@example.com \t", AccountRules.EmailUse.REQUIRED));
        assertNull(AccountRules.emailProblem("\"a b\"@example.com", AccountRules.EmailUse.REPLACEMENT));
    }

    @Test
    void aReplacementAddressMustBeOneAndIsInvalidOtherwise() {
        RuleViolation expected = RuleViolation.invalid("emailAddress", "personForm.error.email", List.of(),
                ProblemCode.USER_EMAIL_INVALID);

        assertEquals(expected, AccountRules.emailProblem("", AccountRules.EmailUse.REPLACEMENT));
        assertEquals(expected, AccountRules.emailProblem("a@b.c", AccountRules.EmailUse.REPLACEMENT));
    }

    @Test
    void anOptionalAddressMayBeAbsentButNotWrong() {
        assertNull(AccountRules.emailProblem(null, AccountRules.EmailUse.OPTIONAL));
        assertNull(AccountRules.emailProblem("  ", AccountRules.EmailUse.OPTIONAL));
        assertEquals(ProblemCode.USER_EMAIL_INVALID,
                AccountRules.emailProblem(" a@b.c ", AccountRules.EmailUse.OPTIONAL).code());
    }

    @Test
    void aValidAddressOverTheColumnIsTooLong() {
        RuleViolation expected = RuleViolation.invalid("emailAddress", "personForm.error.emailTooLong", List.of(),
                ProblemCode.FIELD_TOO_LONG, "emailAddress", "255");

        for (AccountRules.EmailUse use : AccountRules.EmailUse.values()) {
            assertEquals(expected, AccountRules.emailProblem(LONG_ADDRESS, use), use.name());
        }
    }

    // --- screen name ---

    @Test
    void aBlankScreenNameIsRefused() {
        RuleViolation expected = RuleViolation.invalid("screenName", "personForm.error.screenName", List.of(),
                ProblemCode.FIELD_BLANK, "screenName");

        assertEquals(expected, AccountRules.screenNameProblem(null));
        assertEquals(expected, AccountRules.screenNameProblem(" \t"));
    }

    @Test
    void aScreenNameOverTheColumnIsTooLong() {
        assertEquals(RuleViolation.invalid("screenName", "personForm.error.screenNameTooLong", List.of(),
                ProblemCode.FIELD_TOO_LONG, "screenName", "255"), AccountRules.screenNameProblem("s".repeat(256)));
        assertNull(AccountRules.screenNameProblem("s".repeat(255)));
    }

    // --- full name, locale, time zone ---

    @Test
    void aFullNameMustBeNonBlankAndFitTheColumn() {
        assertEquals(RuleViolation.invalid("fullName", "personForm.error.fullName", List.of(),
                ProblemCode.FIELD_BLANK, "fullName"), AccountRules.fullNameProblem(" "));
        assertEquals(RuleViolation.invalid("fullName", "personForm.error.fullName", List.of(),
                ProblemCode.FIELD_BLANK, "fullName"), AccountRules.fullNameProblem(null));
        assertEquals(RuleViolation.invalid("fullName", "personForm.error.fullNameTooLong", List.of(),
                ProblemCode.FIELD_TOO_LONG, "fullName", "255"), AccountRules.fullNameProblem("n".repeat(256)));
        assertNull(AccountRules.fullNameProblem("n".repeat(255)));
    }

    @Test
    void aLocaleMustBeOneTheSettingsFormOffers() {
        assertEquals(RuleViolation.invalid("locale", "websiteSettings.locale.invalid", List.of(),
                ProblemCode.LOCALE_INVALID), AccountRules.localeProblem("xx_YY"));
        assertNull(AccountRules.localeProblem("en_US"));
    }

    @Test
    void aTimeZoneMustBeOneTheSettingsFormOffers() {
        assertEquals(RuleViolation.invalid("timeZone", "websiteSettings.timeZone.invalid", List.of(),
                ProblemCode.TIME_ZONE_INVALID), AccountRules.timeZoneProblem("Mars/Olympus"));
        assertNull(AccountRules.timeZoneProblem("Europe/Berlin"));
    }

    // --- password ---

    @Test
    void aRequiredPasswordMustBeTypedAndAnyTypedOneLongEnough() {
        assertEquals("error.add.user.missingPassword", AccountRules.passwordProblem(null, true));
        assertEquals("error.add.user.missingPassword", AccountRules.passwordProblem("", true));
        assertNull(AccountRules.passwordProblem("", false), "an edit with no password leaves it alone");
        assertEquals("personForm.error.passwordTooShort", AccountRules.passwordProblem("1234567", true));
        assertEquals("personForm.error.passwordTooShort", AccountRules.passwordProblem("1234567", false));
        assertNull(AccountRules.passwordProblem("12345678", true));
    }

    private static Properties config() {
        try {
            Field field = WebloggerConfig.class.getDeclaredField("config");
            field.setAccessible(true);
            return (Properties) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
