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
package org.apache.roller.weblogger.business.comments;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.util.MailUtil;
import org.apache.roller.weblogger.util.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Emails a weblog's {@code ADMIN} members when a comment is held for approval.
 *
 * <p>Everything the message says (subject, body, link) is resolved on the
 * calling thread; the member lookup and the sending run on a pooled
 * background thread that no filter cleans up after, so the runnable releases
 * the persistence session itself in a {@code finally}. Each admin gets their
 * own message, so no admin sees another's address. The email is plain text:
 * the writer's name is not escaped. A failure is logged with the comment id
 * only (never an address or the body) and never touches the comment, which is
 * already stored.
 */
@Component
public class MailCommentNotifier implements CommentNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailCommentNotifier.class);

    /** How much of the comment the email quotes. */
    // A notice is a nudge, not a copy: the full text, up to 4000 characters, is on the Comments page.
    private static final int EXCERPT_LENGTH = 500;

    private final Weblogger weblogger;

    public MailCommentNotifier(@Lazy Weblogger weblogger) {
        this.weblogger = weblogger;
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
            if (next == null && current instanceof jakarta.mail.MessagingException messaging) {
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
                + "/roller-ui/authoring/comments.rol?weblog=" + weblog.getHandle();
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
                } catch (Exception ex) {
                    log.error("Could not send the held-comment notice for comment {}: {}", commentId, kinds(ex));
                }
            }
        } catch (Exception ex) {
            log.error("Could not find who to tell about held comment {}: {}", commentId, kinds(ex));
        } finally {
            weblogger.release();
        }
    }

    /** Distinct email addresses of enabled {@code ADMIN} members who have one; agents are left out. */
    private List<String> adminAddresses(Weblog weblog) throws Exception {
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
