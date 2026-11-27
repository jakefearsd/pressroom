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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.startup.MockMailProvider;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MailCommentNotifier#notifyReplied}: the email a reader gets when an
 * author answers their comment (spec, Part F), checked against a recording
 * mail provider with the background sender run inline.
 */
class ReplyNotificationTest {

    private static final String KEY = "reply-notification-test-key-0123456789abcdef";
    private static final String SITE = "https://blog.example.invalid/pressroom";

    private MockWeblogger mocks;
    private MockMailProvider mail;
    private UnsubscribeTokens tokens;
    private MailCommentNotifier notifier;
    private User reader;
    private User owner;
    private WeblogEntry entry;
    private ReaderComment parent;
    private ReaderComment reply;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockWeblogger.attached();
        mail = MockMailProvider.install();
        tokens = new UnsubscribeTokens(() -> KEY);
        notifier = new MailCommentNotifier(mocks.weblogger(), tokens);
        property("site.adminemail", "site@example.invalid");
        // Under a context path, as a deployment at /pressroom has it.
        property("site.absoluteurl", SITE + "/");
        when(mocks.weblogger().getUrlStrategy()).thenReturn(new MultiWeblogURLStrategy());
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(mocks.getThreadManager()).executeInBackground(any());

        Weblog weblog = new Weblog();
        weblog.setHandle("food");
        weblog.setName("Food & Wine");
        weblog.setLocale("en");
        entry = new WeblogEntry();
        entry.setTitle("Soup &amp; Bread");
        entry.setAnchor("soup-bread");
        entry.setWebsite(weblog);

        reader = person("reader-id", "rae", "reader@example.invalid", "Rae");
        owner = person("owner-id", "olu", "owner@example.invalid", "Olu");
        parent = comment("c-1", reader, "Lovely soup.", null);
        reply = comment("r-1", owner, "Thank you!", parent);
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
        mocks.detach();
    }

    // ------------------------------------------------------------- who is mailed

    @Test
    void anOwnersReplySendsExactlyOneMailToTheCommenter() throws Exception {
        notifier.notifyReplied(reply);

        MimeMessage sent = mail.onlyMessage();
        assertEquals(1, mail.recipients().get(0).length);
        assertEquals("reader@example.invalid", mail.recipients().get(0)[0].toString());
        assertEquals("site@example.invalid", sent.getFrom()[0].toString(), "sent as the site");
        assertEquals("Olu replied to your comment on Soup & Bread", sent.getSubject());
    }

    @Test
    void noMailWhenTheReaderTurnedReplyEmailsOff() throws Exception {
        reader.setNotifyReplies(false);

        notifier.notifyReplied(reply);

        assertTrue(mail.sent().isEmpty());
        verify(mocks.getThreadManager(), never()).executeInBackground(any());
    }

    @Test
    void noMailWhenTheReplyAuthorWroteTheComment() {
        ReaderComment own = comment("r-2", person("reader-id", "rae", "reader@example.invalid", "Rae"),
                "Answering myself", parent);

        notifier.notifyReplied(own);

        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void noMailWhenTheReaderHasNoEmail() throws Exception {
        for (String blank : new String[] {null, "", "   "}) {
            reader.setEmailAddress(blank);

            notifier.notifyReplied(reply);
        }

        assertTrue(mail.sent().isEmpty());
        verify(mocks.getThreadManager(), never()).executeInBackground(any());
    }

    @Test
    void noMailWhenMailIsNotConfigured() throws Exception {
        MockMailProvider.uninstall();
        notifier.notifyReplied(reply);
        verify(mocks.getThreadManager(), never()).executeInBackground(any());
        mail = MockMailProvider.install();

        property("site.adminemail", " ");
        notifier.notifyReplied(reply);

        assertTrue(mail.sent().isEmpty(), "no From address: site.adminemail is part of being configured");
        verify(mocks.getThreadManager(), never()).executeInBackground(any());
    }

    @Test
    void noMailForADisabledReaderOrATopLevelComment() {
        reader.setEnabled(Boolean.FALSE);
        notifier.notifyReplied(reply);

        reader.setEnabled(Boolean.TRUE);
        notifier.notifyReplied(parent);

        assertTrue(mail.sent().isEmpty());
    }

    // ------------------------------------------------------------- failures

    @Test
    void replySucceedsWhenMailThrows() throws Exception {
        jakarta.mail.SendFailedException refused = new jakarta.mail.SendFailedException(
                "550 <reader@example.invalid>: Recipient address rejected");
        MockMailProvider.uninstall();
        mail = MockMailProvider.installFailing(refused);
        reader.setEmailAddress("reader@example.invalid");
        try (SsoLogCapture logs = SsoLogCapture.everyLogger()) {
            notifier.notifyReplied(reply);

            List<String> lines = new ArrayList<>();
            for (Level level : Level.values()) {
                lines.addAll(logs.lines(level));
            }
            assertTrue(lines.stream().anyMatch(l -> l.contains("Could not send the reply email for reply r-1")
                    && l.contains("SendFailedException")),
                    "the failure names the step, the reply and the exception's class: " + lines);
            for (String line : lines) {
                assertFalse(line.contains("reader@") || line.contains("Recipient address")
                        || line.contains("550"), "no address and no exception message: " + line);
                assertFalse(line.contains("Lovely soup") || line.contains("Thank you!"), line);
            }
            assertTrue(logs.everything().stream().noneMatch(m -> m.contains("reader@")),
                    "no Throwable carrying the address is attached");
        }
        assertEquals(ReaderComment.Status.PUBLISHED, reply.getStatus(), "the reply stands");
    }

    @Test
    void aFailureBeforeSchedulingIsLoggedNotThrown() {
        when(mocks.weblogger().getUrlStrategy())
                .thenThrow(new IllegalStateException("cannot link reader@example.invalid"));
        try (SsoLogCapture logs = SsoLogCapture.everyLogger()) {
            notifier.notifyReplied(reply);

            List<String> errors = logs.lines(Level.ERROR);
            assertTrue(errors.stream().anyMatch(l -> l.contains("Could not prepare the reply email for reply r-1")
                    && l.contains("IllegalStateException")), "names the step, the reply and the class: " + errors);
            assertTrue(logs.everything().stream().noneMatch(m -> m.contains("reader@") || m.contains("cannot link")),
                    "neither the message text nor a Throwable carrying it is logged");
        }

        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void anInterruptedSchedulingReInterrupts() throws Exception {
        doThrow(new InterruptedException("stop")).when(mocks.getThreadManager()).executeInBackground(any());
        try {
            notifier.notifyReplied(reply);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------- the message

    @Test
    void theBodiesHoldBothCommentsAsEscapedPlainText() throws Exception {
        parent.setBody("<b>bold</b> & " + "a".repeat(300) + "TAIL");
        reply.setBody("Thanks <script>alert(1)</script>\nSecond line");

        notifier.notifyReplied(reply);

        MimeMessage sent = mail.onlyMessage();
        assertTrue(sent.isMimeType("multipart/alternative"), sent.getContentType());
        String text = part(sent, "text/plain");
        String html = part(sent, "text/html");
        String shortened = "<b>bold</b> & " + "a".repeat(300 - "<b>bold</b> & ".length()) + "…";
        assertTrue(text.contains(shortened), text);
        assertFalse(text.contains("TAIL"), "the comment is cut to 300 characters");
        assertTrue(text.contains("Thanks <script>alert(1)</script>\nSecond line"), "the reply in full: " + text);

        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt; &amp; aaa"), html);
        assertTrue(html.contains("Thanks &lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertFalse(html.contains("<b>bold") || html.contains("<script"), "never raw HTML from content: " + html);
        assertFalse(html.contains("TAIL"));
        assertTrue(html.contains("Soup &amp; Bread"), "the title is escaped once: " + html);
        assertTrue(text.contains("Olu replied to your comment on \"Soup & Bread\""), text);
    }

    @Test
    void aShortCommentIsQuotedWhole() throws Exception {
        parent.setBody("a".repeat(300));

        notifier.notifyReplied(reply);

        String text = part(mail.onlyMessage(), "text/plain");
        assertTrue(text.contains("a".repeat(300)));
        assertFalse(text.contains("…"), "exactly 300 characters needs no ellipsis");
    }

    @Test
    void mailLinksUseSiteAbsoluteUrl() throws Exception {
        notifier.notifyReplied(reply);

        MimeMessage sent = mail.onlyMessage();
        String text = part(sent, "text/plain");
        String html = part(sent, "text/html");
        String entryLink = SITE + "/food/entry/soup-bread#comment-r-1";
        assertTrue(text.contains(entryLink), text);
        assertTrue(html.contains("href=\"" + entryLink + "\""), html);

        String unsubscribe = unsubscribeLink(text);
        assertTrue(unsubscribe.startsWith(SITE + "/pressroom-ui/unsubscribe.rol?t="), unsubscribe);
        String token = unsubscribe.substring(unsubscribe.indexOf("?t=") + 3);
        assertEquals(Optional.of("reader-id"), tokens.verify(token, UnsubscribeTokens.REPLIES),
                "the link turns off this reader's reply emails");
        assertTrue(html.contains("href=\"" + unsubscribe + "\""), html);
    }

    @Test
    void theListUnsubscribeHeadersArePresent() throws Exception {
        notifier.notifyReplied(reply);

        MimeMessage sent = mail.onlyMessage();
        String unsubscribe = unsubscribeLink(part(sent, "text/plain"));
        assertEquals("<" + unsubscribe + ">", sent.getHeader("List-Unsubscribe", null));
        assertEquals("List-Unsubscribe=One-Click", sent.getHeader("List-Unsubscribe-Post", null));
    }

    /**
     * RFC 8058 one-click needs an https URL, so a site served over plain http gets
     * no List-Unsubscribe headers; the link in the body still works.
     */
    @Test
    void overPlainHttpTheListHeadersAreOmittedButTheBodyLinkStays() throws Exception {
        property("site.absoluteurl", "http://blog.example.invalid/pressroom/");

        notifier.notifyReplied(reply);

        MimeMessage sent = mail.onlyMessage();
        assertEquals(null, sent.getHeader("List-Unsubscribe"), "no List-Unsubscribe over http");
        assertEquals(null, sent.getHeader("List-Unsubscribe-Post"), "no one-click header over http");
        String unsubscribe = unsubscribeLink(part(sent, "text/plain"));
        assertTrue(unsubscribe.startsWith("http://blog.example.invalid/pressroom/pressroom-ui/unsubscribe.rol?t="),
                unsubscribe);
        assertTrue(part(sent, "text/html").contains("href=\"" + unsubscribe + "\""), "the body link stays");
    }

    /** A link a mail client cannot open is worse than no mail: nothing is sent without the site's address. */
    @Test
    void noMailWithoutAnAbsoluteSiteAddress() throws Exception {
        property("site.absoluteurl", "");
        String latched = com.wikantik.pressroom.config.WebloggerRuntimeConfig.getAbsoluteContextURL();
        com.wikantik.pressroom.config.WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        try {
            notifier.notifyReplied(reply);
        } finally {
            com.wikantik.pressroom.config.WebloggerRuntimeConfig.setAbsoluteContextURL(latched);
        }

        assertTrue(mail.sent().isEmpty());
    }

    // ------------------------------------------------------------- helpers

    private static String part(MimeMessage message, String type) throws Exception {
        Multipart parts = (Multipart) message.getContent();
        for (int i = 0; i < parts.getCount(); i++) {
            if (parts.getBodyPart(i).isMimeType(type)) {
                return parts.getBodyPart(i).getContent().toString();
            }
        }
        throw new AssertionError("no " + type + " part");
    }

    private static String unsubscribeLink(String text) {
        Matcher m = Pattern.compile("(https?://\\S+/pressroom-ui/unsubscribe\\.rol\\?t=[A-Za-z0-9_-]+)").matcher(text);
        assertTrue(m.find(), text);
        return m.group(1);
    }

    private void property(String name, String value) throws Exception {
        when(mocks.getPropertiesManager().getProperty(name)).thenReturn(new RuntimeConfigProperty(name, value));
    }

    private static User person(String id, String userName, String email, String screenName) {
        User u = new User();
        u.setId(id);
        u.setUserName(userName);
        u.setEmailAddress(email);
        u.setScreenName(screenName);
        u.setEnabled(Boolean.TRUE);
        return u;
    }

    private ReaderComment comment(String id, User author, String body, ReaderComment replyTo) {
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setEntry(entry);
        c.setAuthor(author);
        c.setBody(body);
        c.setReplyTo(replyTo);
        c.setStatus(ReaderComment.Status.PUBLISHED);
        return c;
    }
}
