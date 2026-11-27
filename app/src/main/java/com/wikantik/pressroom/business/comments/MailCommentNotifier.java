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

import jakarta.mail.MessagingException;
import com.wikantik.pressroom.WebloggerException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.util.MailUtil;
import com.wikantik.pressroom.util.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Emails a weblog's {@code ADMIN} members when a comment is held for approval,
 * and a reader when an author replies to their comment.
 *
 * <p>Everything the message says (subject, body, link) is resolved on the
 * calling thread; the member lookup and the sending run on a pooled
 * background thread that no filter cleans up after, so the runnable releases
 * the persistence session itself in a {@code finally}. Each admin gets their
 * own message, so no admin sees another's address. The email is plain text:
 * the writer's name is not escaped. A failure is logged with the comment id
 * only (never an address or the body) and never touches the comment, which is
 * already stored.
 *
 * <p><b>Reply mail</b> ({@link #notifyReplied}) goes to the answered comment's
 * author when they have an address, are enabled, have not turned reply emails
 * off, did not write the reply themselves, and mail is configured with a
 * {@code site.adminemail} to send from. It is text plus HTML: both comments as
 * plain text (escaped in the HTML part, never content markup), a link to the
 * reply on the entry page and a one-click unsubscribe link, both absolute
 * from the site's address, with the matching {@code List-Unsubscribe} and
 * {@code List-Unsubscribe-Post} headers. Without the site's absolute address
 * no link would open, so nothing is sent.
 */
@Component
public class MailCommentNotifier implements CommentNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailCommentNotifier.class);

    /** How much of the comment the email quotes. */
    // A notice is a nudge, not a copy: the full text, up to 4000 characters, is on the Comments page.
    private static final int EXCERPT_LENGTH = 500;

    /** How much of the reader's own comment a reply email quotes, in characters (code points). */
    private static final int QUOTE_LENGTH = 300;

    private static final String ELLIPSIS = "\u2026";

    private final Weblogger weblogger;
    private final UnsubscribeTokens tokens;

    /** With unsubscribe links signed by the tier's generated secret. */
    public MailCommentNotifier(Weblogger weblogger) {
        this(weblogger, new UnsubscribeTokens(weblogger));
    }

    @Autowired
    public MailCommentNotifier(@Lazy Weblogger weblogger, UnsubscribeTokens tokens) {
        this.weblogger = weblogger;
        this.tokens = tokens;
    }

    @Override
    public void notifyHeld(ReaderComment comment) {
        String commentId = comment.getId();
        try {
            WeblogEntry entry = comment.getEntry();
            Weblog weblog = entry.getWebsite();
            if (!Boolean.TRUE.equals(weblog.getCommentNotify()) || !MailUtil.isMailConfigured()) {
                return;
            }
            ResourceBundle resources = ResourceBundle.getBundle(
                    "ApplicationResources", weblog.getLocaleInstance());
            String subject = MessageFormat.format(
                    resources.getString("comments.notify.subject"), weblog.getName());
            String content = MessageFormat.format(resources.getString("comments.notify.body"),
                    comment.getAuthor().getScreenName(),
                    Utilities.unescapeHTML(entry.getTitle()),
                    StringUtils.left(comment.getBody(), EXCERPT_LENGTH),
                    moderationUrl(weblog));
            String handle = weblog.getHandle();
            String from = WebloggerRuntimeConfig.getProperty("site.adminemail");
            weblogger.getThreadManager().executeInBackground(
                    () -> sendToAdmins(handle, from, subject, content, commentId));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Could not schedule the held-comment notice for comment {}: {}", commentId, kinds(ex));
        } catch (RuntimeException ex) {
            log.error("Could not prepare the held-comment notice for comment {}: {}", commentId, kinds(ex));
        }
    }

    @Override
    public void notifyReplied(ReaderComment reply) {
        String replyId = reply.getId();
        try {
            ReaderComment answered = reply.getReplyTo();
            User reader = answered == null ? null : answered.getAuthor();
            String from = WebloggerRuntimeConfig.getProperty("site.adminemail");
            if (!wantsReplyMail(reader, reply.getAuthor()) || !MailUtil.isMailConfigured()
                    || StringUtils.isBlank(from)) {
                return;
            }
            String site = WebloggerRuntimeConfig.getAbsoluteContextURL();
            if (StringUtils.isBlank(site)) {
                log.warn("No reply email for reply {}: the site's absolute address is not known", replyId);
                return;
            }
            ReplyMail message = replyMail(reply, answered, reader, site);
            String to = reader.getEmailAddress();
            weblogger.getThreadManager().executeInBackground(() -> sendReply(from, to, message, replyId));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Could not schedule the reply email for reply {}: {}", replyId, kinds(ex));
        } catch (RuntimeException ex) {
            log.error("Could not prepare the reply email for reply {}: {}", replyId, kinds(ex));
        }
    }

    /** The reader has an address, is enabled, wants reply emails, and did not write the reply. */
    private static boolean wantsReplyMail(User reader, User replyAuthor) {
        return reader != null
                && reader.isNotifyReplies()
                && !Boolean.FALSE.equals(reader.getEnabled())
                && !reader.isAgent()
                && StringUtils.isNotBlank(reader.getEmailAddress())
                && (replyAuthor == null || !Objects.equals(reader.getId(), replyAuthor.getId()));
    }

    /** Everything the reply email says, resolved on the calling thread. */
    private ReplyMail replyMail(ReaderComment reply, ReaderComment answered, User reader, String site) {
        WeblogEntry entry = reply.getEntry();
        Weblog weblog = entry.getWebsite();
        ResourceBundle resources = ResourceBundle.getBundle("ApplicationResources", weblog.getLocaleInstance());
        String author = oneLine(reply.getAuthor() == null ? "" : reply.getAuthor().getScreenName());
        String title = oneLine(Utilities.unescapeHTML(entry.getDisplayTitle()));
        String quoted = shorten(answered.getBody());
        String answer = StringUtils.defaultString(reply.getBody());
        String entryLink = weblogger.getUrlStrategy().getWeblogEntryURL(weblog, null, entry.getAnchor(), true)
                + "#comment-" + reply.getId();
        String unsubscribeLink = StringUtils.stripEnd(site, "/") + "/pressroom-ui/unsubscribe.rol?t="
                + tokens.issue(reader.getId(), UnsubscribeTokens.REPLIES);

        String intro = MessageFormat.format(resources.getString("comments.replied.intro"), author, title);
        String yours = resources.getString("comments.replied.yours");
        String wrote = MessageFormat.format(resources.getString("comments.replied.wrote"), author);
        String see = resources.getString("comments.replied.see");
        String why = resources.getString("comments.replied.why");
        String stop = resources.getString("comments.replied.stop");

        String text = intro + "\n\n" + yours + "\n" + quoted + "\n\n" + wrote + "\n" + answer + "\n\n"
                + see + ": " + entryLink + "\n\n-- \n" + why + "\n" + stop + ": " + unsubscribeLink + "\n";
        String html = "<!DOCTYPE html><html><body>"
                + "<p>" + escape(intro) + "</p>"
                + "<p><strong>" + escape(yours) + "</strong></p>"
                + "<blockquote style=\"white-space:pre-wrap\">" + escape(quoted) + "</blockquote>"
                + "<p><strong>" + escape(wrote) + "</strong></p>"
                + "<blockquote style=\"white-space:pre-wrap\">" + escape(answer) + "</blockquote>"
                + "<p><a href=\"" + escape(entryLink) + "\">" + escape(see) + "</a></p>"
                + "<hr><p style=\"font-size:small\">" + escape(why) + " <a href=\"" + escape(unsubscribeLink)
                + "\">" + escape(stop) + "</a></p>"
                + "</body></html>";
        String subject = MessageFormat.format(resources.getString("comments.replied.subject"), author, title);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("List-Unsubscribe", "<" + unsubscribeLink + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        return new ReplyMail(subject, text, html, headers);
    }

    /** The runnable's body: sends the reply email, logging (never throwing) a failure. */
    private static void sendReply(String from, String to, ReplyMail message, String replyId) {
        try {
            MailUtil.sendAlternativeMessage(from, to, message.subject(), message.text(), message.html(),
                    message.headers());
        } catch (MessagingException | RuntimeException ex) {
            log.error("Could not send the reply email for reply {}: {}", replyId, kinds(ex));
        }
    }

    /** At most {@link #QUOTE_LENGTH} code points, with an ellipsis when cut. */
    private static String shorten(String body) {
        String text = StringUtils.defaultString(body);
        if (text.codePointCount(0, text.length()) <= QUOTE_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, QUOTE_LENGTH)) + ELLIPSIS;
    }

    /** A subject or name on one line: line breaks become spaces. */
    private static String oneLine(String value) {
        return StringUtils.defaultString(value).replaceAll("[\\r\\n]+", " ").strip();
    }

    private static String escape(String value) {
        return StringEscapeUtils.escapeHtml4(value);
    }

    /** A reply email's subject, bodies and headers. */
    private record ReplyMail(String subject, String text, String html, Map<String, String> headers) {
    }

    /**
     * The class names along an exception's cause and next-exception chains,
     * never a message or stack trace: a mail failure's messages routinely
     * quote the recipient address.
     */
    private static String kinds(Throwable failure) {
        List<String> names = new ArrayList<>();
        Throwable current = failure;
        while (current != null && names.size() < 10) {
            names.add(current.getClass().getSimpleName());
            Throwable next = current.getCause();
            if (next == null && current instanceof MessagingException messaging) {
                next = messaging.getNextException();
            }
            current = next;
        }
        return String.join(" <- ", names);
    }

    private static String moderationUrl(Weblog weblog) {
        String base = StringUtils.defaultString(
                WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"));
        return StringUtils.stripEnd(base, "/")
                + "/pressroom-ui/app/weblogs/" + weblog.getHandle() + "/comments";
    }

    /** The runnable's body: runs off the request thread, so it releases the session. */
    private void sendToAdmins(String handle, String from, String subject, String content,
                              String commentId) {
        try {
            Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle);
            if (weblog == null) {
                return;
            }
            for (String address : adminAddresses(weblog)) {
                try {
                    MailUtil.sendTextMessage(from, new String[] {address}, null, null, subject, content);
                } catch (MessagingException | RuntimeException ex) {
                    log.error("Could not send the held-comment notice for comment {}: {}", commentId, kinds(ex));
                }
            }
        } catch (WebloggerException | RuntimeException ex) {
            log.error("Could not find who to tell about held comment {}: {}", commentId, kinds(ex));
        } finally {
            weblogger.release();
        }
    }

    /** Distinct email addresses of enabled {@code ADMIN} members who have one; agents are left out. */
    private List<String> adminAddresses(Weblog weblog) throws WebloggerException {
        UserManager users = weblogger.getUserManager();
        List<String> addresses = new ArrayList<>();
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            if (!permission.hasAction(WeblogPermission.ADMIN)) {
                continue;
            }
            User user = users.getUserByUserName(permission.getUserName());
            if (user != null && !user.isAgent() && !Boolean.FALSE.equals(user.getEnabled())
                    && StringUtils.isNotBlank(user.getEmailAddress())
                    && !addresses.contains(user.getEmailAddress())) {
                addresses.add(user.getEmailAddress());
            }
        }
        return addresses;
    }
}
