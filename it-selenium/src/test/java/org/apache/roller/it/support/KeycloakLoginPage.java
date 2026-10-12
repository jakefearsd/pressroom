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

package org.apache.roller.it.support;

import java.time.Duration;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Keycloak's sign-in form, as the IT run's provider (realm {@code roller-it}) serves it.
 * Modelled on Wikantik's page object of the same name.
 */
public final class KeycloakLoginPage {

    /**
     * Keycloak in dev mode renders its first page slowly (the theme is not cached), so
     * the form gets longer than Selenide's default to appear.
     */
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Fills the form and submits it, returning once the provider's answer has replaced
     * the page: the redirect back through Roller's callback to wherever the sign-in
     * lands, or Keycloak's own form again if it refused the password.
     *
     * <p>The wait is {@link RollerIT#clickAndAwaitNewPage}, not the click: the next thing
     * every caller does is read the URL, and a click is not a barrier (see
     * docs/dev/browser-its.md).
     */
    public void signIn(String username, String password) {
        $("#username").shouldBe(visible, FORM_TIMEOUT).setValue(username);
        $("#password").shouldBe(visible).setValue(password);
        RollerIT.clickAndAwaitNewPage($("#kc-login"));
    }
}
