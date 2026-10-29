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
package com.wikantik.pressroom.it;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Selenide.$;

/**
 * The forgot-password / reset-password flows, in the IT environment's actual
 * configuration: no mail transport and no {@code site.adminemail} (see
 * {@code pressroom-it.properties} / the default runtime property), so
 * {@code PasswordLinkMailer.isReady()} is false and both the form and a
 * submission say so plainly rather than pretending to send anything.
 *
 * <p>The token-issue-and-mail path itself (a mail-configured server) is
 * unit-tested in Task 11's {@code PasswordResetControllerTest} -- exercising
 * it here would need an SMTP fixture this browser suite does not have, and
 * asserting the honest degraded message IS the production-relevant behaviour
 * for a server in this state.
 *
 * <p>No weblog is needed: both flows are account-level, not per-weblog, so
 * this is the rare IT that touches neither the seeded weblog nor one of its
 * own.
 */
class ForgotPasswordIT extends PressroomIT {

    private static final String LOGIN_PATH = "/pressroom-ui/login.rol";
    private static final String MAIL_NOT_CONFIGURED = "This server has no outgoing mail configured "
            + "(or no site email address set), so it cannot send reset links. Contact the administrator.";
    private static final String RESET_INVALID = "That link is invalid or has expired. Request a new one.";

    @Test
    void forgotAndResetDegradeHonestlyWithoutMailConfigured() {
        // --- the link from the login page -------------------------------------
        openPath(LOGIN_PATH);
        $("a[href$='/pressroom-ui/forgotPassword.rol']").should(exist).click();
        $("#forgotPasswordForm").should(exist);
        BrowserHealth.current().settle();
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- submitting says so plainly: no mail configured ---------------------
        $("#identifier").setValue("nobody-" + nonce() + "@example.invalid");
        $("#send").click();
        $("#messages").shouldHave(text(MAIL_NOT_CONFIGURED));
        BrowserHealth.current().settle();
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- an invalid/garbage reset token renders the invalid-link page, not a 500
        openPath("/pressroom-ui/resetPassword.rol?token=garbage");
        $("#resetPasswordInvalid").should(exist).shouldHave(text(RESET_INVALID));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
