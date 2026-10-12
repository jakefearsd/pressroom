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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.business.comments.DisplayNames;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.ui.core.RollerSession;
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
 * The reader's account page: the display name, every comment the account has
 * written, and "Delete my account".
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
@RequestMapping("/roller-ui")
public class AccountController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger("roller.comments");

    private static final String VIEW = ".Account";
    private static final String BACK = "redirect:/roller-ui/account.rol";
    private static final String STORE_ERROR = "generic.error.check.logs";

    private final CommentService comments;

    /** @param comments deletes the account's own comments and expires the weblogs that show them */
    public AccountController(CommentService comments) {
        this.comments = comments;
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
        expireAll(shown);
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
        expireAll(shown);
    }

    /**
     * Expires each weblog, after the change has committed. One that fails is
     * logged and the rest still expire: the change itself already stands.
     */
    private void expireAll(List<Weblog> weblogs) {
        for (Weblog weblog : weblogs) {
            try {
                comments.expire(weblog);
            } catch (WebloggerException ex) {
                log.error("Could not expire weblog {}; its pages show the old comments until its next change",
                        weblog.getHandle(), ex);
            }
        }
    }

    /**
     * Signs the browser out, as {@code logout-redirect.jsp} does: the session
     * goes, the security context is cleared, and any remember-me cookie is
     * cancelled so that it cannot try to sign the deleted account back in.
     */
    private static void endSession(HttpServletRequest request, HttpServletResponse response, String userName) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(RollerSession.ROLLER_SESSION);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        RollerLoginSessionManager.getInstance().invalidate(userName);
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
        if (account == null) {
            return;
        }
        try {
            model.addAttribute("comments", weblogger.getCommentManager().getCommentsByAuthor(account).stream()
                    .filter(c -> !isTombstone(c))
                    .toList());
            model.addAttribute("deleteRefusal", deletionRefusal(account));
        } catch (WebloggerException ex) {
            log.error("Could not load the account page of {}", account.getUserName(), ex);
            addError(model, STORE_ERROR, request);
        }
    }

    /** A HIDDEN comment with no body: one its author deleted (ruling R5). */
    private static boolean isTombstone(ReaderComment c) {
        return c.getStatus() == Status.HIDDEN && (c.getBody() == null || c.getBody().isEmpty());
    }
}
