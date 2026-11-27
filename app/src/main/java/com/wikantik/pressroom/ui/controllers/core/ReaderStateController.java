/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.reader.ReaderList;
import com.wikantik.pressroom.business.reader.ReaderLists;
import com.wikantik.pressroom.business.reader.ReaderStateRefusal;
import com.wikantik.pressroom.business.reader.ReaderStateService;
import com.wikantik.pressroom.business.reader.ReaderVisibility;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.view.EntryListView;
import com.wikantik.pressroom.util.Initials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code /pressroom-ui/rendering/reader.rol}: a signed-in reader's saves,
 * follows and reading history, for the page script and the account page
 * (spec Part E). Every rule lives in {@link ReaderStateService}; this class
 * maps its answers to HTTP. Both verbs answer {@code no-store}.
 *
 * <p><b>GET</b> {@code ?entry=<id>&weblog=<handle>&include=<names>} answers
 * {@code {signedIn:false}} to anyone without a usable account, else
 * {@code {signedIn, name, initials, author, csrf}} plus only what was asked
 * for: {@code saved} with {@code entry}; {@code following} and
 * {@code newsletter:{available}} with {@code weblog}; {@code places},
 * {@code continueReading}, {@code followedPosts} and {@code readIds} for
 * {@code include} names {@code places}, {@code continue}, {@code followed}
 * and {@code readIds}. Names, titles and urls are data for
 * {@code textContent}; titles are plain text. Reads are not limited, as the
 * comment session is not.
 *
 * <p><b>POST</b> JSON {@code {action, entry?, weblog?}}, {@code entry} an id
 * and {@code weblog} a handle: {@code save}, {@code unsave}, {@code follow},
 * {@code unfollow}, {@code read}, {@code subscribe}. 401 signed out, 403 for
 * an account disabled since sign-in or without the global {@code comment}
 * action, 429 over the write limit (counted before anything else is
 * checked), 400 for an unknown action, a missing field or an unreadable body,
 * 415 for a body that is not JSON, 404 for an entry or weblog a reader may not
 * see. {@code unfollow} answers {@code 200 {following:false}} for any handle,
 * known or not: a hidden or inactive weblog's handle cannot be told from an
 * unknown one, and a follow made before a weblog went hidden can always be
 * removed.
 *
 * <p><b>Subscribe</b> is {@link ReaderStateService#subscribe}: 200
 * {@code confirm} or {@code already}, 503 {@code unavailable} (Review Focus 4:
 * a failure is calm and records nothing), 404 where no subscription can be
 * offered.
 *
 * <p><b>CSRF</b> is the UI chain's, as on {@code comments.rol}: a POST without
 * the token is refused (403) before it gets here, and the GET hands the
 * signed-in reader that token.
 */
@Controller
@RequestMapping("/pressroom-ui/rendering")
public class ReaderStateController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ReaderStateController.class);

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private static final String OUTCOME = "outcome";

    /** The {@code include} names and the lists they ask for. */
    private static final Map<String, ReaderList> INCLUDES = Map.of(
            "places", ReaderList.PLACES,
            "continue", ReaderList.CONTINUE,
            "followed", ReaderList.FOLLOWED_POSTS,
            "readIds", ReaderList.READ_IDS);

    private final ReaderStateService readers;

    /** @param readers every reader-state rule */
    public ReaderStateController(ReaderStateService readers) {
        this.readers = readers;
    }

    // ------------------------------------------------------------- security
    //
    // Anonymous readers ask for the state, so the interceptor must not send
    // them to the login page; each handler checks the account itself.

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

    /** The reader's state for this page; see the class comment for the shape. */
    @GetMapping("/reader.rol")
    public ResponseEntity<Map<String, Object>> state(
            @RequestParam(value = "entry", required = false) String entryId,
            @RequestParam(value = "weblog", required = false) String handle,
            @RequestParam(value = "include", required = false) String include,
            HttpServletRequest request) {
        try {
            User user = getAuthenticatedUser(request);
            if (!readers.mayUse(user)) {
                return noStore(HttpStatus.OK).body(Map.of("signedIn", false));
            }
            if (!(request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf)) {
                // Without a token no write could pass, so nothing is offered.
                log.error("no CSRF token on the reader state request for {}", user.getUserName());
                return noStore(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("signedIn", true);
            body.put("name", user.getScreenName());
            body.put("initials", initials(user.getScreenName()));
            body.put("author", isAuthor(user));
            body.put("csrf", Map.of("header", csrf.getHeaderName(), "token", csrf.getToken()));
            if (StringUtils.isNotBlank(entryId)) {
                body.put("saved", readers.isSaved(user, entryId));
            }
            if (StringUtils.isNotBlank(handle)) {
                Weblog weblog = visibleWeblog(handle);
                body.put("following", weblog != null && readers.isFollowing(user, weblog.getId()));
                body.put("newsletter", Map.of("available", readers.newsletterOffered(user, weblog)));
            }
            putLists(body, user, wanted(include));
            return noStore(HttpStatus.OK).body(body);
        } catch (WebloggerException ex) {
            log.error("the reader state could not be answered", ex);
            return noStore(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** One write; see the class comment for the actions and answers. */
    // No "consumes": the body converter refuses a non-JSON body inside the handler's call, where
    // refused() below can mark it no-store; a mapping condition would answer 415 before any handler.
    @PostMapping("/reader.rol")
    public ResponseEntity<Map<String, Object>> act(@RequestBody ReaderAction payload, HttpServletRequest request) {
        User user = getAuthenticatedUser(request);
        if (user == null) {
            return noStore(signedInWithoutAccount() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED).build();
        }
        try {
            if (!readers.mayUse(user)) {
                return noStore(HttpStatus.FORBIDDEN).build();
            }
            if (!readers.admitWrite(user)) {
                return noStore(HttpStatus.TOO_MANY_REQUESTS).build();
            }
            return perform(user, payload);
        } catch (ReaderStateRefusal refusal) {
            return noStore(HttpStatus.NOT_FOUND).build();
        } catch (WebloggerException ex) {
            log.error("a reader state write by {} failed", user.getUserName(), ex);
            return noStore(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ------------------------------------------------------------- writes

    private ResponseEntity<Map<String, Object>> perform(User user, ReaderAction payload)
            throws WebloggerException, ReaderStateRefusal {
        String entryId = StringUtils.trimToNull(payload.entry());
        String handle = StringUtils.trimToNull(payload.weblog());
        String action = StringUtils.defaultString(payload.action());
        boolean needsEntry = Set.of("save", "unsave", "read").contains(action);
        boolean needsWeblog = Set.of("follow", "unfollow", "subscribe").contains(action);
        if ((needsEntry && entryId == null) || (needsWeblog && handle == null) || (!needsEntry && !needsWeblog)) {
            return noStore(HttpStatus.BAD_REQUEST).build();
        }
        return switch (action) {
            case "save" -> {
                readers.save(user, weblogger.getWeblogEntryManager().getWeblogEntry(entryId));
                yield ok("saved", true);
            }
            case "unsave" -> {
                readers.unsave(user, entryId);
                yield ok("saved", false);
            }
            case "read" -> {
                readers.recordRead(user, weblogger.getWeblogEntryManager().getWeblogEntry(entryId));
                yield noStore(HttpStatus.OK).body(Map.of("ok", true));
            }
            case "follow" -> {
                readers.follow(user, visibleWeblog(handle));
                yield ok("following", true);
            }
            case "unfollow" -> {
                Weblog weblog = anyWeblog(handle);
                if (weblog != null) {
                    readers.unfollow(user, weblog.getId());
                }
                yield ok("following", false);
            }
            default -> subscribed(readers.subscribe(user, visibleWeblog(handle)));
        };
    }

    private static ResponseEntity<Map<String, Object>> subscribed(ReaderStateService.SubscribeOutcome outcome) {
        return switch (outcome) {
            case CONFIRM -> noStore(HttpStatus.OK).body(Map.of("ok", true, OUTCOME, "confirm"));
            case ALREADY -> noStore(HttpStatus.OK).body(Map.of("ok", true, OUTCOME, "already"));
            case UNAVAILABLE -> noStore(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(OUTCOME, "unavailable"));
        };
    }

    /** A body Spring could not read as a {@link ReaderAction}: 400, or 415 when it is not JSON. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Void> refused(Exception ex) {
        return noStore(ex instanceof HttpMediaTypeNotSupportedException
                ? HttpStatus.UNSUPPORTED_MEDIA_TYPE : HttpStatus.BAD_REQUEST).build();
    }

    // ------------------------------------------------------------- reads

    private void putLists(Map<String, Object> body, User user, Set<ReaderList> wanted) throws WebloggerException {
        if (wanted.isEmpty()) {
            return;
        }
        ReaderLists lists = readers.lists(user, wanted);
        URLStrategy urls = weblogger.getUrlStrategy();
        if (wanted.contains(ReaderList.PLACES)) {
            List<Map<String, Object>> places = new ArrayList<>();
            for (Weblog w : lists.places()) {
                places.add(Map.of("handle", w.getHandle(), "name", w.getName(),
                        "url", urls.getWeblogURL(w, null, false)));
            }
            body.put("places", places);
        }
        if (wanted.contains(ReaderList.CONTINUE)) {
            body.put("continueReading", entryRows(lists.continueReading(), urls, false));
        }
        if (wanted.contains(ReaderList.FOLLOWED_POSTS)) {
            body.put("followedPosts", entryRows(lists.followedPosts(), urls, true));
        }
        if (wanted.contains(ReaderList.READ_IDS)) {
            body.put("readIds", List.copyOf(lists.readIds()));
        }
    }

    private static List<Map<String, Object>> entryRows(List<WeblogEntry> entries, URLStrategy urls,
            boolean withPubTime) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeblogEntry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("title", EntryListView.plainTitle(e));
            row.put("url", urls.getWeblogEntryURL(e.getWebsite(), null, e.getAnchor(), false));
            row.put("weblogName", e.getWebsite().getName());
            if (withPubTime) {
                row.put("pubTime", e.getPubTime().toInstant().toString());
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------- helpers

    /** The lists the {@code include} names ask for; an unknown name is ignored. */
    private static Set<ReaderList> wanted(String include) {
        Set<ReaderList> wanted = EnumSet.noneOf(ReaderList.class);
        for (String name : StringUtils.split(StringUtils.defaultString(include), ',')) {
            ReaderList list = INCLUDES.get(name.trim());
            if (list != null) {
                wanted.add(list);
            }
        }
        return wanted;
    }

    /** The weblog with this handle when readers may see it, else null. */
    private Weblog visibleWeblog(String handle) throws WebloggerException {
        Weblog weblog = anyWeblog(handle);
        return ReaderVisibility.isVisible(weblog) ? weblog : null;
    }

    /**
     * The weblog with this handle, visible or not, or null. A string that
     * cannot be a handle is unknown, not an error: the lookup would throw.
     */
    private Weblog anyWeblog(String handle) throws WebloggerException {
        if (!Weblog.hasHandleCharacters(handle)) {
            return null;
        }
        return weblogger.getWeblogManager().getWeblogByHandle(handle, null);
    }

    /** The first letters of a name's first and last words ({@link Initials}). */
    static String initials(String name) {
        return Initials.of(name);
    }

    private static ResponseEntity<Map<String, Object>> ok(String key, boolean value) {
        return noStore(HttpStatus.OK).body(Map.of("ok", true, key, value));
    }

    /**
     * True when a principal is signed in but the interceptor found no enabled
     * account for it: the account was disabled or removed since sign-in.
     */
    private static boolean signedInWithoutAccount() {
        return TRUST.isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
    }

    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }

    /** The JSON body the page script and the account page post. */
    record ReaderAction(String action, String entry, String weblog) {
    }
}
