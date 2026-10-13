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
package org.apache.roller.weblogger.util;

import java.util.Arrays;
import java.util.List;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link MailUtil} does when mail cannot be delivered or a reviewer
 * cannot be vetted: delivery failures reach the caller, the transport is
 * always closed, and a pending-entry notice only goes to people the tier
 * confirms may post (a check that cannot run denies, it does not allow).
 * The happy paths are in {@link MailUtilTest}.
 */
class MailUtilFailureTest {

    @AfterEach
    void uninstall() {
        MockMailProvider.uninstall();
    }

    @Test
    void aDeliveryFailureReachesTheCallerAndTheTransportIsStillClosed() throws Exception {
        MockMailProvider.installFailing(new MessagingException("smtp down"));

        assertThrows(MessagingException.class, () -> MailUtil.sendTextMessage("from@example.invalid",
                new String[] {"to@example.invalid"}, null, null, "subject", "body"));

        verify(WebloggerStartup.getMailProvider().getTransport()).close();
    }

    @Test
    void aMessageThatCannotGoToSomeRecipientsEndsInASendFailedExceptionNamingThem() throws Exception {
        SendFailedException partial = new SendFailedException("mailbox full", null,
                new Address[] {new InternetAddress("sent@example.invalid")},
                new Address[] {new InternetAddress("retry@example.invalid")},
                new Address[] {new InternetAddress("bad@example.invalid")});
        MockMailProvider.installFailing(partial);

        SendFailedException thrown = assertThrows(SendFailedException.class,
                () -> MailUtil.sendTextMessage("from@example.invalid",
                        new String[] {"retry@example.invalid", "bad@example.invalid"}, null, null, "s", "b"));

        assertEquals("Unable to send message to some recipients", thrown.getMessage());
        assertSame(partial, thrown.getNextException(), "the transport's own failure is chained");
    }

    @Test
    void aMessageWithNoSubjectIsSentWithAPlaceholder() throws Exception {
        MockMailProvider mail = MockMailProvider.install();

        MailUtil.sendTextMessage("from@example.invalid", new String[] {"to@example.invalid"}, null, null,
                null, "body");

        assertEquals("(no subject)", mail.onlyMessage().getSubject());
    }

    @Test
    void aPendingEntryNoticeCannotBeBuiltWithoutAMailSession() {
        // no provider installed
        WeblogEntry entry = new WeblogEntry();
        WebloggerException e = assertThrows(WebloggerException.class,
                () -> MailUtil.sendPendingEntryNotice(MockWeblogger.create().weblogger(), entry));
        assertEquals("Couldn't get mail Session", e.getMessage());
    }

    @Test
    void onlyConfirmedAuthorsWithAnAddressAreToldAboutAPendingEntry() throws Exception {
        MockMailProvider mail = MockMailProvider.install();
        MockWeblogger tier = MockWeblogger.create();
        Weblog weblog = new Weblog();
        weblog.setHandle("noticeblog");
        weblog.setName("Notice Blog");
        weblog.setLocale("en_US");
        User author = user("author", "author@example.invalid");
        User allowed = user("allowed", "allowed@example.invalid");
        User denied = user("denied", "denied@example.invalid");
        User unvettable = user("unvettable", "unvettable@example.invalid");
        User noAddress = user("noaddress", null);
        WeblogManager weblogs = tier.weblogManager();
        when(weblogs.getWeblogUsers(weblog, true)).thenReturn(List.of(allowed, denied, unvettable, noAddress));
        when(tier.userManager().getUserByUserName("author")).thenReturn(author);
        when(tier.userManager().checkPermission(any(), eq(allowed))).thenReturn(true);
        when(tier.userManager().checkPermission(any(), eq(denied))).thenReturn(false);
        when(tier.userManager().checkPermission(any(), eq(unvettable)))
                .thenThrow(new WebloggerException("permission store down"));
        when(tier.userManager().checkPermission(any(), eq(noAddress))).thenReturn(true);
        when(tier.urlStrategy().getEntryEditURL("noticeblog", "e-1", true)).thenReturn("https://x.invalid/edit");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e-1");
        entry.setWebsite(weblog);
        entry.setCreatorUserName("author");
        Weblogger weblogger = tier.weblogger();
        when(weblogger.getUrlStrategy()).thenReturn(tier.urlStrategy());

        MailUtil.sendPendingEntryNotice(weblogger, entry);

        List<String> to = Arrays.stream(mail.onlyMessage().getRecipients(Message.RecipientType.TO))
                .map(Object::toString).toList();
        assertEquals(List.of("allowed@example.invalid"), to,
                "denied, unvettable and address-less reviewers must not be mailed");
        assertTrue(mail.onlyMessage().getContent().toString().contains("https://x.invalid/edit"));
    }

    private static User user(String name, String email) {
        User u = new User();
        u.setUserName(name);
        u.setEmailAddress(email);
        return u;
    }
}
