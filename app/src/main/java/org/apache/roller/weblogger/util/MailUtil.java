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

package org.apache.roller.weblogger.util;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MailProvider;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * A utility class for helping with sending email. 
 */
public final class MailUtil {

    private MailUtil() {
    }

    private static final Logger log = LoggerFactory.getLogger(MailUtil.class);


    /**
     * Ideally mail senders should call this first to avoid errors that occur 
     * when mail is not properly configured. We'll complain about that at 
     * startup, no need to complain on every attempt to send.
     */
    public static boolean isMailConfigured() {
        return WebloggerStartup.getMailProvider() != null; 
    }
    
    /**
     * Whether {@code user} may post on {@code weblog}; a check that cannot be
     * answered is a denial, logged (was {@code Weblog.hasUserPermission}).
     */
    private static boolean isAuthor(Weblogger weblogger, Weblog weblog, User user) {
        try {
            return weblogger.getUserManager().checkPermission(
                    new WeblogPermission(weblog, user, List.of(WeblogPermission.POST)), user);
        } catch (WebloggerException ex) {
            log.error("ERROR checking user permssion", ex);
            return false;
        }
    }

    /**
     * Send an email notice that a new pending entry has been submitted.
     *
     * @param weblogger the business tier, handed in by the caller: it answers
     *                  who the weblog's reviewers are and builds the edit link
     */
    public static void sendPendingEntryNotice(Weblogger weblogger, WeblogEntry entry)
            throws WebloggerException {

        Session mailSession = WebloggerStartup.getMailProvider() != null
                ? WebloggerStartup.getMailProvider().getSession() : null;

        if (mailSession == null) {
            throw new WebloggerException("Couldn't get mail Session");
        }

        try {
            WeblogManager wmgr = weblogger.getWeblogManager();

            User creator = weblogger.getUserManager().getUserByUserName(entry.getCreatorUserName());
            String userName = creator.getUserName();
            // An agent has no mailbox: its @agents.invalid address is neither
            // copied (nothing is mailed to an agent) nor used as the sender
            // (relays check the sender and would refuse the notice for the
            // people); the site address stands in, or none, leaving the
            // session default. The body still names the agent.
            String from = creator.isAgent()
                    ? WebloggerRuntimeConfig.getProperty("site.adminemail")
                    : creator.getEmailAddress();
            String cc[] = creator.isAgent() || from == null ? new String[0] : new String[] {from};
            String bcc[] = new String[0];
            String to[];
            String subject;
            String content;
            
            // list of enabled website authors and admins
            List<String> reviewers = new ArrayList<>();
            List<User> websiteUsers = wmgr.getWeblogUsers(entry.getWebsite(), true);
            
            // build list of reviewers (website users with author permission);
            // agents are left out: nothing is mailed to one, and a single
            // refused recipient would fail the notice for the people
            for (User websiteUser : websiteUsers) {
                if (!websiteUser.isAgent() && isAuthor(weblogger, entry.getWebsite(), websiteUser)
                        && websiteUser.getEmailAddress() != null) {
                    reviewers.add(websiteUser.getEmailAddress());
                }
            }

            to = reviewers.toArray(String[]::new);
            if (to.length == 0) {
                log.debug("No reviewer to tell about pending entry {}", entry.getId());
                return;
            }
            
            // Figure URL to entry edit page
            String editURL = weblogger.getUrlStrategy()
                    .getEntryEditURL(entry.getWebsite().getHandle(), entry.getId(), true);
            
            ResourceBundle resources = ResourceBundle.getBundle(
                    "ApplicationResources", entry.getWebsite().getLocaleInstance());
            StringBuilder sb = new StringBuilder();
            sb.append(
                    MessageFormat.format(
                    resources.getString("weblogEntry.pendingEntrySubject"),
                    entry.getWebsite().getName(),
                    entry.getWebsite().getHandle()));
            subject = sb.toString();
            sb = new StringBuilder();
            sb.append(
                    MessageFormat.format(
                    resources.getString("weblogEntry.pendingEntryContent"),
                    userName, userName, editURL)
                    );
            content = sb.toString();
            sendTextMessage(
                    from, to, cc, bcc, subject, content);
        } catch (MessagingException e) {
            log.error("ERROR: Problem sending pending entry notification email.");
        }
    }
    
    
    // agangolli: Incorporated suggested changes from Ken Blackler.
    
    /**
     * This method is used to send a Message with a pre-defined
     * mime-type.
     *
     * @param from e-mail address of sender
     * @param to e-mail address(es) of recipients
     * @param subject subject of e-mail
     * @param content the body of the e-mail
     * @param mimeType type of message, i.e. text/plain or text/html
     * @throws MessagingException the exception to indicate failure
     */
    public static void sendMessage(String from, String[] to, String[] cc, String[] bcc, String subject,
            String content, String mimeType) throws MessagingException {
        sendMessage(from, null, to, cc, bcc, subject, content, mimeType);
    }

    /**
     * This method is used to send a Message with a pre-defined mime-type and
     * an optional Reply-To address.
     *
     * @param from e-mail address of sender
     * @param replyTo e-mail address recipients should reply to instead of
     *                {@code from}, or null to leave the header unset (the
     *                MTA then defaults replies to {@code from})
     * @param to e-mail address(es) of recipients
     * @param subject subject of e-mail
     * @param content the body of the e-mail
     * @param mimeType type of message, i.e. text/plain or text/html
     * @throws MessagingException the exception to indicate failure
     */
    private static void sendMessage(String from, String replyTo, String[] to, String[] cc, String[] bcc,
            String subject, String content, String mimeType) throws MessagingException {

        MailProvider mailProvider = WebloggerStartup.getMailProvider();
        if (mailProvider == null) {
            return;
        }

        Session session = mailProvider.getSession();
        MimeMessage message = new MimeMessage(session);

        // n.b. any default from address is expected to be determined by caller.
        if (! StringUtils.isEmpty(from)) {
            InternetAddress sentFrom = new InternetAddress(from);
            message.setFrom(sentFrom);
            log.debug("e-mail from is set");
        }

        // A form submitter's or commenter's address is not the account this
        // notification is sent as, so replies must not silently go to the
        // weblog's own from address (or nowhere, when no from is set).
        if (! StringUtils.isEmpty(replyTo)) {
            message.setReplyTo(new InternetAddress[]{ new InternetAddress(replyTo) });
            log.debug("e-mail reply-to is set");
        }

        if (to!=null) {
            InternetAddress[] sendTo = new InternetAddress[to.length];

            for (int i = 0; i < to.length; i++) {
                sendTo[i] = new InternetAddress(to[i]);
                log.debug("e-mail to recipient {} of {}", i + 1, to.length);
            }
            message.setRecipients(Message.RecipientType.TO, sendTo);
        }

        if (cc != null) {
            InternetAddress[] copyTo = new InternetAddress[cc.length];

            for (int i = 0; i < cc.length; i++) {
                copyTo[i] = new InternetAddress(cc[i]);
                log.debug("e-mail cc recipient {} of {}", i + 1, cc.length);
            }
            message.setRecipients(Message.RecipientType.CC, copyTo);
        }

        if (bcc != null) {
            InternetAddress[] copyTo = new InternetAddress[bcc.length];

            for (int i = 0; i < bcc.length; i++) {
                copyTo[i] = new InternetAddress(bcc[i]);
                log.debug("e-mail bcc recipient {} of {}", i + 1, bcc.length);
            }
            message.setRecipients(Message.RecipientType.BCC, copyTo);
        }
        message.setSubject((subject == null) ? "(no subject)" : subject, "UTF-8");
        message.setContent(content, mimeType);
        message.setSentDate(new java.util.Date());

        // Update the headers from the content just set. Transport.sendMessage
        // sends the message as it stands -- unlike the static Transport.send,
        // it does not do this for the caller -- so without it the Content-Type
        // header keeps its text/plain default however the body was built, and
        // MIME-Version and Message-ID never appear at all. An HTML-bodied
        // notification would have arrived as visible markup instead of being
        // rendered.
        message.saveChanges();
        
        // First collect all the addresses together.
        Address[] remainingAddresses = message.getAllRecipients();
        int nAddresses;
        boolean bFailedToSome = false;
        
        SendFailedException sendex = new SendFailedException("Unable to send message to some recipients");
        
        // Try to send while there remain some potentially good addresses
        try (Transport transport = mailProvider.getTransport()) {
            do {
                // Avoid a loop if we are stuck
                nAddresses = remainingAddresses.length;

                try {
                    // Send to the list of remaining addresses, ignoring the addresses attached to the message
                    transport.sendMessage(message, remainingAddresses);
                } catch(SendFailedException ex) {
                    bFailedToSome=true;
                    sendex.setNextException(ex);

                    // Extract the remaining potentially good addresses
                    remainingAddresses=ex.getValidUnsentAddresses();
                }
            } while (remainingAddresses!=null && remainingAddresses.length>0 
                    && remainingAddresses.length!=nAddresses);
            
        }
        
        if (bFailedToSome) {
            throw sendex;
        }
    }
    
    
    /**
     * This method is used to send a Text Message.
     *
     * @param from e-mail address of sender
     * @param to e-mail addresses of recipients
     * @param cc e-mail address of cc recipients
     * @param bcc e-mail address of bcc recipients
     * @param subject subject of e-mail
     * @param content the body of the e-mail
     * @throws MessagingException the exception to indicate failure
     */
    public static void sendTextMessage(String from, String[] to, String[] cc, String[] bcc,
                                       String subject, String content) throws MessagingException {
        sendMessage(from, to, cc, bcc, subject, content, "text/plain; charset=utf-8");
    }

    /**
     * This method is used to send a Text Message whose replies should go
     * somewhere other than {@code from} -- e.g. a contact-form notification
     * sent as the weblog, where a reply should reach the submitter, not the
     * weblog's own address.
     *
     * @param from e-mail address of sender
     * @param replyTo e-mail address recipients should reply to, or null to
     *                leave the header unset
     * @param to e-mail addresses of recipients
     * @param subject subject of e-mail
     * @param content the body of the e-mail
     * @throws MessagingException the exception to indicate failure
     */
    public static void sendTextMessage(String from, String replyTo, String[] to,
                                       String subject, String content) throws MessagingException {
        sendMessage(from, replyTo, to, null, null, subject, content, "text/plain; charset=utf-8");
    }

    /**
     * This method is used to send a HTML Message
     *
     * @param from e-mail address of sender
     * @param to e-mail address(es) of recipients
     * @param subject subject of e-mail
     * @param content the body of the e-mail
     * @throws MessagingException the exception to indicate failure
     */
    public static void sendHTMLMessage(String from, String[] to, String[] cc, String[] bcc, String subject,
                                       String content) throws MessagingException {
        sendMessage(from, to, cc, bcc, subject, content, "text/html; charset=utf-8");
    }
}
