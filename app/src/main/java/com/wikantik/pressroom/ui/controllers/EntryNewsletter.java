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

package com.wikantik.pressroom.ui.controllers;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * Sending an entry to the weblog's newsletter list as a Listmonk campaign, shared by the entry editor's "Send as
 * newsletter" action and the automation API.
 *
 * <p>Synchronous by design: there is no retry queue, so the person who asked IS the retry mechanism. {@code
 * newsletterSentAt} is stamped ONLY after {@link ListmonkClient#sendCampaign} returns, so a failed attempt
 * stamps nothing and an already-stamped entry is refused before the client is touched; together those two
 * guarantee an entry cannot be sent twice.
 *
 * <p>The two exception scopes in {@link #send} are deliberately separate. Once the campaign has gone, the
 * email is irreversibly with every subscriber, and a failure recording that is a different situation from the
 * send failing: {@link Outcome#SENT_NOT_RECORDED} says explicitly not to retry, where {@link Outcome#SEND_FAILED}
 * invites it.
 *
 * <p>The caller owns the permission rule (it mails every subscriber, so the editor and the API both need
 * {@code POST} on the weblog) and the wording of each outcome.
 */
public final class EntryNewsletter {

    private static final Logger log = LoggerFactory.getLogger(EntryNewsletter.class);

    private EntryNewsletter() {
    }

    /** How a send ended. */
    public enum Outcome {
        /** The campaign was created and the entry stamped. */
        SENT,
        /** Only a published entry can be sent. */
        NOT_PUBLISHED,
        /** The entry carries a send stamp already. */
        ALREADY_SENT,
        /** The weblog has no newsletter list. */
        NO_LIST,
        /** The site has no Listmonk API credentials. */
        NOT_CONFIGURED,
        /** Listmonk refused or could not be reached; nothing was stamped. */
        SEND_FAILED,
        /** The campaign went but the stamp could not be saved; sending again would mail everyone twice. */
        SENT_NOT_RECORDED
    }

    /**
     * What happened and, for the two failures, the exception's raw message (escape it for markup).
     *
     * @param detail null unless the outcome is a failure
     */
    public record Result(Outcome outcome, String detail) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /**
     * Sends {@code entry} and records it, or says why not. The client comes from a supplier so that nothing is
     * built for an entry that is refused first.
     *
     * @param permalink the entry's public address, for the "Read on the site" link
     */
    public static Result send(Weblogger weblogger, Supplier<ListmonkClient> clients, WeblogEntry entry,
                              String permalink) {
        if (!entry.isPublished()) {
            return Result.of(Outcome.NOT_PUBLISHED);
        }
        if (entry.getNewsletterSentAt() != null) {
            return Result.of(Outcome.ALREADY_SENT);
        }
        String listUuid = entry.getWebsite().getNewsletterListUuid();
        ListmonkClient client = clients.get();
        if (StringUtils.isBlank(listUuid)) {
            return Result.of(Outcome.NO_LIST);
        }
        if (!client.isCampaignConfigured()) {
            return Result.of(Outcome.NOT_CONFIGURED);
        }
        return sendCampaign(weblogger, entry, listUuid, client, permalink);
    }

    /**
     * Builds the campaign HTML from the same theme-independent seam feeds use ({@code
     * EntryRenderer.transformedText()}) so the newsletter body matches what the entry renders as, escapes only
     * the title (the body is already-sanitized HTML by the time it reaches here), and stamps {@code
     * newsletterSentAt} strictly after {@code sendCampaign} returns without throwing.
     */
    private static Result sendCampaign(Weblogger weblogger, WeblogEntry entry, String listUuid,
                                       ListmonkClient client, String permalink) {
        String html = "<h1>" + StringEscapeUtils.escapeHtml4(entry.getTitle()) + "</h1>\n"
                + weblogger.getEntryRenderer().transformedText(entry)
                + "\n<p><a href=\"" + permalink + "\">Read on the site</a></p>";
        try {
            client.sendCampaign(listUuid, entry.getTitle(), html);
        } catch (IOException ex) {
            log.error("Error sending newsletter campaign for entry {}", entry.getId(), ex);
            return new Result(Outcome.SEND_FAILED, ex.getMessage());
        }

        // The campaign is already sent at this point. Nothing from here on may be reported as an ordinary,
        // retry-inviting failure.
        try {
            entry.setNewsletterSentAt(new Timestamp(System.currentTimeMillis()));
            weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
            weblogger.flush();
            return Result.of(Outcome.SENT);
        } catch (WebloggerException ex) {
            log.error("Newsletter campaign for entry {} was sent, but recording newsletterSentAt failed",
                    entry.getId(), ex);
            return new Result(Outcome.SENT_NOT_RECORDED, ex.getMessage());
        }
    }
}
