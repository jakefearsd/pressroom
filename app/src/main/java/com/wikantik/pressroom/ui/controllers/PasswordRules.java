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

    /** Whether {@code password} meets the floor; no password does not. */
    public static boolean isLongEnough(String password) {
        return password != null && password.length() >= MIN_LENGTH;
    }
}
