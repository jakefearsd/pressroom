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
package com.wikantik.pressroom.ui.controllers.core;

import jakarta.mail.MessagingException;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.util.MailUtil;

/**
 * Mails a {@code UserToken}-bearing "set/reset your password" link.
 *
 * <p>Shared by {@link PasswordResetController}'s forgot-password flow and
 * {@code UserEditController}'s admin "send set-password link" action (Stage 2
 * Wave B, Task 12): both hand a freshly issued {@code UserToken} raw value to
 * the exact same URL shape (see {@code PasswordResetController#resetForm}),
 * so extracting that URL construction plus the plain-text send into one place
 * means the two flows cannot drift apart. Deliberately {@code public} rather
 * than package-private: {@code UserEditController} lives in
 * {@code ui.controllers.admin}, a different package from this class's home in
 * {@code ui.controllers.core}, and calls it too.
 */
public final class PasswordLinkMailer {

    private PasswordLinkMailer() {
        // static use only
    }

    /**
     * Whether this server can actually deliver a token link: a transport is
     * configured AND there is a site email address to send from. Checking
     * only {@code MailUtil.isMailConfigured()} would leave a server with mail
     * set up but a blank {@code site.adminemail} looking ready while every
     * send silently went nowhere -- both callers need this same two-part
     * check, not just the transport half.
     */
    public static boolean isReady() {
        return MailUtil.isMailConfigured()
                && StringUtils.isNotBlank(WebloggerRuntimeConfig.getProperty("site.adminemail"));
    }

    /**
     * Sends the token's URL to the user's email address as a plain-text
     * message.
     *
     * @param user     the recipient; the link is sent to {@code user.getEmailAddress()}
     * @param rawToken the raw (undigested) token value, as returned by
     *                 {@code UserTokenManager.issueToken} -- never persisted,
     *                 so this is the only place it may still be handled
     * @param subject  the already-locale-resolved subject line; this class
     *                 has no {@code MessageSource} of its own, so callers
     *                 resolve it first
     * @throws MessagingException whatever {@code MailUtil.sendTextMessage}
     *                            throws; callers decide whether to swallow it
     *                            (the forgot-password flow, off-thread and
     *                            enumeration-proof) or surface it (the admin
     *                            action, in-request and already authenticated)
     */
    public static void sendLink(User user, String rawToken, String subject) throws MessagingException {
        String from = WebloggerRuntimeConfig.getProperty("site.adminemail");
        String url = WebloggerRuntimeConfig.getAbsoluteContextURL()
                + "/roller-ui/resetPassword.rol?token=" + rawToken;
        String body = "Use the link below to set your password. It is valid for one hour "
                + "and can only be used once.\n\n"
                + url + "\n\n"
                + "If you did not expect this email, you can safely ignore it.";
        MailUtil.sendTextMessage(from, new String[] { user.getEmailAddress() }, null, null, subject, body);
    }
}
