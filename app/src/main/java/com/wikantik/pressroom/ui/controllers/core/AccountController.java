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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.comments.CommentRules;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.business.comments.DisplayNames;
import com.wikantik.pressroom.business.reader.ReaderList;
import com.wikantik.pressroom.business.reader.ReaderLists;
import com.wikantik.pressroom.business.reader.ReaderStateRefusal;
import com.wikantik.pressroom.business.reader.ReaderStateService;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.view.EntryListView;
import com.wikantik.pressroom.ui.core.PressroomLoginSessionManager;
import com.wikantik.pressroom.ui.core.PressroomSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The reader's account page ("Your account"), in the spec's order (0.18.0,
 * Part E): Saved, Following (with "Email me new posts" where the blog has a
 * list), Reading history (the newest {@value #HISTORY_SHOWN}, "Clear history"
 * and the "Keep my reading history" switch), every comment the account has
 * written, the Email switch (reply-notification emails), the display name,
 * and "Delete my account". Each list action posts back here and lands on its
 * own section (#saved, #following, #history), the anchors the "You" menu
 * links to.
 *
 * <p><b>Who gets in.</b> Any signed-in account with the global {@code comment}
 * action, which readers hold and editors and administrators hold too; no
 * weblog is involved. Everything here acts on the signed-in account only:
 * there is no account id in any request, and a comment id is passed to
 * {@link CommentService#deleteOwn}, which refuses one that is not the
 * account's own.
 *
 * <p><b>Failing closed.</b> The name and deletion checks are decisions: a
 * check that cannot run refuses. Expiring the weblogs' rendered pages after a
 * committed change is not: it is logged and the change stands.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class AccountController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger("pressroom.comments");

    private static final String VIEW = ".Account";
    private static final String BACK = "redirect:/pressroom-ui/account.rol";
    private static final String STORE_ERROR = "generic.error.check.logs";
    private static final String SAVED = "#saved";
    private static final String FOLLOWING = "#following";
    private static final String HISTORY = "#history";
    /** The most saves and follows the page lists. */
    private static final int LIST_SHOWN = 200;

    /** The reading history the page shows: the newest this many. */
    static final int HISTORY_SHOWN = 50;

    private final CommentService comments;
    private final ReaderStateService readers;

    /**
     * @param comments deletes the account's own comments and expires the weblogs that show them
     * @param readers  the account's saves, follows and reading history
     */
    public AccountController(CommentService comments, ReaderStateService readers) {
        this.comments = comments;
        this.readers = readers;
    }

    // ------------------------------------------------------------- security

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    /** The reader's own action: readers have no {@code login}. */
    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.COMMENT);
    }

    @Override
    public String getPageTitle() {
        return "account.title";
    }

    // ------------------------------------------------------------- the page

    @GetMapping("/account.rol")
    public String execute(HttpServletRequest request, Model model) {
        User account = getAuthenticatedUser(request);
        show(account, account.getScreenName(), request, model);
        return VIEW;
    }

    /**
     * Saves the display name, judged by {@link DisplayNames} in exactly the
     * form it is stored in. A save that fails is rolled back and the page
     * shows the account as stored, not the name that did not save.
     */
    @PostMapping("/account!saveName.rol")
    public String saveName(@RequestParam(name = "screenName", required = false) String screenName,
                           HttpServletRequest request, Model model, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        String name = DisplayNames.storedForm(screenName);
        try {
            DisplayNames.Refusal refusal = DisplayNames.refusal(name, account, weblogger.getUserManager());
            if (refusal == null) {
                rename(account, name);
                addFlashMessage(redirectAttributes, "account.name.saved", request);
                return BACK;
            }
            addError(model, refusal.messageKey(), request);
        } catch (WebloggerException ex) {
            log.error("Could not save the display name of {}", account.getUserName(), ex);
            addError(model, STORE_ERROR, request);
            weblogger.release();
            account = asStored(account);
        }
        show(account, screenName, request, model);
        return VIEW;
    }

    /**
     * The Email section's switch: reply-notification emails on when the box
     * is ticked ({@code notifyReplies} sent), off when it is not (an unticked
     * box sends nothing). A save that fails is rolled back and reported.
     */
    @PostMapping("/account!saveEmailSetting.rol")
    public String saveEmailSetting(@RequestParam(name = "notifyReplies", required = false) String notifyReplies,
                                   HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        boolean on = notifyReplies != null;
        try {
            account.setNotifyReplies(on);
            weblogger.getUserManager().saveUser(account);
            weblogger.flush();
            log.info("account {} turned reply emails {}", account.getUserName(), on ? "on" : "off");
            addFlashMessage(redirectAttributes, "account.email.saved", request);
        } catch (WebloggerException ex) {
            log.error("Could not save the email setting of {}", account.getUserName(), ex);
            weblogger.release();
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + "#email";
    }

    /** Saved's Remove: the save goes, whether or not the entry is still visible. */
    @PostMapping("/account!unsave.rol")
    public String unsave(@RequestParam(name = "entry", required = false) String entryId,
                         HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        try {
            readers.unsave(account, StringUtils.defaultString(entryId));
            addFlashMessage(redirectAttributes, "account.saved.removed", request);
        } catch (WebloggerException ex) {
            log.error("Could not remove a save for {}", account.getUserName(), ex);
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + SAVED;
    }

    /** Following's Unfollow, by weblog id: a follow of a blog hidden since is removable too. */
    @PostMapping("/account!unfollow.rol")
    public String unfollow(@RequestParam(name = "weblog", required = false) String weblogId,
                           HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        try {
            readers.unfollow(account, StringUtils.defaultString(weblogId));
            addFlashMessage(redirectAttributes, "account.following.removed", request);
        } catch (WebloggerException ex) {
            log.error("Could not remove a follow for {}", account.getUserName(), ex);
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + FOLLOWING;
    }

    /** Reading history's "Clear history". */
    @PostMapping("/account!clearHistory.rol")
    public String clearHistory(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        try {
            readers.clearHistory(account);
            addFlashMessage(redirectAttributes, "account.history.cleared", request);
        } catch (WebloggerException ex) {
            log.error("Could not clear the reading history of {}", account.getUserName(), ex);
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + HISTORY;
    }

    /**
     * "Keep my reading history": on when the box is ticked ({@code keepHistory}
     * sent), off when it is not. Off clears the history first and then saves
     * the switch, so a clear that fails leaves both as they were; the beacon
     * then records nothing ({@link ReaderStateService#recordRead}). A save
     * that fails is rolled back and reported.
     */
    @PostMapping("/account!saveHistorySetting.rol")
    public String saveHistorySetting(@RequestParam(name = "keepHistory", required = false) String keepHistory,
                                     HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        boolean on = keepHistory != null;
        try {
            if (!on) {
                // Cleared first: a clear that fails leaves the switch on over the history it kept.
                readers.clearHistory(account);
            }
            account.setKeepHistory(on);
            weblogger.getUserManager().saveUser(account);
            weblogger.flush();
            log.info("account {} turned its reading history {}", account.getUserName(), on ? "on" : "off");
            addFlashMessage(redirectAttributes, "account.history.saved", request);
        } catch (WebloggerException ex) {
            log.error("Could not save the history setting of {}", account.getUserName(), ex);
            weblogger.release();
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + HISTORY;
    }

    /**
     * Following's "Email me new posts": the account's own email to the blog's
     * listmonk list ({@link ReaderStateService#subscribe}). Every outcome is a
     * calm line on the Following section: listmonk being down never fails the
     * page (Review Focus 4). Counted against the account's reader-state write
     * limit, since each one reaches listmonk.
     */
    @PostMapping("/account!subscribe.rol")
    public String subscribe(@RequestParam(name = "weblog", required = false) String weblogId,
                            HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        try {
            if (!readers.admitWrite(account)) {
                addFlashError(redirectAttributes, "account.following.subscribe.unavailable", request);
                return BACK + FOLLOWING;
            }
            Weblog weblog = StringUtils.isBlank(weblogId) ? null : weblogger.getWeblogManager().getWeblog(weblogId);
            switch (readers.subscribe(account, weblog)) {
                case CONFIRM -> addFlashMessage(redirectAttributes, "account.following.subscribe.confirm", request);
                case ALREADY -> addFlashMessage(redirectAttributes, "account.following.subscribe.already", request);
                case UNAVAILABLE -> addFlashError(redirectAttributes, "account.following.subscribe.unavailable", request);
            }
        } catch (ReaderStateRefusal refusal) {
            addFlashError(redirectAttributes, "account.following.subscribe.notOffered", request);
        } catch (WebloggerException ex) {
            log.error("Could not look up a weblog to subscribe {} to", account.getUserName(), ex);
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK + FOLLOWING;
    }

    @PostMapping("/account!deleteComment.rol")
    public String deleteComment(@RequestParam(name = "id", required = false) String id,
                                HttpServletRequest request, RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        try {
            if (comments.deleteOwn(account, id)) {
                addFlashMessage(redirectAttributes, "account.comment.deleted", request);
            } else {
                addFlashError(redirectAttributes, "account.comment.notFound", request);
            }
        } catch (WebloggerException ex) {
            // Not the id: it is a request parameter, and a CR/LF in it would forge log lines.
            log.error("Could not delete a comment for {}", account.getUserName(), ex);
            addFlashError(redirectAttributes, STORE_ERROR, request);
        }
        return BACK;
    }

    /**
     * Deletes the account, its comments and the replies to them, its roles and
     * tokens, then ends the session. Refused for a weblog member, for an
     * administrator, and for an account that wrote entries (ruling R8).
     */
    @PostMapping("/account!deleteAccount.rol")
    public String deleteAccount(HttpServletRequest request, HttpServletResponse response,
                                RedirectAttributes redirectAttributes) {
        User account = getAuthenticatedUser(request);
        String userName = account.getUserName();
        List<Weblog> shown;
        try {
            String refusal = deletionRefusal(account);
            if (refusal != null) {
                addFlashError(redirectAttributes, refusal, request);
                return BACK;
            }
            // Before the removal: afterwards there are no comments to find them by.
            shown = weblogger.getCommentManager().getWeblogsWithPublishedCommentsBy(account);
            weblogger.getUserManager().removeUser(account);
            weblogger.flush();
        } catch (WebloggerException ex) {
            log.error("Could not delete the account {}", userName, ex);
            weblogger.release();
            addFlashError(redirectAttributes, STORE_ERROR, request);
            return BACK;
        }
        log.info("account {} deleted by its holder", userName);
        comments.expireAll(shown);
        endSession(request, response, userName);
        addFlashMessage(redirectAttributes, "account.deleted", request);
        return "redirect:/";
    }

    // ------------------------------------------------------------- rules

    /** The message key refusing deletion of this account, or null when it may go. */
    private String deletionRefusal(User account) throws WebloggerException {
        UserManager users = weblogger.getUserManager();
        if (!users.getWeblogPermissions(account).isEmpty()) {
            return "account.delete.member";
        }
        if (users.checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), account)) {
            return "account.delete.admin";
        }
        // Entries name their creator by username with no foreign key: deleting a
        // writer would orphan the bylines and hand them to the next account of that name.
        if (weblogger.getWeblogEntryManager().hasEntriesCreatedBy(account)) {
            return "account.delete.author";
        }
        return null;
    }

    /**
     * The account as stored, after a failed save was rolled back; null when
     * even that cannot be read, and the page then shows no name at all.
     */
    private User asStored(User account) {
        try {
            return weblogger.getUserManager().getUserByUserName(account.getUserName());
        } catch (WebloggerException ex) {
            log.error("Could not re-read the account {}", account.getUserName(), ex);
            return null;
        }
    }

    /** Saves the name (and a full name that was the old screen name), then expires the weblogs that show it. */
    private void rename(User account, String name) throws WebloggerException {
        List<Weblog> shown = weblogger.getCommentManager().getWeblogsWithPublishedCommentsBy(account);
        if (Objects.equals(account.getFullName(), account.getScreenName())) {
            account.setFullName(name);
        }
        account.setScreenName(name);
        weblogger.getUserManager().saveUser(account);
        weblogger.flush();
        log.info("account {} renamed", account.getUserName());
        comments.expireAll(shown);
    }

    /**
     * Signs the browser out, as {@code logout-redirect.jsp} does: the session
     * goes, the security context is cleared, and any remember-me cookie is
     * cancelled so that it cannot try to sign the deleted account back in.
     */
    @SuppressFBWarnings(
            value = {"HTTPONLY_COOKIE", "INSECURE_COOKIE"},
            justification = "This cookie only cancels the remember-me cookie: no value and Max-Age 0, so "
                    + "the browser deletes it and holds nothing a script or a plain-http hop could read.")
    private static void endSession(HttpServletRequest request, HttpServletResponse response, String userName) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PressroomSession.PRESSROOM_SESSION);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        PressroomLoginSessionManager.getInstance().invalidate(userName);
        Cookie cancel = new Cookie(TokenBasedRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY, null);
        String contextPath = request.getContextPath();
        cancel.setPath(contextPath == null || contextPath.isEmpty() ? "/" : contextPath);
        cancel.setMaxAge(0);
        response.addCookie(cancel);
    }

    // ------------------------------------------------------------- the model

    /**
     * The page: the account, the name in the form, the account's comments
     * (newest first, without the tombstones of hidden ones it deleted) and why
     * it may not be deleted, if it may not. A display path: what cannot be
     * read is shown empty, with an error (a null account, one that could not
     * be re-read, shows nothing); the actions re-check for themselves.
     */
    private void show(User account, String nameValue, HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("account", account);
        model.addAttribute("nameValue", nameValue);
        model.addAttribute("comments", List.of());
        model.addAttribute("saved", List.of());
        model.addAttribute("following", List.of());
        model.addAttribute("history", List.of());
        model.addAttribute("newsletterOffered", Map.of());
        model.addAttribute("entryTitles", Map.of());
        if (account == null) {
            return;
        }
        model.addAttribute("notifyReplies", account.isNotifyReplies());
        model.addAttribute("keepHistory", account.isKeepHistory());
        List<WeblogEntry> labelled = new ArrayList<>();
        try {
            ReaderLists lists = readers.lists(account, EnumSet.of(ReaderList.SAVED, ReaderList.FOLLOWING), LIST_SHOWN);
            List<WeblogEntry> history = readers.lists(account, EnumSet.of(ReaderList.HISTORY), HISTORY_SHOWN).history();
            Map<String, Boolean> offered = new LinkedHashMap<>();
            for (Weblog w : lists.following()) {
                offered.put(w.getId(), readers.newsletterOffered(account, w));
            }
            model.addAttribute("saved", lists.saved());
            model.addAttribute("following", lists.following());
            model.addAttribute("history", history);
            model.addAttribute("newsletterOffered", offered);
            labelled.addAll(lists.saved());
            labelled.addAll(history);
        } catch (WebloggerException ex) {
            log.error("Could not load the reader lists of {}", account.getUserName(), ex);
            addError(model, STORE_ERROR, request);
        }
        // The comments and the delete refusal do not depend on the reader state, so a failure there
        // degrades only those lists above (0.17.1 showed comments regardless).
        try {
            List<ReaderComment> comments = weblogger.getCommentManager().getCommentsByAuthor(account).stream()
                    .filter(c -> !CommentRules.isTombstone(c))
                    .toList();
            model.addAttribute("comments", comments);
            comments.stream().map(ReaderComment::getEntry).filter(Objects::nonNull).forEach(labelled::add);
        } catch (WebloggerException ex) {
            log.error("Could not load the comments of {}", account.getUserName(), ex);
            addError(model, STORE_ERROR, request);
        }
        try {
            model.addAttribute("deleteRefusal", deletionRefusal(account));
        } catch (WebloggerException ex) {
            // Fail closed: a refusal that cannot be worked out hides the Delete button (the action re-checks).
            log.error("Could not work out whether {} may be deleted", account.getUserName(), ex);
            model.addAttribute("deleteRefusal", STORE_ERROR);
        }
        // Each listed entry, labelled once through fn:escapeXml (EntryListView.plainTitles).
        model.addAttribute("entryTitles", EntryListView.plainTitles(labelled));
    }
}
