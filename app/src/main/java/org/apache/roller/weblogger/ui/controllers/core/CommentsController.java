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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.comments.CommentOutcome;
import org.apache.roller.weblogger.business.comments.CommentRefusal;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.security.sso.SsoPaths;
import org.apache.roller.weblogger.ui.core.security.sso.SsoSettings;
import org.apache.roller.weblogger.util.URLUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The three endpoints the page script builds the comment form from: the
 * session answer, posting, and a reader deleting their own comment. Every
 * rule lives in {@link CommentService}; this class maps its answers to HTTP.
 *
 * <p><b>Who is signed in.</b> The account is the one
 * {@code RollerHandlerInterceptor} resolves, which is only ever an enabled
 * one. A request that carries a signed-in principal but resolves to no
 * account is an account disabled (or removed) since it signed in: it is
 * refused as {@code disabled}, never treated as anonymous.
 *
 * <p><b>CSRF.</b> There is no exemption: {@code /roller-ui/rendering/**} is
 * {@code permitAll}, and the UI chain's CSRF filter refuses a POST or DELETE
 * without the token (403) before it gets here. The session answer hands the
 * signed-in reader that token.
 *
 * <p><b>Wire codes.</b> A refusal travels as its {@link CommentRefusal} name
 * in lower case with hyphens ({@code CUSTOM_DOMAIN} is {@code custom-domain}):
 * the session's {@code reason} and a 400's {@code error}. The page script
 * reads them, so a renamed constant is a changed contract.
 */
@Controller
@RequestMapping("/roller-ui/rendering")
public class CommentsController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger("roller.comments");

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private final CommentService comments;
    private final SsoSettings sso;

    /**
     * @param comments every posting and deleting rule
     * @param sso      whether a sign-in link can be offered at all
     */
    public CommentsController(CommentService comments, SsoSettings sso) {
        this.comments = comments;
        this.sso = sso;
    }

    // ------------------------------------------------------------- security
    //
    // Anonymous readers ask for the session answer, so the interceptor must
    // not send them to the login page. Each handler does its own sign-in check.

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    // ------------------------------------------------------------- endpoints

    /**
     * What the form should show for this entry, for whoever is asking. Never
     * cached: it carries the reader's own CSRF token and held comments. An
     * unknown or unpublished entry is a 404, so the answer reveals nothing.
     */
    @GetMapping("/comments/session.rol")
    public ResponseEntity<Map<String, Object>> session(
            @RequestParam(value = "entry", required = false) String entryId, HttpServletRequest request) {
        try {
            WeblogEntry entry = publishedEntry(entryId);
            if (entry == null) {
                return noStore(HttpStatus.NOT_FOUND).build();
            }
            User user = getAuthenticatedUser(request);
            if (user == null) {
                return noStore(HttpStatus.OK).body(signedInWithoutAccount()
                        ? cannotPost(CommentRefusal.DISABLED)
                        : anonymous(request, entry));
            }
            CommentRefusal why = comments.whyNot(user, entry);
            if (why != null) {
                return noStore(HttpStatus.OK).body(cannotPost(why));
            }
            if (!(request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf)) {
                // Without a token the post could never pass, so the form is not offered.
                log.error("no CSRF token on the comment session request for {}", user.getUserName());
                return noStore(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            return noStore(HttpStatus.OK).body(canPost(request, user, entry, csrf));
        } catch (WebloggerException ex) {
            log.error("the comment session could not be answered", ex);
            return noStore(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Posts a comment, or an owner's reply when {@code replyTo} is given. */
    @PostMapping(value = "/comments.rol", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> post(@RequestBody CommentPayload payload,
            HttpServletRequest request) {
        User user = getAuthenticatedUser(request);
        if (user == null) {
            return ResponseEntity.status(signedInWithoutAccount()
                    ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED).build();
        }
        try {
            CommentOutcome out = comments.post(user, payload.entry(), payload.body(),
                    StringUtils.trimToNull(payload.replyTo()));
            if (out.ok()) {
                ReaderComment c = out.comment();
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(Map.of("id", c.getId(), "status", c.getStatus().name()));
            }
            return refused(out.refusal());
        } catch (WebloggerException ex) {
            log.error("a comment by {} could not be posted", user.getUserName(), ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Deletes the caller's own comment, and its reply; anyone else's is a 404. */
    @DeleteMapping("/comments/{id}.rol")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, HttpServletRequest request) {
        User user = getAuthenticatedUser(request);
        if (user == null) {
            // A disabled account deletes nothing, as the service would answer it.
            return ResponseEntity.status(signedInWithoutAccount()
                    ? HttpStatus.NOT_FOUND : HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return comments.deleteOwn(user, id)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (WebloggerException ex) {
            log.error("a comment delete by {} failed", user.getUserName(), ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ------------------------------------------------------------- answers

    private Map<String, Object> anonymous(HttpServletRequest request, WeblogEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signedIn", false);
        if (sso.enabled()) {
            body.put("signInUrl", request.getContextPath() + SsoPaths.START + "?return="
                    + URLEncoder.encode(entryPath(request, entry), StandardCharsets.UTF_8));
        }
        return body;
    }

    private static Map<String, Object> cannotPost(CommentRefusal why) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signedIn", true);
        body.put("canPost", false);
        body.put("reason", code(why));
        return body;
    }

    private Map<String, Object> canPost(HttpServletRequest request, User user, WeblogEntry entry, CsrfToken csrf)
            throws WebloggerException {
        CommentManager store = weblogger.getCommentManager();
        List<Map<String, String>> pending = new ArrayList<>();
        for (ReaderComment c : store.getPendingForEntryAndAuthor(entry, user)) {
            pending.add(Map.of("id", c.getId(), "body", c.getBody(),
                    "created", c.getCreated().toInstant().toString()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signedIn", true);
        body.put("canPost", true);
        body.put("displayName", user.getScreenName());
        body.put("isOwner", comments.isOwner(user, entry.getWebsite()));
        body.put("csrf", Map.of("header", csrf.getHeaderName(), "token", csrf.getToken()));
        body.put("accountUrl", request.getContextPath() + "/roller-ui/account.rol");
        body.put("pending", pending);
        // Ruling R7: the cached page names no commenter, so the script finds the
        // caller's own comments (to offer Delete) by these ids, not by username.
        body.put("ownPublished", store.getPublishedIdsForEntryAndAuthor(entry, user));
        return body;
    }

    /** The HTTP answer to a refused post: the spec's status, and a code for a 400. */
    private static ResponseEntity<Map<String, String>> refused(CommentRefusal why) {
        return switch (why) {
            case NOT_SIGNED_IN -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case DISABLED, NO_PERMISSION -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case NOT_FOUND, CLOSED, COMMENTS_OFF, CUSTOM_DOMAIN -> ResponseEntity.notFound().build();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case EMPTY_BODY, BODY_TOO_LONG, BAD_REPLY -> ResponseEntity.badRequest().body(Map.of("error", code(why)));
        };
    }

    // ------------------------------------------------------------- helpers

    /** The entry when it exists and is published, else null. */
    private WeblogEntry publishedEntry(String entryId) throws WebloggerException {
        if (StringUtils.isBlank(entryId)) {
            return null;
        }
        WeblogEntry entry = weblogger.getWeblogEntryManager().getWeblogEntry(entryId);
        return entry != null && entry.getStatus() == PubStatus.PUBLISHED ? entry : null;
    }

    /**
     * The entry's path on this site, under the request's context path, which is
     * what the sign-in start step accepts as a return path.
     */
    private static String entryPath(HttpServletRequest request, WeblogEntry entry) {
        return request.getContextPath() + "/" + entry.getWebsite().getHandle() + "/entry/"
                + URLUtilities.encode(entry.getAnchor());
    }

    /**
     * True when a principal is signed in but the interceptor found no enabled
     * account for it: the account was disabled or removed since sign-in.
     */
    private static boolean signedInWithoutAccount() {
        return TRUST.isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
    }

    /** A refusal's wire code: its name in lower case, words joined by hyphens. */
    static String code(CommentRefusal why) {
        return why.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }

    /** The JSON body the page script posts. */
    record CommentPayload(String entry, String body, String replyTo) {
    }
}
