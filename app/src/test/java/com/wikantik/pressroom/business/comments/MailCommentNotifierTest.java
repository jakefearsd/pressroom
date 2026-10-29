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
package com.wikantik.pressroom.business.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import jakarta.mail.internet.MimeMessage;

import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.startup.MockMailProvider;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link MailCommentNotifier}: the email to a weblog's admins when a comment is held. */
class MailCommentNotifierTest {

    private MockWeblogger mocks;
    private MockMailProvider mail;
    private MailCommentNotifier notifier;
    private Weblog weblog;
    private ReaderComment comment;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockWeblogger.attached();
        mail = MockMailProvider.install();
        notifier = new MailCommentNotifier(mocks.weblogger());
        property("site.adminemail", "admin@example.invalid");
        property("site.absoluteurl", "https://blog.example.invalid");

        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(mocks.getThreadManager()).executeInBackground(any());

        weblog = new Weblog();
        weblog.setHandle("food");
        weblog.setName("Food & Wine");
        weblog.setLocale("en");
        weblog.setCommentNotify(Boolean.TRUE);
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Soup &amp; Bread");
        entry.setWebsite(weblog);
        User author = user("reader1", "reader@example.invalid", true);
        author.setScreenName("Rae");
        comment = new ReaderComment();
        comment.setId("c-1");
        comment.setEntry(entry);
        comment.setAuthor(author);
        comment.setBody("Lovely soup.");
        comment.setStatus(ReaderComment.Status.PENDING);
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
        mocks.detach();
    }

    @Test
    void aHeldCommentMailsEachAdminMemberOnce() throws Exception {
        admin("amy", "amy@example.invalid", true);
        admin("bob", "bob@example.invalid", true);

        notifier.notifyHeld(comment);

        assertEquals(2, mail.sent().size(), "one email per admin, so no admin sees another's address");
        List<String> to = new ArrayList<>();
        mail.recipients().forEach(r -> { for (var a : r) { to.add(a.toString()); } });
        assertTrue(to.contains("amy@example.invalid"));
        assertTrue(to.contains("bob@example.invalid"));
        assertEquals(2, to.size(), "each admin exactly once");
        MimeMessage sent = mail.sent().get(0);
        assertEquals("New comment awaiting approval on Food & Wine", sent.getSubject());
        String body = sent.getContent().toString();
        assertTrue(body.contains("Rae commented on \"Soup & Bread\":"), body);
        assertTrue(body.contains("Lovely soup."), body);
        assertTrue(body.contains(
                "https://blog.example.invalid/roller-ui/authoring/comments.rol?weblog=food"), body);
        verify(mocks.weblogger()).release();
    }

    @Test
    void withNoAbsoluteUrlTheLinkIsRelative() throws Exception {
        property("site.absoluteurl", "");
        admin("amy", "amy@example.invalid", true);

        notifier.notifyHeld(comment);

        String body = mail.onlyMessage().getContent().toString();
        assertTrue(body.contains("Review it: /roller-ui/authoring/comments.rol?weblog=food"), body);
    }

    @Test
    void theBodyIsCutToFiveHundredCharacters() throws Exception {
        admin("amy", "amy@example.invalid", true);
        comment.setBody("x".repeat(500) + "TAIL");

        notifier.notifyHeld(comment);

        String body = mail.onlyMessage().getContent().toString();
        assertTrue(body.contains("x".repeat(500)));
        assertFalse(body.contains("TAIL"));
    }

    @Test
    void notifyOffMailsNobody() throws Exception {
        admin("amy", "amy@example.invalid", true);
        weblog.setCommentNotify(Boolean.FALSE);

        notifier.notifyHeld(comment);

        assertTrue(mail.sent().isEmpty());
        verify(mocks.getThreadManager(), never()).executeInBackground(any());
    }

    @Test
    void noMailConfiguredMailsNobody() throws Exception {
        MockMailProvider.uninstall();
        admin("amy", "amy@example.invalid", true);

        notifier.notifyHeld(comment);

        verify(mocks.getThreadManager(), never()).executeInBackground(any());
        mail = MockMailProvider.install();
    }

    @Test
    void disabledAndBlankEmailAdminsAreSkipped() throws Exception {
        admin("amy", "amy@example.invalid", true);
        admin("off", "off@example.invalid", false);
        admin("blank", "  ", true);
        admin("none", null, true);

        notifier.notifyHeld(comment);

        assertEquals(1, mail.recipients().size());
        assertEquals(1, mail.recipients().get(0).length);
        assertEquals("amy@example.invalid", mail.recipients().get(0)[0].toString());
    }

    @Test
    void anAgentAdminMemberIsNeverMailed() throws Exception {
        admin("amy", "amy@example.invalid", true);
        User bot = user("bot", "bot@agents.invalid", true);
        bot.setKind(User.Kind.AGENT);
        permissions.add(new WeblogPermission(weblog, bot, List.of(WeblogPermission.ADMIN)));
        when(mocks.getUserManager().getUserByUserName("bot")).thenReturn(bot);
        when(mocks.getUserManager().getWeblogPermissions(weblog)).thenReturn(List.copyOf(permissions));

        notifier.notifyHeld(comment);

        assertEquals(1, mail.recipients().size(), "one email, to the person only");
        assertEquals(1, mail.recipients().get(0).length);
        assertEquals("amy@example.invalid", mail.recipients().get(0)[0].toString());
    }

    @Test
    void postOnlyMembersAreNotMailed() throws Exception {
        admin("amy", "amy@example.invalid", true);
        member("poster", "poster@example.invalid", WeblogPermission.POST);

        notifier.notifyHeld(comment);

        assertEquals(1, mail.recipients().get(0).length);
        assertEquals("amy@example.invalid", mail.recipients().get(0)[0].toString());
    }

    @Test
    void noAdminToMailSendsNothing() throws Exception {
        member("poster", "poster@example.invalid", WeblogPermission.POST);

        notifier.notifyHeld(comment);

        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void aMailFailureLeavesTheCommentStored() throws Exception {
        MockMailProvider.uninstall();
        mail = MockMailProvider.installFailing();
        admin("amy", "amy@example.invalid", true);

        notifier.notifyHeld(comment);

        assertEquals(ReaderComment.Status.PENDING, comment.getStatus());
        assertEquals("Lovely soup.", comment.getBody());
        verify(mocks.getThreadManager()).executeInBackground(any());
        verify(mocks.weblogger()).release();
    }

    @Test
    void theDisplayNameIsPlainTextInTheEmail() throws Exception {
        admin("amy", "amy@example.invalid", true);
        // The setter may sanitise; a stored name is whatever the column holds.
        User tagged = mock(User.class);
        when(tagged.getScreenName()).thenReturn("<b>Rae</b>");
        comment.setAuthor(tagged);

        notifier.notifyHeld(comment);

        MimeMessage sent = mail.onlyMessage();
        assertTrue(sent.getContentType().startsWith("text/plain"), sent.getContentType());
        assertFalse(sent.isMimeType("multipart/*"));
        assertTrue(sent.getContent().toString().contains("<b>Rae</b> commented on"), sent.getContent().toString());
    }

    @Test
    void theRunnableReleasesThePersistenceSession() throws Exception {
        admin("amy", "amy@example.invalid", true);
        when(mocks.getUserManager().getWeblogPermissions(weblog))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("db down"));

        notifier.notifyHeld(comment);

        assertTrue(mail.sent().isEmpty());
        verify(mocks.weblogger()).release();
    }

    @Test
    void anInterruptedSchedulingReInterrupts() throws Exception {
        admin("amy", "amy@example.invalid", true);
        doThrow(new InterruptedException("stop")).when(mocks.getThreadManager()).executeInBackground(any());
        try {
            notifier.notifyHeld(comment);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void noLogLineCarriesAnAddressOrTheBody() throws Exception {
        MockMailProvider.uninstall();
        mail = MockMailProvider.installFailing();
        admin("amy", "amy@example.invalid", true);
        comment.setBody("SECRET-BODY-TEXT");
        try (SsoLogCapture logs = SsoLogCapture.everyLogger()) {
            notifier.notifyHeld(comment);

            List<String> lines = new ArrayList<>();
            for (Level level : Level.values()) {
                lines.addAll(logs.lines(level));
            }
            assertFalse(lines.isEmpty(), "the failure is logged");
            assertTrue(lines.stream().anyMatch(l -> l.contains("c-1")));
            for (String line : lines) {
                // Recipient and writer addresses; the sender's own address is a
                // framework debug line about site.adminemail, not about a person.
                assertFalse(line.contains("amy@") || line.contains("reader@"), line);
                assertFalse(line.contains("SECRET-BODY-TEXT"), line);
            }
        }
    }

    /**
     * Real failures carry the recipient in their messages and in nested
     * exceptions; none of that may reach a log line.
     */
    @Test
    void aFailureWhoseMessagesNameTheRecipientLeaksNothingToTheLog() throws Exception {
        jakarta.mail.SendFailedException inner = new jakarta.mail.SendFailedException(
                "550 5.1.1 <amy@example.invalid>: Recipient address rejected");
        jakarta.mail.SendFailedException outer = new jakarta.mail.SendFailedException(
                "Invalid Addresses: amy@example.invalid");
        outer.setNextException(inner);
        MockMailProvider.uninstall();
        mail = MockMailProvider.installFailing(outer);
        admin("amy", "amy@example.invalid", true);
        try (SsoLogCapture logs = SsoLogCapture.everyLogger()) {
            notifier.notifyHeld(comment);

            List<String> lines = new ArrayList<>();
            for (Level level : Level.values()) {
                lines.addAll(logs.lines(level));
            }
            assertTrue(lines.stream().anyMatch(l -> l.contains("c-1")), "the failure is logged");
            for (String line : lines) {
                assertFalse(line.contains("amy@") || line.contains("Recipient address"), line);
            }
            // The captured events carry thrown exceptions too.
            assertTrue(logs.everything().stream().noneMatch(m -> m.contains("amy@")),
                    "no Throwable with the address is attached");
        }
    }

    @Test
    void theWorkerReloadsTheWeblogInItsOwnSession() throws Exception {
        Weblog reloaded = new Weblog();
        reloaded.setHandle("food");
        when(mocks.getWeblogManager().getWeblogByHandle("food")).thenReturn(reloaded);
        User u = user("amy", "amy@example.invalid", true);
        when(mocks.getUserManager().getUserByUserName("amy")).thenReturn(u);
        when(mocks.getUserManager().getWeblogPermissions(reloaded))
                .thenReturn(List.of(new WeblogPermission(reloaded, u, List.of(WeblogPermission.ADMIN))));

        notifier.notifyHeld(comment);

        assertEquals(1, mail.sent().size());
        verify(mocks.getUserManager(), never()).getWeblogPermissions(org.mockito.ArgumentMatchers.same(weblog));
    }

    @Test
    void aWeblogThatCannotBeReloadedMailsNobody() throws Exception {
        when(mocks.getWeblogManager().getWeblogByHandle("food")).thenReturn(null);
        admin("amy", "amy@example.invalid", true);
        when(mocks.getWeblogManager().getWeblogByHandle("food")).thenReturn(null);

        notifier.notifyHeld(comment);

        assertTrue(mail.sent().isEmpty());
        verify(mocks.weblogger()).release();
    }

    // ------------------------------------------------------------------ helpers

    private void property(String name, String value) throws Exception {
        when(mocks.getPropertiesManager().getProperty(name)).thenReturn(new RuntimeConfigProperty(name, value));
    }

    private static User user(String name, String email, boolean enabled) {
        User u = new User();
        u.setUserName(name);
        u.setEmailAddress(email);
        u.setEnabled(enabled);
        return u;
    }

    private final List<WeblogPermission> permissions = new ArrayList<>();

    private void admin(String name, String email, boolean enabled) throws Exception {
        member(name, email, enabled, WeblogPermission.ADMIN);
    }

    private void member(String name, String email, String action) throws Exception {
        member(name, email, true, action);
    }

    private void member(String name, String email, boolean enabled, String action) throws Exception {
        User u = user(name, email, enabled);
        permissions.add(new WeblogPermission(weblog, u, List.of(action)));
        when(mocks.getWeblogManager().getWeblogByHandle(weblog.getHandle())).thenReturn(weblog);
        when(mocks.getUserManager().getUserByUserName(name)).thenReturn(u);
        when(mocks.getUserManager().getWeblogPermissions(weblog)).thenReturn(List.copyOf(permissions));
    }
}
