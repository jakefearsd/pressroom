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
 * The token form's values as submitted (spec 2026-10-13, section 3), kept so
 * a refused form comes back as it was sent.
 */
public final class TokenFormValues {

    /** Write: a token for publishing. */
    public static final String DEFAULT_ROLE = "POST";

    /** 90 days, the spec's default. */
    public static final String DEFAULT_EXPIRY = "90";

    /** The expiry choice for a token that never expires on its own. */
    public static final String NEVER = "never";

    private final String label;
    private final String role;
    private final String pin;
    private final String expiry;

    /** A fresh form: Write, all its weblogs, 90 days. */
    public TokenFormValues() {
        this(null, DEFAULT_ROLE, null, DEFAULT_EXPIRY);
    }

    public TokenFormValues(String label, String role, String pin, String expiry) {
        this.label = label;
        this.role = role;
        this.pin = pin;
        this.expiry = expiry;
    }

    public String getLabel() {
        return label;
    }

    /** {@code READ}, {@code POST} or {@code ADMIN}, as submitted. */
    public String getRole() {
        return role;
    }

    /** A weblog handle, or blank for all its weblogs. */
    public String getPin() {
        return pin;
    }

    /** {@code 30}, {@code 90}, {@code 365} or {@code never}, as submitted. */
    public String getExpiry() {
        return expiry;
    }
}
