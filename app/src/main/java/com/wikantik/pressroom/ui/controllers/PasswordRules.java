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

import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.core.PressroomContext;
import org.slf4j.LoggerFactory;

/**
 * The one rule for a password a person chooses, applied by every path that
 * sets one: creating a person and editing one ({@code UserEditController}),
 * the profile page ({@code ProfileController}) and the emailed reset link
 * ({@code PasswordResetController}). Each path once carried its own copy of
 * the floor, and the profile and edit paths carried none, so a one-character
 * password went through there.
 */
public final class PasswordRules {

    /** The shortest password accepted; the messages that refuse one say "8". */
    public static final int MIN_LENGTH = 8;

    private PasswordRules() {
    }

    /**
     * Whether {@code typed} is {@code account}'s current password, for a change that must prove the person
     * holds it (a signed-in session alone, a borrowed laptop or a stolen cookie, must not be enough to take an
     * account over for good). Fails closed: no account, nothing typed, no stored hash, or a hash the encoder
     * cannot read (it throws on an unknown {@code {id}}) all answer false.
     */
    public static boolean matchesCurrent(User account, String typed) {
        if (account == null || typed == null || typed.isEmpty() || account.getPassword() == null) {
            return false;
        }
        try {
            return PressroomContext.getPasswordEncoder().matches(typed, account.getPassword());
        } catch (IllegalArgumentException ex) {
            LoggerFactory.getLogger(PasswordRules.class)
                    .warn("The stored password of {} could not be checked", account.getUserName(), ex);
            return false;
        }
    }

    /** Whether {@code password} meets the floor; no password does not. */
    public static boolean isLongEnough(String password) {
        return password != null && password.length() >= MIN_LENGTH;
    }
}
