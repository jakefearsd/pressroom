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

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.business.comments.CommentOutcome;
import com.wikantik.pressroom.business.comments.CommentRefusal;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.core.security.sso.ReturnPaths;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The comment form's three endpoints, driven through the real UI security
 * chain ({@link SecurityConfig}, CSRF on), the real
 * {@link PressroomHandlerInterceptor} and the real {@link CommentService} against
 * the database: spec criteria 11, 12, 13, 20, 22 (the endpoint half) and 23,
 * and the JSON contract the page script reads.
 *
 * <p>The context is hand-built for the reason {@code SecurityConfigTest}'s
 * javadoc gives. A reader signs in as the provider leaves them, with a
 * {@link PressroomOidcUser}; an owner with a password-account principal.
 */
class CommentsControllerTest {

    private static final String SESSION = "/pressroom-ui/rendering/comments/session.rol";
    private static final String POST = "/pressroom-ui/rendering/comments.rol";
    private static final String ISSUER = "https://issuer.test/comments-endpoints";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SsoSettings SSO = mock(SsoSettings.class);

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    private final List<String> userNames = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    private User owner;
    private User reader;
    private User other;
    private Weblog blog;
    private WeblogEntry entry;

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * The suite's tier, seen through a view that is not itself a bean to
         * dispose. The provider is a {@code DisposableBean}, whose
         * {@code destroy()} detaches the runtime config; Spring calls it on
         * context close whatever {@code destroyMethod} says, which left every
         * later test in the JVM reading null properties.
         */
        @Bean
        WebloggerProvider webloggerProvider() {
            return new WebloggerProvider() {
                @Override
                public boolean isBootstrapped() {
                    return TestUtils.provider().isBootstrapped();
                }

                @Override
                public void bootstrap() throws BootstrapException {
                    TestUtils.provider().bootstrap();
                }

                @Override
                public Weblogger getWeblogger() {
                    return TestUtils.weblogger();
                }
            };
        }

        /** As above: an inferred {@code shutdown()} would stop the tier for every later test. */
        @Bean(destroyMethod = "")
        Weblogger weblogger() {
            return TestUtils.weblogger();
        }

        @Bean
        SsoSettings ssoSettings() {
            return SSO;
        }

        @Bean
        CommentService commentService() {
            return new CommentService(TestUtils.weblogger(), comment -> { });
        }

        @Bean
        CommentsController commentsController(CommentService comments, SsoSettings sso) {
            return new CommentsController(comments, sso);
        }

        @Bean
        PressroomHandlerInterceptor pressroomHandlerInterceptor() {
            return new PressroomHandlerInterceptor(TestUtils.provider(), TestUtils.weblogger());
        }

        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor interceptor) {
            return new WebMvcConfig(interceptor);
        }
    }

    @BeforeAll
    static void setUpContext() throws Exception {
        TestUtils.setupWeblogger();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
        assertNotNull(WebloggerRuntimeConfig.getProperty("uploads.file.maxsize"),
                "closing this test's context must leave the suite's runtime config attached, "
                        + "or every later test in the JVM reads null properties");
    }

    @BeforeEach
    void setUp() throws Exception {
        when(SSO.enabled()).thenReturn(false);
        owner = TestUtils.setupUser("ccowner");
        userNames.add(owner.getUserName());
        blog = newWeblog("ccblog");
        entry = TestUtils.setupWeblogEntry("ccentry", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(owner));
        reader = newReader("reader-ccreader1", "Ann Reader");
        other = newReader("reader-ccreader2", "Bob Reader");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        for (String id : weblogIds) {
            TestUtils.teardownWeblog(id);
        }
        for (String name : userNames) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
        ControllerTestFixture.useDefaultWeblogger();
    }

    // ------------------------------------------------------------- the session (criterion 20)

    @Test
    void theSessionAnswersAnonymousWithASignInUrlOnlyWhenSsoIsOn() throws Exception {
        when(SSO.enabled()).thenReturn(true);
        MvcResult on = perform(get("/pressroom" + SESSION).contextPath("/pressroom").param("entry", entry.getId()));

        String entryPath = "/pressroom/ccblog/entry/ccentry";
        assertEquals(200, on.getResponse().getStatus());
        assertEquals("no-store", on.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("signedIn", false,
                        "signInUrl", "/pressroom/pressroom-ui/sso/start?return="
                                + URLEncoder.encode(entryPath, StandardCharsets.UTF_8)),
                json(on));
        assertEquals(entryPath, ReturnPaths.sanitize(entryPath, "/pressroom"),
                "the start step must accept the return path as it is, not send the reader to the root");
        assertNull(on.getRequest().getSession(false), "an anonymous visitor is given no session, and no token");

        when(SSO.enabled()).thenReturn(false);
        MvcResult off = perform(get(SESSION).param("entry", entry.getId()));
        assertEquals(200, off.getResponse().getStatus());
        assertEquals("no-store", off.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("signedIn", false), json(off), "with SSO off there is nothing to sign in with");
    }

    @Test
    void theSessionGivesAReaderTheirOwnPendingAndAToken() throws Exception {
        ReaderComment mine = service().post(reader, entry.getId(), "Mine, held", null).comment();
        ReaderComment theirs = service().post(other, entry.getId(), "Theirs, held", null).comment();
        TestUtils.endSession(true);
        assertEquals(Status.PENDING, mine.getStatus(), "fixture: a new reader is held");

        MvcResult result = perform(get(SESSION).param("entry", entry.getId()).with(as(reader)));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        Map<String, Object> session = json(result);
        assertEquals(Set.of("signedIn", "canPost", "displayName", "isOwner", "csrf",
                "accountUrl", "pending", "ownPublished"), session.keySet());
        assertEquals(true, session.get("signedIn"));
        assertEquals(true, session.get("canPost"));
        assertEquals("Ann Reader", session.get("displayName"));
        assertFalse(body(result).contains(reader.getUserName()), "no username: the script matches by id (R7)");
        assertEquals(false, session.get("isOwner"));
        assertEquals("/pressroom-ui/account.rol", session.get("accountUrl"));
        Map<?, ?> token = (Map<?, ?>) session.get("csrf");
        assertEquals("X-CSRF-TOKEN", token.get("header"));
        assertFalse(((String) token.get("token")).isBlank());

        List<?> pending = (List<?>) session.get("pending");
        assertEquals(1, pending.size(), "only this reader's own held comment: " + pending);
        Map<?, ?> held = (Map<?, ?>) pending.get(0);
        assertEquals(Set.of("id", "body", "created"), held.keySet());
        assertEquals(mine.getId(), held.get("id"));
        assertEquals("Mine, held", held.get("body"));
        assertEquals(mine.getCreated().toInstant(), Instant.parse((String) held.get("created")));

        String raw = body(result);
        assertFalse(raw.contains(theirs.getId()), "another reader's comment never appears");
        assertFalse(raw.contains("Theirs, held"));
        assertFalse(raw.contains("@example.com"), "no email address, the reader's or anyone's");

        Map<String, Object> ownerSession = json(perform(get(SESSION).param("entry", entry.getId())
                .with(user(owner.getUserName()))));
        assertEquals(true, ownerSession.get("isOwner"));
        assertEquals(List.of(), ownerSession.get("pending"));
    }

    /**
     * Ruling R7: the cached page carries no usernames, so the session answer
     * names the caller's own PUBLISHED comments on the entry by id, and the
     * page script offers Delete on exactly those.
     */
    @Test
    void theSessionListsTheCallersOwnPublishedCommentsAndRepliesById() throws Exception {
        ReaderComment mine = service().post(reader, entry.getId(), "Approve me", null).comment();
        ReaderComment held = service().post(reader, entry.getId(), "Still held", null).comment();
        ReaderComment theirs = service().post(other, entry.getId(), "Approve them", null).comment();
        TestUtils.endSession(true);
        service().setStatus(comments().getComment(mine.getId()), Status.PUBLISHED, owner);
        service().setStatus(comments().getComment(theirs.getId()), Status.PUBLISHED, owner);
        TestUtils.endSession(true);
        ReaderComment reply = service().post(owner, entry.getId(), "Thanks", mine.getId()).comment();
        TestUtils.endSession(true);
        assertEquals(Status.PENDING, comments().getComment(held.getId()).getStatus(),
                "fixture: one comment still held");

        Map<String, Object> readerSession = json(perform(get(SESSION).param("entry", entry.getId())
                .with(as(reader))));
        assertEquals(List.of(mine.getId()), readerSession.get("ownPublished"),
                "their own published comment only: not another reader's, not their held one");

        Map<String, Object> ownerSession = json(perform(get(SESSION).param("entry", entry.getId())
                .with(user(owner.getUserName()))));
        assertEquals(List.of(reply.getId()), ownerSession.get("ownPublished"), "an owner's reply is theirs");

        Map<String, Object> otherEntry = json(perform(get(SESSION).param("entry", newEntry(blog, "ccelse").getId())
                .with(as(reader))));
        assertEquals(List.of(), otherEntry.get("ownPublished"), "this entry's only");
    }

    @Test
    void theSessionGivesReasonsForThoseWhoCannotPost() throws Exception {
        WeblogEntry closed = newEntry(blog, "ccclosed");
        setCommentsClosed(closed);
        assertReason("closed", reader, closed);

        Weblog off = newWeblog("ccoff");
        setCommentsEnabled(off, false);
        assertReason("comments-off", reader, newEntry(off, "ccoffentry"));

        Weblog custom = newWeblog("cccustom");
        setCustomDomain(custom, "comments-endpoints.example.test");
        assertReason("custom-domain", reader, newEntry(custom, "cccustomentry"));

        User roleless = newReader("reader-ccroleless", "No Role");
        TestUtils.weblogger().getUserManager().revokeRole("reader", TestUtils.getManagedUser(roleless));
        TestUtils.endSession(true);
        assertReason("no-permission", roleless, entry);

        disable(other);
        assertReason("disabled", other, entry);
    }

    @Test
    void theSessionAnswers404ForAnUnknownOrUnpublishedEntry() throws Exception {
        WeblogEntry draft = TestUtils.setupWeblogEntry("ccdraft", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(owner), PubStatus.DRAFT);
        TestUtils.endSession(true);

        for (RequestPostProcessor who : List.of(as(reader), anonymous())) {
            assertEquals(404, perform(get(SESSION).param("entry", "no-such-entry").with(who))
                    .getResponse().getStatus());
            assertEquals(404, perform(get(SESSION).param("entry", draft.getId()).with(who))
                    .getResponse().getStatus());
            assertEquals(404, perform(get(SESSION).with(who)).getResponse().getStatus(), "no entry at all");
            assertEquals(200, perform(get(SESSION).param("entry", entry.getId()).with(who))
                    .getResponse().getStatus(), "control: a published entry answers");
        }
    }

    /**
     * Review Focus 3. With {@code weblogAdminsUntrusted} on (the shipped
     * default) the entity strips tags from a screen name as it is set; with it
     * off the name keeps them, which is the case pinned here: the JSON must
     * carry whatever is stored as data.
     */
    @Test
    void theSessionJsonCarriesTheDisplayNameAsData() throws Exception {
        String hostile = "\"><img src=x onerror=alert(1)><script>x</script>";
        Boolean sanitizing = HTMLSanitizer.xssEnabled;
        MvcResult result;
        try {
            HTMLSanitizer.xssEnabled = Boolean.FALSE;
            User named = newReader("reader-cchostile", hostile);
            result = perform(get(SESSION).param("entry", entry.getId()).with(as(named)));
        } finally {
            HTMLSanitizer.xssEnabled = sanitizing;
        }

        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(result.getResponse().getContentType())),
                "JSON, never HTML: " + result.getResponse().getContentType());
        assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
        String raw = body(result);
        assertTrue(raw.startsWith("{"), raw);
        assertTrue(raw.contains("\"\\\"><img"), "the quote is JSON-escaped inside a string: " + raw);
        assertEquals(hostile, json(result).get("displayName"), "the name comes back as data, unchanged");
    }

    // ------------------------------------------------------------- posting (criteria 11-13)

    @Test
    void postingWithoutASessionIs401() throws Exception {
        MvcResult result = perform(post(POST).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload(entry.getId(), "Hello")));

        assertEquals(401, result.getResponse().getStatus());
        assertEquals(0, countOn(blog));
    }

    @Test
    void withoutCsrfIs403() throws Exception {
        MvcResult posted = perform(post(POST).with(as(reader)).contentType(MediaType.APPLICATION_JSON)
                .content(payload(entry.getId(), "Hello")));
        assertEquals(403, posted.getResponse().getStatus());
        assertEquals(0, countOn(blog));

        ReaderComment mine = service().post(reader, entry.getId(), "Mine", null).comment();
        TestUtils.endSession(true);
        MvcResult deleted = perform(delete(deletePath(mine.getId())).with(as(reader)));
        assertEquals(403, deleted.getResponse().getStatus());
        assertNotNull(comments().getComment(mine.getId()), "a refused delete removes nothing");

        assertEquals(201, postAs(as(reader), payload(entry.getId(), "Hello")).getResponse().getStatus(),
                "control: the same post with the token");
        assertEquals(204, deleteAs(as(reader), mine.getId()).getResponse().getStatus(),
                "control: the same delete with the token");
    }

    @Test
    void disabledIs403() throws Exception {
        disable(reader);

        MvcResult result = postAs(as(reader), payload(entry.getId(), "Hello"));

        assertEquals(403, result.getResponse().getStatus(),
                "a session that was valid before the account was disabled");
        assertEquals(0, countOn(blog));
    }

    @Test
    void anAccountWithoutCommentIs403() throws Exception {
        TestUtils.weblogger().getUserManager().revokeRole("reader", TestUtils.getManagedUser(reader));
        TestUtils.endSession(true);

        assertEquals(403, postAs(as(reader), payload(entry.getId(), "Hello")).getResponse().getStatus());
        assertEquals(0, countOn(blog));
    }

    @Test
    void postingStoresPendingForANewReaderAnd201() throws Exception {
        MockHttpSession browser = new MockHttpSession();
        Map<String, Object> session = json(perform(get(SESSION).param("entry", entry.getId())
                .session(browser).with(as(reader))));
        Map<?, ?> token = (Map<?, ?>) session.get("csrf");

        MvcResult result = perform(post(POST).session(browser).with(as(reader))
                .header((String) token.get("header"), (String) token.get("token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(entry.getId(), "First words")));

        assertEquals(201, result.getResponse().getStatus(), "the session's own token is accepted");
        Map<String, Object> created = json(result);
        assertEquals(Set.of("id", "status"), created.keySet());
        assertEquals("PENDING", created.get("status"));
        ReaderComment stored = comments().getComment((String) created.get("id"));
        assertEquals(Status.PENDING, stored.getStatus());
        assertEquals(reader.getId(), stored.getAuthor().getId());
        assertEquals("First words", stored.getBody());

        Map<String, Object> byOwner = json(postAs(user(owner.getUserName()), payload(entry.getId(), "Welcome")));
        assertEquals("PUBLISHED", byOwner.get("status"), "an owner is never held");
    }

    @Test
    void badBodiesAndRepliesAre400WithACode() throws Exception {
        assertBadRequest("empty-body", payload(entry.getId(), "   "));
        assertBadRequest("body-too-long", payload(entry.getId(), "x".repeat(4001)));
        assertBadRequest("bad-reply", MAPPER.writeValueAsString(
                Map.of("entry", entry.getId(), "body", "Thanks", "replyTo", "no-such-comment")));
        assertEquals(0, countOn(blog));
    }

    @Test
    void postingToAClosedOrCustomDomainEntryIs404() throws Exception {
        WeblogEntry closed = newEntry(blog, "ccclosed");
        setCommentsClosed(closed);
        Weblog custom = newWeblog("cccustom");
        setCustomDomain(custom, "comments-endpoints.example.test");
        WeblogEntry onCustom = newEntry(custom, "cccustomentry");
        Weblog off = newWeblog("ccoff");
        setCommentsEnabled(off, false);
        WeblogEntry onOff = newEntry(off, "ccoffentry");
        WeblogEntry draft = TestUtils.setupWeblogEntry("ccdraft", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(owner), PubStatus.DRAFT);
        TestUtils.endSession(true);

        for (String id : List.of(closed.getId(), onCustom.getId(), onOff.getId(), draft.getId(), "no-such-entry")) {
            assertEquals(404, postAs(as(reader), payload(id, "Hello")).getResponse().getStatus(), id);
        }
        assertEquals(0, countOn(blog));
        assertEquals(0, countOn(custom));
        assertEquals(0, countOn(off));
        assertEquals(201, postAs(as(reader), payload(entry.getId(), "Hello")).getResponse().getStatus(),
                "control: the open entry takes the same post");
    }

    @Test
    void theSixthPostIn10MinutesIs429() throws Exception {
        for (int i = 1; i <= 5; i++) {
            assertEquals(201, postAs(as(reader), payload(entry.getId(), "Comment " + i)).getResponse().getStatus());
        }

        assertEquals(429, postAs(as(reader), payload(entry.getId(), "One too many")).getResponse().getStatus());
        assertEquals(5, countOn(blog), "the refused one is not stored");
    }

    // ------------------------------------------------------------- deleting (criterion 23)

    @Test
    void deletingOwnIs204AndOthersIs404() throws Exception {
        ReaderComment parent = service().post(reader, entry.getId(), "Approve me", null).comment();
        TestUtils.endSession(true);
        service().setStatus(comments().getComment(parent.getId()), Status.PUBLISHED, owner);
        TestUtils.endSession(true);
        ReaderComment reply = service().post(owner, entry.getId(), "Thanks", parent.getId()).comment();
        ReaderComment second = service().post(reader, entry.getId(), "Another", null).comment();
        TestUtils.endSession(true);
        assertNotNull(reply, "fixture: the owner replied");

        assertEquals(404, deleteAs(as(other), second.getId()).getResponse().getStatus(), "not theirs");
        assertNotNull(comments().getComment(second.getId()));
        assertEquals(404, deleteAs(as(reader), "no-such-comment").getResponse().getStatus());
        assertEquals(401, perform(delete(deletePath(second.getId())).with(csrf())).getResponse().getStatus());
        assertNotNull(comments().getComment(second.getId()));

        assertEquals(204, deleteAs(as(reader), parent.getId()).getResponse().getStatus());
        assertNull(comments().getComment(parent.getId()));
        assertNull(comments().getComment(reply.getId()), "the reply goes with it");

        disable(reader);
        assertEquals(404, deleteAs(as(reader), second.getId()).getResponse().getStatus(),
                "a disabled account deletes nothing");
        assertNotNull(comments().getComment(second.getId()));
    }

    // ------------------------------------------------------------- the CSRF boundary

    /**
     * Characterisation: passes against {@code SecurityConfig} as it stands, and
     * pins that this task added no exemption. {@code withoutCsrfIs403} is the
     * behavioural half.
     */
    @Test
    void noCsrfExemptionWasAdded() throws Exception {
        Method predicate = SecurityConfig.class.getDeclaredMethod("isPublicAudiencePost", HttpServletRequest.class);
        predicate.setAccessible(true);
        for (String contextPath : List.of("", "/pressroom")) {
            MockHttpServletRequest posting = new MockHttpServletRequest("POST", contextPath + POST);
            posting.setContextPath(contextPath);
            MockHttpServletRequest deleting = new MockHttpServletRequest("DELETE", contextPath + deletePath("x"));
            deleting.setContextPath(contextPath);
            assertFalse((boolean) predicate.invoke(null, posting));
            assertFalse((boolean) predicate.invoke(null, deleting));
        }
    }

    // ------------------------------------------------------------- failing closed

    @Test
    void aStoreThatCannotBeReachedAnswers500() throws Exception {
        Weblogger broken = mock(Weblogger.class);
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(broken.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntry(any())).thenThrow(new WebloggerException("down"));
        CommentService failing = mock(CommentService.class);
        when(failing.post(any(), any(), any(), any())).thenThrow(new WebloggerException("down"));
        when(failing.deleteOwn(any(), any())).thenThrow(new WebloggerException("down"));
        ControllerTestFixture.useWeblogger(broken);
        CommentsController controller = ControllerTestFixture.withMessages(new CommentsController(failing, SSO));

        ResponseEntity<?> session = controller.session("an-entry", signedIn(reader));
        assertEquals(500, session.getStatusCode().value());
        assertEquals("no-store", session.getHeaders().getCacheControl());
        assertEquals(500, controller.post(new CommentsController.CommentPayload("an-entry", "Hi", null),
                signedIn(reader)).getStatusCode().value());
        assertEquals(500, controller.delete("a-comment", signedIn(reader)).getStatusCode().value());
    }

    @Test
    void withoutACsrfTokenOnTheRequestTheFormIsNotOffered() throws Exception {
        WeblogEntry published = new WeblogEntry();
        published.setStatus(PubStatus.PUBLISHED);
        Weblogger tier = mock(Weblogger.class);
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(tier.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntry("an-entry")).thenReturn(published);
        CommentService allowing = mock(CommentService.class);
        ControllerTestFixture.useWeblogger(tier);
        CommentsController controller = ControllerTestFixture.withMessages(new CommentsController(allowing, SSO));

        ResponseEntity<?> session = controller.session("an-entry", signedIn(reader));

        assertEquals(500, session.getStatusCode().value(), "no token, no form: the post could never pass");
        assertNull(session.getBody());
    }

    /**
     * The whole refusal table, including the arms no HTTP request reaches
     * ({@code NOT_SIGNED_IN} never comes back for a resolved account). Every
     * constant must be listed, so a new one is a decision, not a 500.
     */
    @Test
    void everyRefusalAnswersItsStatusAndOnlyA400CarriesACode() throws Exception {
        Map<CommentRefusal, Integer> expected = new EnumMap<>(CommentRefusal.class);
        expected.put(CommentRefusal.NOT_SIGNED_IN, 401);
        expected.put(CommentRefusal.DISABLED, 403);
        expected.put(CommentRefusal.NO_PERMISSION, 403);
        expected.put(CommentRefusal.NOT_FOUND, 404);
        expected.put(CommentRefusal.CLOSED, 404);
        expected.put(CommentRefusal.COMMENTS_OFF, 404);
        expected.put(CommentRefusal.CUSTOM_DOMAIN, 404);
        expected.put(CommentRefusal.EMPTY_BODY, 400);
        expected.put(CommentRefusal.BODY_TOO_LONG, 400);
        expected.put(CommentRefusal.BAD_REPLY, 400);
        expected.put(CommentRefusal.RATE_LIMITED, 429);
        assertEquals(EnumSet.allOf(CommentRefusal.class), expected.keySet());

        CommentService refusing = mock(CommentService.class);
        CommentsController controller = ControllerTestFixture.withMessages(new CommentsController(refusing, SSO));
        for (Map.Entry<CommentRefusal, Integer> e : expected.entrySet()) {
            when(refusing.post(any(), any(), any(), any())).thenReturn(CommentOutcome.refused(e.getKey()));
            ResponseEntity<Map<String, String>> answer = controller.post(
                    new CommentsController.CommentPayload("an-entry", "Hi", null), signedIn(reader));
            assertEquals(e.getValue(), answer.getStatusCode().value(), e.getKey().name());
            assertEquals(e.getValue() == 400 ? Map.of("error", CommentsController.code(e.getKey())) : null,
                    answer.getBody(), e.getKey().name());
        }
        assertEquals("custom-domain", CommentsController.code(CommentRefusal.CUSTOM_DOMAIN));
    }

    // ------------------------------------------------------------- helpers

    private void assertReason(String reason, User who, WeblogEntry on) throws Exception {
        MvcResult result = perform(get(SESSION).param("entry", on.getId()).with(as(who)));
        assertEquals(200, result.getResponse().getStatus(), reason);
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"), reason);
        assertEquals(Map.of("signedIn", true, "canPost", false, "reason", reason), json(result));
    }

    private void assertBadRequest(String code, String json) throws Exception {
        MvcResult result = postAs(as(reader), json);
        assertEquals(400, result.getResponse().getStatus(), code);
        assertEquals(Map.of("error", code), json(result));
    }

    private static MvcResult postAs(RequestPostProcessor who, String json) throws Exception {
        return perform(post(POST).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static MvcResult deleteAs(RequestPostProcessor who, String commentId) throws Exception {
        return perform(delete(deletePath(commentId)).with(csrf()).with(who));
    }

    /** One request, then the end of its persistence session, as the request filter does. */
    private static MvcResult perform(RequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn();
        } finally {
            TestUtils.endSession(true);
        }
    }

    private static String deletePath(String commentId) {
        return "/pressroom-ui/rendering/comments/" + commentId + ".rol";
    }

    private static String payload(String entryId, String body) {
        return MAPPER.writeValueAsString(Map.of("entry", entryId, "body", body));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(MvcResult result) throws Exception {
        return MAPPER.readValue(body(result), Map.class);
    }

    /** A reader as the provider leaves them signed in. */
    private static RequestPostProcessor as(User who) {
        List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority("reader"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject("sub-" + who.getUserName())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        PressroomOidcUser principal = new PressroomOidcUser(new DefaultOidcUser(roles, idToken),
                who.getUserName(), roles, false);
        return authentication(new OAuth2AuthenticationToken(principal, roles, "oidc"));
    }

    private static RequestPostProcessor anonymous() {
        return request -> request;
    }

    /** A request as the interceptor leaves it for a signed-in account, for calling the controller directly. */
    private static HttpServletRequest signedIn(User who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUser", who);
        return request;
    }

    private static CommentService service() {
        return context.getBean(CommentService.class);
    }

    private static CommentManager comments() {
        return TestUtils.weblogger().getCommentManager();
    }

    private static long countOn(Weblog weblog) throws Exception {
        return comments().countComments(TestUtils.getManagedWebsite(weblog), null);
    }

    private Weblog newWeblog(String handle) throws Exception {
        Weblog w = TestUtils.setupWeblog(handle, owner);
        weblogIds.add(w.getId());
        TestUtils.endSession(true);
        setCommentsEnabled(w, true);
        return w;
    }

    private WeblogEntry newEntry(Weblog weblog, String anchor) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
        return e;
    }

    private User newReader(String userName, String screenName) throws Exception {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName(screenName);
        u.setFullName(screenName);
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject("sub-" + UUID.randomUUID());
        TestUtils.weblogger().getUserManager().addReader(u);
        TestUtils.weblogger().flush();
        userNames.add(userName);
        TestUtils.endSession(true);
        return u;
    }

    private static void disable(User who) throws Exception {
        UserManager users = TestUtils.weblogger().getUserManager();
        User current = users.getUserByUserName(who.getUserName(), null);
        current.setEnabled(Boolean.FALSE);
        users.saveUser(current);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setCommentsEnabled(Weblog weblog, boolean on) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setCommentsEnabled(on);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setCustomDomain(Weblog weblog, String domain) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setCustomDomain(domain);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setCommentsClosed(WeblogEntry e) throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(e);
        managed.setCommentsClosed(true);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
