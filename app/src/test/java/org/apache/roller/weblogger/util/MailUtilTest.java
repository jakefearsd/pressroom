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

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.apache.roller.weblogger.business.UserManager;

/**
 * What Roller actually puts in the mail it sends.
 *
 * <p>Mail is optional to configure and so was easy to leave uncovered, but when
 * it is configured it carries the things a user cannot get any other way: the
 * link in a password reset, what a comment notification is about. A mistake
 * here is invisible to every other test in the suite and shows up as an email
 * nobody can act on.
 *
 * <p>The provider is installed through {@link MockMailProvider}, which records
 * messages instead of sending them. The session is a real JavaMail one so the
 * headers asserted below are genuinely the headers that would go out.
 */
class MailUtilTest {

    private MockMailProvider mail;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        mail = MockMailProvider.install();

        weblog = new Weblog();
        weblog.setHandle("mailblog");
        weblog.setName("Mail Blog");
        weblog.setEmailAddress("owner@example.invalid");
        weblog.setLocale("en_US");
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
    }

    // ------------------------------------------------------ pending entry notice

    /**
     * The notice reaches the weblog's reviewers, the author and the edit link
     * through the {@code Weblogger} it is handed -- there is no other tier it
     * could consult (the static locator is gone), so the recipient list and
     * the link prove the explicit tier was used for every lookup.
     */
    @Test
    void pendingEntryNoticeGoesToTheReviewersOfTheTierItIsGiven() throws Exception {
        {
            User author = new User();
            author.setUserName("author");
            author.setEmailAddress("author@example.invalid");
            User reviewer = new User();
            reviewer.setUserName("reviewer");
            reviewer.setEmailAddress("reviewer@example.invalid");

            WeblogManager explicitWeblogs = mock(WeblogManager.class);
            when(explicitWeblogs.getWeblogUsers(weblog, true)).thenReturn(List.of(reviewer));
            URLStrategy urls = mock(URLStrategy.class);
            when(urls.getEntryEditURL("mailblog", "entry-1", true))
                    .thenReturn("https://site.invalid/edit/entry-1");
            UserManager explicitUsers = mock(UserManager.class);
            when(explicitUsers.getUserByUserName("author")).thenReturn(author);
            when(explicitUsers.checkPermission(any(), eq(reviewer))).thenReturn(true);
            Weblogger explicit = mock(Weblogger.class);
            when(explicit.getWeblogManager()).thenReturn(explicitWeblogs);
            when(explicit.getUserManager()).thenReturn(explicitUsers);
            when(explicit.getUrlStrategy()).thenReturn(urls);

            WeblogEntry entry = new WeblogEntry();
            entry.setId("entry-1");
            entry.setWebsite(weblog);
            entry.setCreatorUserName("author");
            entry.setTitle("Pending");

            MailUtil.sendPendingEntryNotice(explicit, entry);

            MimeMessage message = mail.onlyMessage();
            assertEquals(List.of("reviewer@example.invalid"),
                    addresses(message, Message.RecipientType.TO),
                    "the reviewers must come from the tier the notice was given");
            assertEquals("author@example.invalid", message.getFrom()[0].toString());
            assertTrue(message.getContent().toString().contains("https://site.invalid/edit/entry-1"),
                    "the edit link must come from the tier's url strategy");
        }
    }

    /**
     * An agent member who qualifies as a reviewer is left out of the notice, so
     * one refused {@code @agents.invalid} recipient cannot fail the whole send;
     * an agent as the entry's creator is not copied either.
     */
    @Test
    void pendingEntryNoticeNeverAddressesAnAgent() throws Exception {
        User author = new User();
        author.setUserName("bot");
        author.setEmailAddress("bot@agents.invalid");
        author.setKind(User.Kind.AGENT);
        User person = new User();
        person.setUserName("reviewer");
        person.setEmailAddress("reviewer@example.invalid");
        User agent = new User();
        agent.setUserName("bot2");
        agent.setEmailAddress("bot2@agents.invalid");
        agent.setKind(User.Kind.AGENT);

        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogs.getWeblogUsers(weblog, true)).thenReturn(List.of(agent, person));
        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getEntryEditURL("mailblog", "entry-1", true)).thenReturn("https://site.invalid/e");
        UserManager users = mock(UserManager.class);
        when(users.getUserByUserName("bot")).thenReturn(author);
        when(users.checkPermission(any(), any(User.class))).thenReturn(true);
        Weblogger tier = mock(Weblogger.class);
        when(tier.getWeblogManager()).thenReturn(weblogs);
        when(tier.getUserManager()).thenReturn(users);
        when(tier.getUrlStrategy()).thenReturn(urls);

        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setCreatorUserName("bot");
        entry.setTitle("Pending");

        MailUtil.sendPendingEntryNotice(tier, entry);

        MimeMessage message = mail.onlyMessage();
        assertEquals(List.of("reviewer@example.invalid"), addresses(message, Message.RecipientType.TO));
        assertTrue(addresses(message, Message.RecipientType.CC).isEmpty(), "the agent creator is not copied");
        assertTrue(message.getFrom() == null || Arrays.stream(message.getFrom())
                .noneMatch(a -> a.toString().contains("agents.invalid")),
                "an agent's address is never the sender");
        assertTrue(message.getContent().toString().contains("bot"), "the body still names the agent");
    }

    /** With only agents to tell, nobody is addressed: nothing is sent and nothing throws. */
    @Test
    void pendingEntryNoticeWithNobodyToTellSendsNothing() throws Exception {
        User author = new User();
        author.setUserName("bot");
        author.setEmailAddress("bot@agents.invalid");
        author.setKind(User.Kind.AGENT);
        User agent = new User();
        agent.setUserName("bot2");
        agent.setEmailAddress("bot2@agents.invalid");
        agent.setKind(User.Kind.AGENT);

        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogs.getWeblogUsers(weblog, true)).thenReturn(List.of(agent));
        UserManager users = mock(UserManager.class);
        when(users.getUserByUserName("bot")).thenReturn(author);
        when(users.checkPermission(any(), any(User.class))).thenReturn(true);
        Weblogger tier = mock(Weblogger.class);
        when(tier.getWeblogManager()).thenReturn(weblogs);
        when(tier.getUserManager()).thenReturn(users);
        when(tier.getUrlStrategy()).thenReturn(mock(URLStrategy.class));

        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setCreatorUserName("bot");
        entry.setTitle("Pending");

        MailUtil.sendPendingEntryNotice(tier, entry);

        assertTrue(mail.sent().isEmpty(), "no message when there is nobody to tell");
    }

    // ------------------------------------------------------- configured or not

    @Test
    void mailIsReportedConfiguredOnlyWhenAProviderIsInstalled() {
        assertTrue(MailUtil.isMailConfigured(),
                "a provider is installed for this test");

        MockMailProvider.uninstall();
        assertFalse(MailUtil.isMailConfigured(),
                "callers branch on this before composing anything; with no provider "
                        + "it must say so rather than fail later");
        mail = MockMailProvider.install();
    }

    /**
     * Sending with no provider is a no-op, not a crash. Every caller checks
     * {@code isMailConfigured} first, but the low-level send is public and an
     * installation without mail must not break because something forgot.
     */
    @Test
    void sendingWithNoProviderDoesNothingQuietly() throws Exception {
        MockMailProvider.uninstall();
        try {
            MailUtil.sendTextMessage("from@example.invalid",
                    new String[]{"to@example.invalid"}, null, null, "subject", "body");
        } finally {
            mail = MockMailProvider.install();
        }
    }

    // ------------------------------------------------------------ addressing

    @Test
    void aMessageCarriesItsFromToCcAndSubject() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"first@example.invalid", "second@example.invalid"},
                new String[]{"copied@example.invalid"},
                new String[]{"blind@example.invalid"},
                "A subject line", "The body.");

        MimeMessage message = mail.onlyMessage();
        assertEquals("A subject line", message.getSubject());
        assertEquals("sender@example.invalid", message.getFrom()[0].toString());
        assertEquals(List.of("first@example.invalid", "second@example.invalid"),
                addresses(message, Message.RecipientType.TO));
        assertEquals(List.of("copied@example.invalid"),
                addresses(message, Message.RecipientType.CC));
    }

    /**
     * A blind copy must not appear in the headers the other recipients receive.
     * That is the whole point of it, and it is one {@code setRecipients} call
     * away from being wrong.
     */
    @Test
    void aBlindCopyIsNotVisibleInTheToOrCcHeaders() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"visible@example.invalid"}, null,
                new String[]{"hidden@example.invalid"},
                "subject", "body");

        MimeMessage message = mail.onlyMessage();
        assertFalse(addresses(message, Message.RecipientType.TO).contains("hidden@example.invalid"),
                "a bcc must not be in To");
        assertFalse(addresses(message, Message.RecipientType.CC).contains("hidden@example.invalid"),
                "nor in Cc");
    }

    /**
     * The transport is still asked to deliver to the blind recipient -- hidden
     * from the headers, not dropped.
     */
    @Test
    void aBlindCopyIsStillDelivered() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"visible@example.invalid"}, null,
                new String[]{"hidden@example.invalid"},
                "subject", "body");

        List<String> delivered = Arrays.stream(mail.recipients().get(0))
                .map(Object::toString).toList();
        assertTrue(delivered.contains("hidden@example.invalid"),
                "the bcc must still be delivered to: " + delivered);
    }

    // -------------------------------------------------------------- content

    @Test
    void aTextMessageIsSentAsPlainTextAndAnHtmlOneAsHtml() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "plain", "just words");
        assertTrue(mail.onlyMessage().getContentType().startsWith("text/plain"),
                "got: " + mail.onlyMessage().getContentType());

        MockMailProvider.uninstall();
        mail = MockMailProvider.install();

        MailUtil.sendHTMLMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "rich", "<p>markup</p>");
        assertTrue(mail.onlyMessage().getContentType().startsWith("text/html"),
                "got: " + mail.onlyMessage().getContentType());
    }

    @Test
    void theBodyReachesTheMessageIntact() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null,
                "subject", "Line one.\nLine two.");

        assertEquals("Line one.\nLine two.", mail.onlyMessage().getContent().toString());
    }

    // ----------------------------------------------------------- reply-to

    /**
     * A contact-form notification is sent as the weblog, but a reply must
     * reach the submitter, not the weblog's own from address. This is the
     * one path in the whole class that reaches the private widened
     * {@code sendMessage} with a non-null {@code replyTo}.
     */
    @Test
    void aReplyToOverloadSetsTheHeaderWhenProvided() throws Exception {
        MailUtil.sendTextMessage("owner@example.invalid", "submitter@example.invalid",
                new String[]{"owner@example.invalid"}, "subject", "body");

        MimeMessage message = mail.onlyMessage();
        assertEquals(List.of("submitter@example.invalid"), replyToAddresses(message));
    }

    /**
     * Every pre-existing overload keeps delegating with a null replyTo, so
     * none of them must start emitting a Reply-To header just because the
     * private method underneath grew a parameter.
     */
    @Test
    void existingOverloadsSetNoReplyToHeader() throws Exception {
        MailUtil.sendTextMessage("owner@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "subject", "body");

        assertEquals(List.of(), replyToAddresses(mail.onlyMessage()));
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> addresses(MimeMessage message, Message.RecipientType type)
            throws Exception {
        if (message.getRecipients(type) == null) {
            return List.of();
        }
        return Arrays.stream(message.getRecipients(type)).map(Object::toString).toList();
    }

    private static List<String> replyToAddresses(MimeMessage message) throws Exception {
        if (message.getHeader("Reply-To") == null) {
            return List.of();
        }
        return Arrays.stream(message.getReplyTo()).map(Object::toString).toList();
    }
}
