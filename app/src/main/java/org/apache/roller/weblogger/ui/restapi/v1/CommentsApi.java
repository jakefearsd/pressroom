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
package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.comments.CommentOutcome;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.CommentDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Moderation of signed-in readers' comments. Authorization is the handler
 * interceptor's weblog {@code POST} check; every by-id lookup then goes
 * through {@link WeblogOwnership#comment}, so a comment of another weblog
 * and a missing one are both 404. Comments stay moderatable with comments
 * switched off on the weblog. The token's user is the actor, and replies are
 * written as that user.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/comments")
public class CommentsApi extends BaseApiController implements UISecurityEnforced {

    private static final int MAX_LIMIT = 200;

    @Autowired
    @Lazy
    CommentService commentService;

    @GetMapping("")
    public CommentDtos.CommentPage list(HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "entry", required = false) String entryId,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        if (limit < 1 || offset < 0) {
            throw ApiException.badRequest("limit must be at least 1 and offset must not be negative.");
        }
        Weblog weblog = requireActionWeblog(request);
        Status wanted = null;
        if (StringUtils.isNotBlank(status)) {
            wanted = parse(status);
        }
        WeblogEntry entry = null;
        if (entryId != null) {
            entry = requireEntry(request, entryId);
        }
        int bounded = Math.min(limit, MAX_LIMIT);
        // One extra row decides hasMore without a count query.
        List<ReaderComment> found = weblogger.getCommentManager()
                .getComments(weblog, wanted, entry, offset, bounded + 1);
        boolean hasMore = found.size() > bounded;
        List<CommentDtos.CommentView> items = (hasMore ? found.subList(0, bounded) : found).stream()
                .map(CommentDtos::toView).toList();
        return new CommentDtos.CommentPage(items, offset, bounded, hasMore);
    }

    @GetMapping("/{id}")
    public CommentDtos.CommentView get(HttpServletRequest request, @PathVariable("id") String id) {
        return CommentDtos.toView(requireComment(request, id));
    }

    @PatchMapping("/{id}")
    public CommentDtos.CommentView update(HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody CommentDtos.CommentPatch body) throws WebloggerException {
        ReaderComment comment = requireComment(request, id);
        Status wanted = body == null || StringUtils.isBlank(body.status()) ? null : parse(body.status());
        if (wanted != Status.PUBLISHED && wanted != Status.HIDDEN) {
            throw ApiException.badRequest("status must be PUBLISHED or HIDDEN.");
        }
        if (!commentService.setStatus(comment, wanted, requireAuthenticatedUser(request))) {
            // The service's one refusal: a reader-deleted tombstone is never published.
            throw ApiException.conflict("Its author deleted this comment; it cannot be published.");
        }
        return CommentDtos.toView(comment);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        ReaderComment comment = requireComment(request, id);
        commentService.delete(comment, requireAuthenticatedUser(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<CommentDtos.CommentView> reply(HttpServletRequest request,
            @PathVariable("id") String id, @RequestBody CommentDtos.CommentReply body)
            throws WebloggerException {
        ReaderComment parent = requireComment(request, id);
        User actor = requireAuthenticatedUser(request);
        CommentOutcome outcome = commentService.reply(actor, parent, body == null ? null : body.body());
        if (!outcome.ok()) {
            throw ApiException.badRequest("The reply was refused: " + outcome.refusal().name());
        }
        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getRequestURI().replaceFirst("/[^/]+/replies$", "/" + outcome.comment().getId()))
                .build().toUri();
        return ResponseEntity.created(location).body(CommentDtos.toView(outcome.comment()));
    }

    private static Status parse(String value) {
        try {
            return Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("status must be one of PENDING, PUBLISHED or HIDDEN.", ex);
        }
    }

    private ReaderComment requireComment(HttpServletRequest request, String id) {
        ReaderComment comment = WeblogOwnership.comment(weblogger, id, requireActionWeblog(request));
        if (comment == null) {
            throw ApiException.notFound("No such comment.");
        }
        return comment;
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
