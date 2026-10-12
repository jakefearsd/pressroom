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

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Every rule about reader comments, in one place: who may post where, what
 * status a comment gets, which replies are allowed, the rate limit, and when
 * a change expires the weblog's rendered pages. The posting endpoint, the
 * moderation page, the API and the account page all come through here.
 *
 * <p>Comments passed in ({@link #setStatus}, {@link #delete}, {@link #reply})
 * must be managed, that is, fetched in the current request. Logs go to
 * {@code roller.comments}: ids, statuses, usernames and refusal codes, never
 * a body or an email address.
 */
@Component
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger("roller.comments");

    /** At most this many comments per account in any ten minutes... */
    static final int PER_TEN_MINUTES = 5;
    /** ...and this many in a day. */
    static final int PER_DAY = 30;
    private static final int TEN_MINUTES_SECONDS = 600;
    private static final int DAY_SECONDS = 86_400;
    private static final int TRACKED_ACCOUNTS = 1000;

    private final Weblogger weblogger;
    private final CommentNotifier notifier;
    private final GenericThrottle tenMinutes;
    private final GenericThrottle day;

    /**
     * @param weblogger the lazily-resolved business tier
     * @param notifier  told about each comment stored {@code PENDING}
     */
    @Autowired
    public CommentService(@Lazy Weblogger weblogger, CommentNotifier notifier) {
        this(weblogger, notifier, tenMinuteThrottle(), dayThrottle());
    }

    /** With the rate limit's two windows supplied, so a test need not wait. */
    CommentService(Weblogger weblogger, CommentNotifier notifier, GenericThrottle tenMinutes, GenericThrottle day) {
        this.weblogger = weblogger;
        this.notifier = notifier;
        this.tenMinutes = tenMinutes;
        this.day = day;
    }

    /** The production ten-minute window. */
    static GenericThrottle tenMinuteThrottle() {
        return limit(PER_TEN_MINUTES, TEN_MINUTES_SECONDS);
    }

    /** The production one-day window. */
    static GenericThrottle dayThrottle() {
        return limit(PER_DAY, DAY_SECONDS);
    }

    /**
     * GenericThrottle calls an account abusive once its hits EXCEED the
     * threshold, so "at most {@code allowed}" is a threshold one lower: after
     * {@code allowed} stored comments, the next is refused.
     */
    private static GenericThrottle limit(int allowed, int seconds) {
        return new GenericThrottle(allowed - 1, seconds * RollerConstants.SEC_IN_MS, TRACKED_ACCOUNTS);
    }

    // ------------------------------------------------------------- who may post

    /**
     * Why this account may not post a top-level comment on this entry, or null
     * when it may. The account is re-read, so one disabled since sign-in is
     * refused. The checks run in this order: signed in, enabled, the global
     * {@code comment} action, the entry published, comments on, no custom
     * domain, the entry open.
     */
    public CommentRefusal whyNot(User user, WeblogEntry entry) throws WebloggerException {
        if (user == null) {
            return CommentRefusal.NOT_SIGNED_IN;
        }
        return whyNotFor(reread(user), entry);
    }

    private CommentRefusal whyNotFor(User current, WeblogEntry entry) throws WebloggerException {
        CommentRefusal account = accountRefusal(current);
        if (account != null) {
            return account;
        }
        if (entry == null || entry.getStatus() != PubStatus.PUBLISHED) {
            return CommentRefusal.NOT_FOUND;
        }
        Weblog weblog = entry.getWebsite();
        if (!Boolean.TRUE.equals(weblog.getCommentsEnabled())) {
            return CommentRefusal.COMMENTS_OFF;
        }
        if (StringUtils.isNotBlank(weblog.getCustomDomain())) {
            return CommentRefusal.CUSTOM_DOMAIN;
        }
        if (Boolean.TRUE.equals(entry.getCommentsClosed())) {
            return CommentRefusal.CLOSED;
        }
        return null;
    }

    /** DISABLED for a missing or disabled account, NO_PERMISSION without {@code comment}, else null. */
    private CommentRefusal accountRefusal(User current) throws WebloggerException {
        if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
            return CommentRefusal.DISABLED;
        }
        if (!users().checkPermission(new GlobalPermission(List.of(GlobalPermission.COMMENT)), current)) {
            return CommentRefusal.NO_PERMISSION;
        }
        return null;
    }

    /** The account as stored now, enabled or not; null when it no longer exists. */
    private User reread(User user) throws WebloggerException {
        return user.getUserName() == null ? null : users().getUserByUserName(user.getUserName(), null);
    }

    /** True when the account holds any permission on the weblog: an owner, whatever the role. */
    public boolean isOwner(User user, Weblog weblog) throws WebloggerException {
        return user != null && users().getWeblogPermission(weblog, user) != null;
    }

    // ------------------------------------------------------------- writing

    /**
     * Posts a comment, or an owner's reply when {@code replyToId} is given, as
     * the public form does. Nothing is stored unless every rule passes.
     */
    public CommentOutcome post(User user, String entryId, String body, String replyToId)
            throws WebloggerException {
        if (user == null) {
            return refused(CommentRefusal.NOT_SIGNED_IN, null, null);
        }
        User author = reread(user);
        WeblogEntry entry = entryId == null ? null : weblogger.getWeblogEntryManager().getWeblogEntry(entryId);
        CommentRefusal why = whyNotFor(author, entry);
        if (why != null) {
            return refused(why, author, entry);
        }
        boolean owner = isOwner(author, entry.getWebsite());
        ReaderComment parent = null;
        if (replyToId != null) {
            parent = comments().getComment(replyToId);
            if (!acceptsReply(owner, parent, entry)) {
                return refused(CommentRefusal.BAD_REPLY, author, entry);
            }
        }
        return store(author, entry, parent, owner, body);
    }

    /**
     * An owner's reply to a comment, from moderation or the API. Unlike
     * {@link #post} it does not need comments on, the entry open or the weblog
     * free of a custom domain: existing comments stay moderatable.
     */
    public CommentOutcome reply(User owner, ReaderComment parent, String body) throws WebloggerException {
        if (owner == null) {
            return refused(CommentRefusal.NOT_SIGNED_IN, null, null);
        }
        User author = reread(owner);
        CommentRefusal account = accountRefusal(author);
        if (account != null) {
            return refused(account, author, null);
        }
        if (parent == null) {
            return refused(CommentRefusal.NOT_FOUND, author, null);
        }
        WeblogEntry entry = parent.getEntry();
        if (!acceptsReply(isOwner(author, entry.getWebsite()), parent, entry)) {
            return refused(CommentRefusal.BAD_REPLY, author, entry);
        }
        return store(author, entry, parent, true, body);
    }

    /**
     * Only an owner replies, and only to a PUBLISHED top-level comment on the
     * same entry that has no reply yet. One reply per comment, ever: a reply in
     * any status counts.
     */
    private boolean acceptsReply(boolean owner, ReaderComment parent, WeblogEntry entry)
            throws WebloggerException {
        return owner
                && parent != null
                && parent.getStatus() == Status.PUBLISHED
                && parent.getReplyTo() == null
                && parent.getEntry().getId().equals(entry.getId())
                && replyOf(parent) == null;
    }

    /**
     * The comment's reply in any status, or null, asked of the store rather
     * than of {@code getReply()}: a render re-reads a cached parent with only
     * its PUBLISHED reply ({@code CommentManager.getPublishedForEntry}), so the
     * parent can lose sight of a hidden one. {@code replyTo} is the owning side
     * and stays true.
     */
    private ReaderComment replyOf(ReaderComment parent) throws WebloggerException {
        if (parent.getReplyTo() != null) {
            return null;
        }
        WeblogEntry entry = parent.getEntry();
        for (ReaderComment c : comments().getComments(entry.getWebsite(), null, entry, 0, Integer.MAX_VALUE)) {
            if (parent.equals(c.getReplyTo())) {
                return c;
            }
        }
        return null;
    }

    /** The body and rate checks, then the store, expiry and notification shared by post and reply. */
    private CommentOutcome store(User author, WeblogEntry entry, ReaderComment parent, boolean owner, String body)
            throws WebloggerException {
        String text = CommentRules.normalizeBody(body);
        CommentRefusal why = CommentRules.bodyRefusal(text);
        if (why == null && rateLimited(author)) {
            why = CommentRefusal.RATE_LIMITED;
        }
        if (why != null) {
            return refused(why, author, entry);
        }
        Weblog weblog = entry.getWebsite();
        Status status = parent != null
                ? Status.PUBLISHED
                : CommentRules.statusFor(owner, !owner && comments().isTrusted(author, weblog), text);
        Date now = new Date();
        ReaderComment c = new ReaderComment();
        c.setEntry(entry);
        c.setAuthor(author);
        c.setReplyTo(parent);
        c.setBody(text);
        c.setStatus(status);
        c.setAuthorIsOwner(owner);
        c.setCreated(now);
        c.setStatusChanged(now);
        comments().saveComment(c);
        if (status == Status.PUBLISHED) {
            expire(weblog);
        } else {
            weblogger.flush();
        }
        tenMinutes.processHit(author.getId());
        day.processHit(author.getId());
        log.info("comment {} stored {} on entry {} by {}{}", c.getId(), status, entry.getId(),
                author.getUserName(), parent == null ? "" : " in reply to " + parent.getId());
        if (status == Status.PENDING) {
            notifier.notifyHeld(c);
        }
        return CommentOutcome.stored(c);
    }

    private boolean rateLimited(User author) {
        return tenMinutes.isAbusive(author.getId()) || day.isAbusive(author.getId());
    }

    private static CommentOutcome refused(CommentRefusal why, User user, WeblogEntry entry) {
        String who = user == null ? "-" : user.getUserName();
        String where = entry == null ? "-" : entry.getId();
        if (why == CommentRefusal.RATE_LIMITED) {
            log.info("comment refused {} on entry {} by {}", why, where, who);
        } else {
            log.debug("comment refused {} on entry {} by {}", why, where, who);
        }
        return CommentOutcome.refused(why);
    }

    // ------------------------------------------------------------- moderation

    /**
     * Moves a comment to a status; moving it to the one it has does nothing.
     * Pages expire only when the move is into or out of PUBLISHED. Works with
     * comments off: existing comments stay moderatable. The caller has
     * authorized {@code actor}; it is logged.
     */
    public void setStatus(ReaderComment c, Status status, User actor) throws WebloggerException {
        Objects.requireNonNull(status, "status");
        Status before = c.getStatus();
        if (before == status) {
            return;
        }
        c.setStatus(status);
        c.setStatusChanged(new Date());
        comments().saveComment(c);
        log.info("comment {} {} -> {} by {}", c.getId(), before, status, nameOf(actor));
        if (before == Status.PUBLISHED || status == Status.PUBLISHED) {
            expire(c.getEntry().getWebsite());
        } else {
            weblogger.flush();
        }
    }

    /**
     * Deletes a comment and its reply. Pages expire when the comment was
     * PUBLISHED. The caller has authorized {@code actor}; it is logged.
     */
    public void delete(ReaderComment c, User actor) throws WebloggerException {
        boolean shown = c.getStatus() == Status.PUBLISHED;
        Weblog weblog = c.getEntry().getWebsite();
        // Removed explicitly: the cascade reaches only a reply the cached parent
        // still knows about, and the database's own cascade leaves the cache stale.
        ReaderComment reply = replyOf(c);
        if (reply != null) {
            comments().removeComment(reply);
        }
        comments().removeComment(c);
        log.info("comment {} deleted{} by {}", c.getId(), reply == null ? "" : " with reply " + reply.getId(),
                nameOf(actor));
        if (shown) {
            expire(weblog);
        } else {
            weblogger.flush();
        }
    }

    /** A reader deletes their own comment; false (and nothing done) when it is not theirs. */
    public boolean deleteOwn(User user, String commentId) throws WebloggerException {
        if (user == null || commentId == null) {
            return false;
        }
        User current = reread(user);
        if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
            return false;
        }
        ReaderComment c = comments().getComment(commentId);
        if (c == null || !c.getAuthor().getId().equals(current.getId())) {
            return false;
        }
        delete(c, current);
        return true;
    }

    /** Bumps lastModified (saveWeblog sets it) on the weblog, so rendered pages expire. */
    void expire(Weblog weblog) throws WebloggerException {
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        // WeblogPageCache expires only against weblog.lastModified; this reaches the other caches.
        CacheManager.invalidate(weblog);
        log.debug("weblog {} expired", weblog.getHandle());
    }

    private static String nameOf(User user) {
        return user == null ? "-" : user.getUserName();
    }

    private UserManager users() {
        return weblogger.getUserManager();
    }

    private CommentManager comments() {
        return weblogger.getCommentManager();
    }
}
