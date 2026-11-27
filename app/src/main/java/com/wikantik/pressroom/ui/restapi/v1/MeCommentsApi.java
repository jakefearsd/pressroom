package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.auth.WeblogScopeExempt;
import com.wikantik.pressroom.ui.restapi.dto.CommentDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cross-blog approval queue: readers' comments on every blog where the caller may moderate. The blogs are
 * the ones {@code GET /v1/me/weblogs} calls {@code canModerate} (the caller's own permission row implies
 * {@code POST}), so a site admin who is not a member of a blog sees nothing from it, and an {@code EDIT_DRAFT}
 * member's blog contributes nothing. Session-only (SA-18: readers' words and names stay out of a token's reach);
 * a token is refused before this runs, so the weblog pin needs no check here.
 * Approve, hide and delete are the per-blog routes of {@link CommentsApi}, called with a row's handle.
 */
@Tag(name = "Comment queue", description = "Comments awaiting approval across the caller's blogs (session only: "
        + "the admin app, never a token).")
@RestController
@RequestMapping("/v1/me")
public class MeCommentsApi {

    /** The queue's page ceiling: a screen's page, not a bulk export. */
    static final int MAX_LIMIT = 100;

    @Autowired
    @Lazy
    private Weblogger weblogger;

    @WeblogScopeExempt
    @SessionOnly
    @Operation(operationId = "meCommentsList", summary = "Comments across every blog the caller may moderate, "
            + "newest first (session only).")
    @GetMapping("/comments")
    public CommentDtos.QueuePage list(HttpServletRequest request,
            @RequestParam(value = "status", defaultValue = "PENDING") String status,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.authenticationRequired();
        }
        Paging.requireRange(limit, offset);
        ReaderComment.Status wanted = parse(status);
        int bounded = Math.min(limit, MAX_LIMIT);
        List<Weblog> blogs = moderated(user);
        var comments = weblogger.getCommentManager();
        // One extra row decides hasMore.
        List<ReaderComment> found = comments.getComments(blogs, wanted, offset, bounded + 1);
        long total = comments.countComments(blogs, wanted);
        boolean hasMore = found.size() > bounded;
        List<CommentDtos.QueueComment> items = new ArrayList<>();
        for (ReaderComment comment : hasMore ? found.subList(0, bounded) : found) {
            Weblog weblog = comment.getEntry().getWebsite();
            items.add(CommentDtos.toQueue(comment, Objects.toString(weblog.getName(), ""),
                    weblogger.getUrlStrategy().getWeblogEntryURL(weblog, null, comment.getEntry().getAnchor(), true)));
        }
        return new CommentDtos.QueuePage(items, offset, bounded, hasMore, total);
    }

    /** The caller's weblogs whose permission row implies POST: the {@code canModerate} rule of the main menu. */
    private List<Weblog> moderated(User user) throws WebloggerException {
        WeblogPermission moderate = new WeblogPermission();
        moderate.setActionsAsList(List.of(WeblogPermission.POST));
        List<Weblog> blogs = new ArrayList<>();
        for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(user)) {
            if (!permission.implies(moderate)) {
                continue;
            }
            Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(permission.getObjectId());
            if (weblog != null) {
                blogs.add(weblog);
            }
        }
        return blogs;
    }

    private static ReaderComment.Status parse(String value) {
        try {
            return ReaderComment.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.problemOnField("status", ProblemCode.COMMENT_STATUS_UNKNOWN, ex);
        }
    }
}
