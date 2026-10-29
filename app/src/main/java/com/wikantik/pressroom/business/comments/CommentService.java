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

import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.GenericThrottle;
import com.wikantik.pressroom.util.cache.CacheManager;
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
 * {@code pressroom.comments}: ids, statuses, usernames and refusal codes, never
 * a body or an email address.
 */
@Component
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger("pressroom.comments");

    private static final int DAY_SECONDS = 86_400;
    // Bounded memory over exactness: each throttle keeps the 1000 most recently active commenters
    // and forgets the rest, and a restart forgets all of it. A forgotten account gets a fresh window;
    // that is cheaper than a counter written to the database on every comment and fine at this scale.
    // Raise it if a site ever has more than 1000 active commenters inside the one-day window.
    private static final int TRACKED_ACCOUNTS = 1000;
    // 64 stripes, not one lock per account: two accounts that hash together only wait for each
    // other's write, and there is no unbounded lock map to clean up. The store runs inside the lock
    // on purpose, so the rate check, the write and the hit are one step per account.
    private static final int LOCK_STRIPES = 64;
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String ONE_REPLY_INDEX = "rrc_reply_to_uq";

    private final Weblogger weblogger;
    private final CommentNotifier notifier;
    private final GenericThrottle tenMinutes;
    private final GenericThrottle day;
    /** Per-account locks (by id hash) around the rate check, the store and the hit. */
    private final Object[] stripes = new Object[LOCK_STRIPES];

    /**
     * With the rate limit pressroom.properties configures ({@link CommentLimits}).
     *
     * @param weblogger the lazily-resolved business tier
     * @param notifier  told about each comment stored {@code PENDING}
     */
    @Autowired
    public CommentService(@Lazy Weblogger weblogger, CommentNotifier notifier) {
        this(weblogger, notifier, CommentLimits.fromConfig());
    }

    /** With the rate limit given, as configuration would give it. */
    CommentService(Weblogger weblogger, CommentNotifier notifier, CommentLimits limits) {
        this(weblogger, notifier, windowThrottle(limits), dailyThrottle(limits));
        log.info("comment rate limit: {} per {}s and {} a day per account", limits.windowLimit(),
                limits.windowSeconds(), limits.dailyLimit());
    }

    /** With the rate limit's two windows supplied, so a test need not wait. */
    CommentService(Weblogger weblogger, CommentNotifier notifier, GenericThrottle tenMinutes, GenericThrottle day) {
        this.weblogger = weblogger;
        this.notifier = notifier;
        this.tenMinutes = tenMinutes;
        this.day = day;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    /** The short window: {@code windowLimit} per {@code windowSeconds}. */
    static GenericThrottle windowThrottle(CommentLimits limits) {
        return limit(limits.windowLimit(), limits.windowSeconds());
    }

    /** The day: {@code dailyLimit} per 24 hours. */
    static GenericThrottle dailyThrottle(CommentLimits limits) {
        return limit(limits.dailyLimit(), DAY_SECONDS);
    }

    /**
     * The one place an ALLOWED count becomes a GenericThrottle threshold.
     * GenericThrottle calls an account abusive once its hits EXCEED the
     * threshold, so "at most {@code allowed}" is a threshold one lower: after
     * {@code allowed} stored comments, the next is refused.
     */
    private static GenericThrottle limit(int allowed, int seconds) {
        return new GenericThrottle(allowed - 1, seconds * PressroomConstants.SEC_IN_MS, TRACKED_ACCOUNTS);
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
     * Posts a comment, or an owner's reply when {@code replyToId} is given (a
     * blank one counts as not given), as the public form does. Nothing is
     * stored unless every rule passes.
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
        if (StringUtils.isNotBlank(replyToId)) {
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
    private static boolean acceptsReply(boolean owner, ReaderComment parent, WeblogEntry entry) {
        return owner
                && parent != null
                && parent.getStatus() == Status.PUBLISHED
                && parent.getReplyTo() == null
                && parent.getEntry().getId().equals(entry.getId())
                && parent.getReply() == null;
    }

    /** The body and rate checks, then the store, expiry and notification shared by post and reply. */
    private CommentOutcome store(User author, WeblogEntry entry, ReaderComment parent, boolean owner, String body)
            throws WebloggerException {
        String text = CommentRules.normalizeBody(body);
        CommentRefusal why = CommentRules.bodyRefusal(text);
        if (why != null) {
            return refused(why, author, entry);
        }
        ReaderComment c;
        // One account's check, store and record run one at a time, so a burst of
        // parallel posts cannot all pass the check before any is recorded; a hit
        // is still recorded only for a comment that was stored.
        synchronized (stripeFor(author.getId())) {
            if (rateLimited(author)) {
                return refused(CommentRefusal.RATE_LIMITED, author, entry);
            }
            c = newComment(author, entry, parent, owner, text);
            if (!commit(c)) {
                return refused(CommentRefusal.BAD_REPLY, author, entry);
            }
            tenMinutes.processHit(author.getId());
            day.processHit(author.getId());
        }
        log.info("comment {} stored {} on entry {} by {}{}", c.getId(), c.getStatus(), entry.getId(),
                author.getUserName(), parent == null ? "" : " in reply to " + parent.getId());
        if (c.getStatus() == Status.PENDING) {
            notifier.notifyHeld(c);
        }
        return CommentOutcome.stored(c);
    }

    /** The new comment: a reply is PUBLISHED; anything else as {@link CommentRules#statusFor} says. */
    private ReaderComment newComment(User author, WeblogEntry entry, ReaderComment parent, boolean owner,
            String text) throws WebloggerException {
        Status status = parent != null
                ? Status.PUBLISHED
                : CommentRules.statusFor(owner, !owner && comments().isTrusted(author, entry.getWebsite()), text);
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
        return c;
    }

    /**
     * Stores and commits the comment, expiring the weblog when it is
     * PUBLISHED. False, with the failed transaction released, when the commit
     * broke the one-reply unique index: another request's reply to the same
     * parent committed first.
     */
    private boolean commit(ReaderComment c) throws WebloggerException {
        try {
            comments().saveComment(c);
            if (c.getStatus() == Status.PUBLISHED) {
                expire(c.getEntry().getWebsite());
            } else {
                weblogger.flush();
            }
            return true;
        } catch (WebloggerException e) {
            if (c.getReplyTo() == null || !isUniqueViolation(e)) {
                throw e;
            }
            weblogger.release();
            return false;
        }
    }

    /** True when the failure's cause chain is a unique violation, by SQLState or by the one-reply index's name. */
    static boolean isUniqueViolation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(ONE_REPLY_INDEX)) {
                return true;
            }
        }
        return false;
    }

    private Object stripeFor(String accountId) {
        return stripes[Math.floorMod(accountId.hashCode(), stripes.length)];
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
     *
     * @return false, with nothing changed, when the move is refused: a
     *         reader-deleted tombstone ({@link CommentRules#isTombstone}) is
     *         never published. Every surface (moderation page, API, MCP) gets
     *         this one answer.
     */
    public boolean setStatus(ReaderComment c, Status status, User actor) throws WebloggerException {
        Objects.requireNonNull(status, "status");
        Status before = c.getStatus();
        if (before == status) {
            return true;
        }
        if (status == Status.PUBLISHED && CommentRules.isTombstone(c)) {
            log.info("comment {} not published: its author deleted it; asked by {}", c.getId(), nameOf(actor));
            return false;
        }
        c.setStatus(status);
        c.setStatusChanged(new Date());
        comments().saveComment(c);
        if (before == Status.PUBLISHED || status == Status.PUBLISHED) {
            expire(c.getEntry().getWebsite());
        } else {
            weblogger.flush();
        }
        log.info("comment {} {} -> {} by {}", c.getId(), before, status, nameOf(actor));
        return true;
    }

    /**
     * Deletes a comment and its reply. Pages expire when the comment was
     * PUBLISHED. The caller has authorized {@code actor}; it is logged.
     */
    public void delete(ReaderComment c, User actor) throws WebloggerException {
        boolean shown = c.getStatus() == Status.PUBLISHED;
        Weblog weblog = c.getEntry().getWebsite();
        ReaderComment reply = c.getReply();
        comments().removeComment(c);
        if (shown) {
            expire(weblog);
        } else {
            weblogger.flush();
        }
        log.info("comment {} deleted{} by {}", c.getId(), reply == null ? "" : " with reply " + reply.getId(),
                nameOf(actor));
    }

    /**
     * A reader deletes their own comment; false (and nothing done) when it is
     * not theirs. A reader's HIDDEN one is erased to a tombstone instead (see
     * erase). An owner's own comment or reply (authorIsOwner) is deleted
     * outright whatever its status: an owner needs no trust to keep, and a
     * tombstoned hidden reply would hold its comment's one reply slot forever.
     */
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
        if (c.getStatus() == Status.HIDDEN && !c.isAuthorIsOwner()) {
            erase(c, current);
        } else {
            delete(c, current);
        }
        return true;
    }

    /**
     * A reader's own HIDDEN comment is kept as a tombstone with its body
     * erased (ruling R5): deleting the row would erase the owner's distrust,
     * since trust needs no HIDDEN comment. Its reply goes. Nothing rendered
     * changes, so nothing expires.
     */
    private void erase(ReaderComment c, User reader) throws WebloggerException {
        ReaderComment reply = c.getReply();
        if (reply != null) {
            comments().removeComment(reply);
        }
        c.setBody("");
        comments().saveComment(c);
        weblogger.flush();
        log.info("comment {} erased{} by {}", c.getId(), reply == null ? "" : " with reply " + reply.getId(),
                reader.getUserName());
    }

    /**
     * Expires every weblog where this author has a PUBLISHED comment or reply:
     * their screen name signs it there. For a rename from anywhere (the account
     * page, Profile, User Admin), called after the rename has committed, so a
     * failure here is logged, not thrown: the rename stands, and those pages
     * show the old name until their weblog's next change.
     */
    public void expireWhereShown(User author) {
        List<Weblog> shown;
        try {
            shown = comments().getWeblogsWithPublishedCommentsBy(author);
        } catch (WebloggerException ex) {
            log.error("Could not find the weblogs showing {}; they show the old name until their next change",
                    nameOf(author), ex);
            return;
        }
        expireAll(shown);
    }

    /**
     * Expires each weblog, after the change that alters them has committed. One
     * that fails is logged and the rest still expire: the change itself stands.
     */
    public void expireAll(List<Weblog> weblogs) {
        for (Weblog weblog : weblogs) {
            try {
                expire(weblog);
            } catch (WebloggerException ex) {
                log.error("Could not expire weblog {}; its pages show the old comments until its next change",
                        weblog.getHandle(), ex);
            }
        }
    }

    /**
     * Bumps lastModified (saveWeblog sets it) on the weblog, so rendered pages
     * expire. Public for the account page, whose renames and deletions change
     * what the weblogs show.
     */
    public void expire(Weblog weblog) throws WebloggerException {
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
