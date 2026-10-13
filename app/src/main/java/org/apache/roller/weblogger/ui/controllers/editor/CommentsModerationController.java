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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.comments.CommentOutcome;
import org.apache.roller.weblogger.business.comments.CommentRefusal;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reader-comment moderation page: three tabs by status, approve / hide /
 * delete on one row or many, and an owner's reply.
 *
 * <p><b>Ownership.</b> The interceptor vouches only for the action weblog
 * (weblog {@code POST} permission), and comment ids are client input fetched
 * by a global by-id lookup. Every id is therefore checked against the action
 * weblog before {@link CommentService} (which trusts its caller) is asked to
 * act; a foreign or unknown id is skipped and not counted.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class CommentsModerationController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CommentsModerationController.class);
    // 30 per page: a normal queue is one or two pages, and the page size bounds what one request loads.
    private static final int COUNT = 30;
    /** The last page a request may ask for: page * COUNT stays far below int overflow. */
    static final int MAX_PAGE = 100_000;

    private final CommentService comments;

    public CommentsModerationController(CommentService comments) {
        this.comments = comments;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "comments";
    }

    @Override
    public String getPageTitle() {
        return "comments.title";
    }

    @GetMapping("/comments.rol")
    public String execute(@RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                          @RequestParam(name = "entry", required = false) String entryId,
                          HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        load(request, model, parse(status), page, entryId);
        return ".Comments";
    }

    @PostMapping("/comments!approve.rol")
    public String approve(@RequestParam(name = "ids", required = false) String[] ids,
                          @RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "entry", required = false) String entryId,
                          @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                          HttpServletRequest request, Model model) {
        return moveAll(ids, status, entryId, page, Status.PUBLISHED, "comments.approved", request, model);
    }

    @PostMapping("/comments!hide.rol")
    public String hide(@RequestParam(name = "ids", required = false) String[] ids,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "entry", required = false) String entryId,
                       @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                       HttpServletRequest request, Model model) {
        return moveAll(ids, status, entryId, page, Status.HIDDEN, "comments.hidden", request, model);
    }

    @PostMapping("/comments!delete.rol")
    public String delete(@RequestParam(name = "ids", required = false) String[] ids,
                         @RequestParam(name = "status", required = false) String status,
                         @RequestParam(name = "entry", required = false) String entryId,
                         @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                         HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        User actor = getAuthenticatedUser(request);
        int done = 0;
        try {
            for (ReaderComment c : owned(ids, request)) {
                comments.delete(c, actor);
                done++;
            }
            addMessage(model, "comments.deleted", Integer.toString(done), request);
        } catch (WebloggerException ex) {
            log.error("Error deleting comments", ex);
            addError(model, "generic.error.check.logs", request);
        }
        load(request, model, parse(status), page, entryId);
        return ".Comments";
    }

    @PostMapping("/comments!reply.rol")
    public String reply(@RequestParam(name = "id") String id,
                        @RequestParam(name = "body") String body,
                        @RequestParam(name = "status", required = false) String status,
                        @RequestParam(name = "entry", required = false) String entryId,
                        @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                        HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        try {
            List<ReaderComment> mine = owned(new String[] {id}, request);
            CommentOutcome outcome = mine.isEmpty() ? null
                    : comments.reply(getAuthenticatedUser(request), mine.get(0), body);
            if (outcome != null && outcome.ok()) {
                addMessage(model, "comments.replied", request);
            } else if (outcome != null && outcome.refusal() == CommentRefusal.RATE_LIMITED) {
                // An owner's replies count toward the per-account limit too.
                addError(model, "comments.reply.rateLimited", request);
            } else {
                addError(model, "comments.reply.refused", request);
            }
        } catch (WebloggerException ex) {
            // The id is a raw request parameter: never logged (a CR/LF in it could forge a line).
            log.error("Error replying to a comment on weblog {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
        load(request, model, parse(status), page, entryId);
        return ".Comments";
    }

    private String moveAll(String[] ids, String status, String entryId, int page, Status target, String messageKey,
                           HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        User actor = getAuthenticatedUser(request);
        int done = 0;
        try {
            for (ReaderComment c : owned(ids, request)) {
                // The service refuses a move it forbids (publishing a reader-deleted
                // tombstone); a refused one is not counted.
                if (comments.setStatus(c, target, actor)) {
                    done++;
                }
            }
            addMessage(model, messageKey, Integer.toString(done), request);
        } catch (WebloggerException ex) {
            log.error("Error moderating comments", ex);
            addError(model, "generic.error.check.logs", request);
        }
        load(request, model, parse(status), page, entryId);
        return ".Comments";
    }

    /** The ids that name a comment on the action weblog, in order; the rest are skipped. */
    private List<ReaderComment> owned(String[] ids, HttpServletRequest request) throws WebloggerException {
        List<ReaderComment> found = new java.util.ArrayList<>();
        if (ids == null) {
            return found;
        }
        Weblog actionWeblog = getActionWeblog(request);
        CommentManager mgr = weblogger.getCommentManager();
        for (String id : ids) {
            ReaderComment c = mgr.getComment(id);
            if (c != null && c.getEntry() != null && actionWeblog.equals(c.getEntry().getWebsite())) {
                found.add(c);
            } else if (c != null) {
                // A stored comment's id, not the raw parameter.
                log.warn("Refusing comment {}: not on weblog {}", c.getId(), actionWeblog.getHandle());
            } else {
                // The raw parameter is never logged: a CR/LF in it could forge a line.
                log.warn("Refusing an unknown comment id on weblog {}", actionWeblog.getHandle());
            }
        }
        return found;
    }

    private static Status parse(String status) {
        if (status != null) {
            for (Status s : Status.values()) {
                if (s.name().equals(status)) {
                    return s;
                }
            }
        }
        return Status.PENDING;
    }

    /** The page as asked, held to 0..MAX_PAGE: it is client input, multiplied into an offset. */
    static int clampPage(int page) {
        return Math.clamp(page, 0, MAX_PAGE);
    }

    private void load(HttpServletRequest request, Model model, Status status, int requestedPage, String entryId) {
        Weblog actionWeblog = getActionWeblog(request);
        int page = clampPage(requestedPage);
        model.addAttribute("status", status.name());
        model.addAttribute("page", page);
        try {
            WeblogEntry entry = null;
            if (entryId != null && !entryId.isEmpty()) {
                WeblogEntry found = weblogger.getWeblogEntryManager().getWeblogEntry(entryId);
                if (found != null && actionWeblog.equals(found.getWebsite())) {
                    entry = found;
                    model.addAttribute("entryFilter", entryId);
                }
            }
            CommentManager mgr = weblogger.getCommentManager();
            model.addAttribute("comments", mgr.getComments(actionWeblog, status, entry, page * COUNT, COUNT));
            // What the list shows, counted: the pager's "Older" link reads it.
            model.addAttribute("commentCount", mgr.countComments(actionWeblog, status, entry));
        } catch (WebloggerException ex) {
            log.error("Error getting comments for weblog - {}", actionWeblog.getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
    }
}
