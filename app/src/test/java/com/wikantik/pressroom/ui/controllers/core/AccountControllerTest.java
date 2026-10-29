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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.controllers.core.ControllerTestFixture.errors;
import static com.wikantik.pressroom.ui.controllers.core.ControllerTestFixture.flashErrors;
import static com.wikantik.pressroom.ui.controllers.core.ControllerTestFixture.requestFor;
import static com.wikantik.pressroom.ui.controllers.core.ControllerTestFixture.withMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The reader's account page (spec criteria 29 and 30, Review Focus 4),
 * driven through the real UI security chain ({@link SecurityConfig}, CSRF
 * on), the real {@link PressroomHandlerInterceptor} and the real
 * {@link CommentService} against the database. A reader signs in as the
 * provider leaves them, with a {@link PressroomOidcUser}; an owner or an
 * administrator with a password-account principal.
 *
 * <p>The last few tests call the controller directly over a mocked tier, for
 * the one thing the database cannot be made to do on demand: fail. A check
 * that could not run must refuse, never pass.
 */
class AccountControllerTest {

    private static final String ACCOUNT = "/pressroom-ui/account.rol";
    private static final String SAVE_NAME = "/pressroom-ui/account!saveName.rol";
    private static final String DELETE_COMMENT = "/pressroom-ui/account!deleteComment.rol";
    private static final String DELETE_ACCOUNT = "/pressroom-ui/account!deleteAccount.rol";
    private static final String ISSUER = "https://issuer.test/account-page";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    private final List<String> userNames = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    private User owner;
    private User reader;
    private User other;
    private Weblog blogA;
    private Weblog blogB;
    private WeblogEntry entryA;
    private WeblogEntry entryB;
    private MockWeblogger mocked;

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
        CommentService commentService() {
            return new CommentService(TestUtils.weblogger(), comment -> { });
        }

        @Bean
        AccountController accountController(CommentService comments) {
            return new AccountController(comments);
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
        owner = TestUtils.setupUser("acowner");
        userNames.add(owner.getUserName());
        rename(owner, "Weblog Owner " + owner.getId().substring(0, 8));
        blogA = newWeblog("acbloga");
        blogB = newWeblog("acblogb");
        entryA = TestUtils.setupWeblogEntry("acentrya", TestUtils.getManagedWebsite(blogA),
                TestUtils.getManagedUser(owner));
        entryB = TestUtils.setupWeblogEntry("acentryb", TestUtils.getManagedWebsite(blogB),
                TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
        reader = newReader("reader-acreader1", "Ann Reader");
        other = newReader("reader-acreader2", "Bob Reader");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocked != null) {
            mocked.detach();
        }
        ControllerTestFixture.useDefaultWeblogger();
        SecurityContextHolder.clearContext();
        TestUtils.endSession(false);
        for (String id : weblogIds) {
            TestUtils.teardownWeblog(id);
        }
        UserManager users = TestUtils.weblogger().getUserManager();
        for (String name : userNames) {
            if (users.getUserByUserName(name, null) != null) {
                TestUtils.teardownUser(name);
            }
        }
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- who gets in (criterion 29)

    @Test
    void anonymousIsSentToLogIn() throws Exception {
        ReaderComment mine = published(reader, entryA, "Mine");

        for (RequestBuilder request : List.of(get(ACCOUNT),
                post(SAVE_NAME).with(csrf()).param("screenName", "Taken Over"),
                post(DELETE_COMMENT).with(csrf()).param("id", mine.getId()),
                post(DELETE_ACCOUNT).with(csrf()))) {
            MvcResult result = perform(request);
            assertEquals(302, result.getResponse().getStatus());
            assertEquals("/pressroom-ui/login.rol", result.getResponse().getRedirectedUrl());
        }
        assertNotNull(comments().getComment(mine.getId()), "nothing was deleted");
    }

    @Test
    void anAccountWithoutTheCommentActionIsRefused() throws Exception {
        User roleless = newReader("reader-acroleless", "No Role");
        TestUtils.weblogger().getUserManager().revokeRole("reader", TestUtils.getManagedUser(roleless));
        TestUtils.endSession(true);

        MvcResult result = perform(get(ACCOUNT).with(as(roleless)));

        assertEquals(302, result.getResponse().getStatus());
        assertEquals("/pressroom-ui/access-denied.rol", result.getResponse().getRedirectedUrl());
    }

    @Test
    void aReaderSeesThePage() throws Exception {
        ReaderComment older = published(reader, entryA, "Older");
        Thread.sleep(5);
        ReaderComment newer = service().post(reader, entryB.getId(), "Newer, held", null).comment();
        ReaderComment theirs = service().post(other, entryA.getId(), "Theirs", null).comment();
        TestUtils.endSession(true);

        MvcResult result = perform(get(ACCOUNT).with(as(reader)));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(".Account", result.getModelAndView().getViewName());
        assertEquals(List.of(newer.getId(), older.getId()), ids(result),
                "this reader's comments, newest first, and nobody else's");
        assertFalse(ids(result).contains(theirs.getId()));
        assertEquals(reader.getUserName(), ((User) model(result, "account")).getUserName());
        assertNull(model(result, "deleteRefusal"), "a reader may delete their account");
    }

    // ------------------------------------------------------------- the display name (criterion 29)

    @Test
    void aNameMatchingAnOwnersScreenNameIsRefused() throws Exception {
        String ownerName = TestUtils.getManagedUser(owner).getScreenName();
        // Upper case, a zero-width space and doubled blanks: the same name to a reader's eye.
        String lookalike = "  " + ownerName.toUpperCase(Locale.ROOT)
                .replace(" ", "\u200B  ") + " ";

        MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(as(reader)).param("screenName", lookalike));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(".Account", result.getModelAndView().getViewName());
        assertEquals(List.of("account.name.taken"), modelErrors(result));
        assertEquals("Ann Reader", stored(reader).getScreenName(), "a refused name is not saved");
    }

    @Test
    void anOwnerMayKeepTheirOwnName() throws Exception {
        String ownerName = TestUtils.getManagedUser(owner).getScreenName();

        MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(user(owner.getUserName()))
                .param("screenName", ownerName));

        assertEquals("/pressroom-ui/account.rol", result.getResponse().getRedirectedUrl(),
                "a member's own name is not somebody else's: " + modelErrors(result));
        assertEquals(ownerName, stored(owner).getScreenName());
    }

    @Test
    void aBlankOr51CodePointNameIsRefused() throws Exception {
        String fiftyOne = "\uD83D\uDE00".repeat(51);
        for (String bad : List.of("", "   ", "\u0007\u200B\u202E", fiftyOne)) {
            MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(as(reader)).param("screenName", bad));
            assertEquals(List.of("account.name.length"), modelErrors(result), "for [" + bad + "]");
            assertEquals("Ann Reader", stored(reader).getScreenName(), "nothing saved for [" + bad + "]");
        }

        String fifty = "\uD83D\uDE00".repeat(50);
        MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(as(reader)).param("screenName", fifty));
        assertEquals("/pressroom-ui/account.rol", result.getResponse().getRedirectedUrl(),
                "fifty code points is the limit, not fifty chars: " + modelErrors(result));
        assertEquals(fifty, stored(reader).getScreenName());
    }

    @Test
    void aNameThatIsOnlyMarkupIsRefused() throws Exception {
        Boolean sanitizing = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                    .param("screenName", "<b></b>"));
            assertEquals(List.of("account.name.length"), modelErrors(result),
                    "the stored form of the name would be empty");
        } finally {
            HTMLSanitizer.xssEnabled = sanitizing;
        }
        assertEquals("Ann Reader", stored(reader).getScreenName());
    }

    /**
     * Review finding: the stripper decodes entities after removing tags, so
     * one pass leaves new markup that the setter's own pass strips. Judged
     * after one pass this was {@code <owner name><b></b>} and passed; stored,
     * it was the owner's exact name.
     */
    @Test
    void aNameThatStoresAsAnOwnersNameIsRefused() throws Exception {
        String ownerName = TestUtils.getManagedUser(owner).getScreenName();
        MvcResult result = withMarkupStripped(() -> perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                .param("screenName", "<i>" + ownerName + "&lt;b&gt;&lt;/b&gt;</i>")));

        assertEquals(List.of("account.name.taken"), modelErrors(result));
        assertEquals("Ann Reader", stored(reader).getScreenName());
    }

    /** Review finding: seven code points after one pass, an empty name once stored. */
    @Test
    void aNameThatStoresEmptyIsRefused() throws Exception {
        MvcResult result = withMarkupStripped(() -> perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                .param("screenName", "<i>&lt;b&gt;&lt;/b&gt;</i>")));

        assertEquals(List.of("account.name.length"), modelErrors(result));
        assertEquals("Ann Reader", stored(reader).getScreenName());
    }

    @Test
    void aNameThatNeverSettlesIsRefused() throws Exception {
        MvcResult result = withMarkupStripped(() -> perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                .param("screenName", "x < y &" + "amp;".repeat(20) + "lt;")));

        assertEquals(List.of("account.name.invalid"), modelErrors(result));
        assertEquals("Ann Reader", stored(reader).getScreenName());
    }

    @Test
    void aMarkedUpNameIsSavedExactlyAsJudged() throws Exception {
        MvcResult result = withMarkupStripped(() -> perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                .param("screenName", "<b>Ann</b>\u00A0Marked")));

        assertEquals("/pressroom-ui/account.rol", result.getResponse().getRedirectedUrl(), modelErrors(result).toString());
        assertEquals("Ann Marked", stored(reader).getScreenName());
    }

    @Test
    void aValidNameIsSavedAndExpiresTheWeblogs() throws Exception {
        published(reader, entryA, "On A");
        published(reader, entryB, "On B");
        Date beforeA = freshLastModified(blogA);
        Date beforeB = freshLastModified(blogB);

        MvcResult result = perform(post(SAVE_NAME).with(csrf()).with(as(reader))
                .param("screenName", " \tAnn\u00A0\u00A0 Newname\u200B "));

        assertEquals(302, result.getResponse().getStatus());
        assertEquals("/pressroom-ui/account.rol", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.name.saved"), flash(result, "messages"));
        User saved = stored(reader);
        assertEquals("Ann Newname", saved.getScreenName(), "controls and format characters gone, blanks collapsed");
        assertEquals("Ann Newname", saved.getFullName(), "a full name that was the screen name follows it");
        assertTrue(TestUtils.getManagedWebsite(blogA).getLastModified().after(beforeA), "weblog A expires");
        assertTrue(TestUtils.getManagedWebsite(blogB).getLastModified().after(beforeB), "weblog B expires");
        assertEquals("Ann Newname", comments().getPublishedForEntry(TestUtils.getManagedWeblogEntry(entryA))
                .get(0).getAuthor().getScreenName(), "the new name shows on the published comment");
    }

    @Test
    void aFullNameOfItsOwnIsKept() throws Exception {
        User managed = TestUtils.getManagedUser(reader);
        managed.setFullName("Ann Q. Reader");
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.endSession(true);

        perform(post(SAVE_NAME).with(csrf()).with(as(reader)).param("screenName", "Annie"));

        assertEquals("Annie", stored(reader).getScreenName());
        assertEquals("Ann Q. Reader", stored(reader).getFullName());
    }

    // ------------------------------------------------------------- the reader's comments

    @Test
    void deletingOwnCommentWorks() throws Exception {
        ReaderComment mine = published(reader, entryA, "Mine");
        ReaderComment reply = service().reply(TestUtils.getManagedUser(owner),
                comments().getComment(mine.getId()), "Thanks").comment();
        ReaderComment theirs = service().post(other, entryA.getId(), "Theirs", null).comment();
        TestUtils.endSession(true);

        MvcResult deleted = perform(post(DELETE_COMMENT).with(csrf()).with(as(reader)).param("id", mine.getId()));

        assertEquals("/pressroom-ui/account.rol", deleted.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.comment.deleted"), flash(deleted, "messages"));
        assertNull(comments().getComment(mine.getId()));
        assertNull(comments().getComment(reply.getId()), "its reply goes with it");

        MvcResult refused = perform(post(DELETE_COMMENT).with(csrf()).with(as(reader)).param("id", theirs.getId()));

        assertEquals("/pressroom-ui/account.rol", refused.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.comment.notFound"), flash(refused, "errors"));
        assertNotNull(comments().getComment(theirs.getId()), "another reader's comment stays");
    }

    @Test
    void aDeletedHiddenCommentLeavesNoRowOnThePage() throws Exception {
        ReaderComment hidden = published(reader, entryA, "Hidden later");
        service().setStatus(comments().getComment(hidden.getId()), Status.HIDDEN, TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);

        perform(post(DELETE_COMMENT).with(csrf()).with(as(reader)).param("id", hidden.getId()));

        ReaderComment tombstone = comments().getComment(hidden.getId());
        assertNotNull(tombstone, "the tombstone stays for the owner (ruling R5)");
        assertEquals("", tombstone.getBody());
        assertEquals(List.of(), ids(perform(get(ACCOUNT).with(as(reader)))),
                "the reader asked for it to be gone, so the page omits it");
    }

    // ------------------------------------------------------------- deleting the account (criterion 30)

    /** Review Focus 4. */
    @Test
    void deletingAnAccountRemovesItsCommentsAndRepliesAndExpiresEachWeblog() throws Exception {
        ReaderComment onA = published(reader, entryA, "On A");
        ReaderComment onB = published(reader, entryB, "On B");
        ReaderComment replyA = service().reply(TestUtils.getManagedUser(owner),
                comments().getComment(onA.getId()), "Reply on A").comment();
        ReaderComment replyB = service().reply(TestUtils.getManagedUser(owner),
                comments().getComment(onB.getId()), "Reply on B").comment();
        ReaderComment theirs = published(other, entryA, "Stays");
        TestUtils.endSession(true);
        Date beforeA = freshLastModified(blogA);
        Date beforeB = freshLastModified(blogB);

        MvcResult result = perform(post(DELETE_ACCOUNT).with(csrf()).with(as(reader)));

        assertEquals(302, result.getResponse().getStatus());
        assertEquals("/", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.deleted"), flash(result, "messages"));
        assertNull(TestUtils.weblogger().getUserManager().getUserByUserName(reader.getUserName(), null),
                "the account is gone");
        for (ReaderComment gone : List.of(onA, onB, replyA, replyB)) {
            assertNull(comments().getComment(gone.getId()), gone.getBody() + " must go");
        }
        assertNotNull(comments().getComment(theirs.getId()), "another reader's comment stays");
        User sameName = new User();
        sameName.setUserName(reader.getUserName());
        assertEquals(List.of(), TestUtils.weblogger().getUserManager().getRoles(sameName), "its roles go too");
        assertTrue(TestUtils.getManagedWebsite(blogA).getLastModified().after(beforeA), "weblog A expires");
        assertTrue(TestUtils.getManagedWebsite(blogB).getLastModified().after(beforeB), "weblog B expires");
    }

    @Test
    void deletingIsRefusedForAMemberAndForAnAdmin() throws Exception {
        MvcResult member = perform(post(DELETE_ACCOUNT).with(csrf()).with(user(owner.getUserName())));

        assertEquals("/pressroom-ui/account.rol", member.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.delete.member"), flash(member, "errors"));
        assertNotNull(stored(owner), "a member's account stays");

        User admin = TestUtils.setupUser("acadmin");
        userNames.add(admin.getUserName());
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);

        MvcResult refused = perform(post(DELETE_ACCOUNT).with(csrf()).with(user(admin.getUserName())));

        assertEquals("/pressroom-ui/account.rol", refused.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.delete.admin"), flash(refused, "errors"));
        assertNotNull(stored(admin), "an administrator's account stays");

        assertEquals("account.delete.member",
                model(perform(get(ACCOUNT).with(user(owner.getUserName()))), "deleteRefusal"),
                "the page says why instead of offering the button");
        assertEquals("account.delete.admin",
                model(perform(get(ACCOUNT).with(user(admin.getUserName()))), "deleteRefusal"));
    }

    /**
     * Ruling R8: entries name their creator by username with no foreign key,
     * so deleting a writer would orphan the bylines and hand them to the next
     * account given the same name.
     */
    @Test
    void deletingIsRefusedForAnAccountThatWroteEntries() throws Exception {
        TestUtils.setupWeblogEntry("acbyreader", TestUtils.getManagedWebsite(blogA),
                TestUtils.getManagedUser(reader));
        TestUtils.endSession(true);

        MvcResult refused = perform(post(DELETE_ACCOUNT).with(csrf()).with(as(reader)));

        assertEquals("/pressroom-ui/account.rol", refused.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.delete.author"), flash(refused, "errors"));
        assertNotNull(stored(reader), "the writer's account stays");
        assertEquals("account.delete.author", model(perform(get(ACCOUNT).with(as(reader))), "deleteRefusal"));
    }

    /** Under a context path, as no code may assume there is none. */
    @Test
    void theSessionEndsAfterDeletion() throws Exception {
        MockHttpSession browser = signedInSession(reader);
        assertEquals(200, perform(get("/pressroom" + ACCOUNT).contextPath("/pressroom").session(browser))
                .getResponse().getStatus(), "control: the session signs the reader in");

        MvcResult result = perform(post("/pressroom" + DELETE_ACCOUNT).contextPath("/pressroom")
                .with(csrf()).session(browser));

        assertEquals("/pressroom/", result.getResponse().getRedirectedUrl());
        assertTrue(browser.isInvalid(), "the signed-in session is invalidated");
        Cookie rememberMe = result.getResponse().getCookie("remember-me");
        assertNotNull(rememberMe, "any remember-me cookie is cancelled");
        assertEquals(0, rememberMe.getMaxAge());
        assertEquals("/pressroom", rememberMe.getPath(), "on the path it was set for");

        MockHttpSession next = (MockHttpSession) result.getRequest().getSession(false);
        MockHttpSession carried = next == null ? new MockHttpSession() : next;
        assertNull(carried.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
                "whatever session the browser holds now carries no sign-in");
        MvcResult after = perform(get("/pressroom" + ACCOUNT).contextPath("/pressroom").session(carried));
        assertEquals("/pressroom/pressroom-ui/login.rol", after.getResponse().getRedirectedUrl(),
                "the next request is anonymous");
    }

    // ------------------------------------------------------------- failing closed

    @Test
    void aNameIsRefusedWhenTheMemberCheckCannotRun() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getScreenNamesOfWeblogMembers()).thenThrow(new WebloggerException("down"));
        Model model = new ExtendedModelMap();

        String view = controller.saveName("A Fine Name", requestFor(account), model, new RedirectAttributesModelMap());

        assertEquals(".Account", view);
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        assertEquals("Reader X", account.getScreenName(), "nothing changed");
        verify(mocked.userManager(), never()).saveUser(any());
    }

    @Test
    void deletionIsRefusedWhenTheMembershipCheckCannotRun() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getWeblogPermissions(account)).thenThrow(new WebloggerException("down"));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.deleteAccount(requestFor(account), new MockHttpServletResponse(), flash);

        assertEquals("redirect:/pressroom-ui/account.rol", view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
        verify(mocked.userManager(), never()).removeUser(any());
    }

    @Test
    void deletionIsRefusedWhenTheAdminCheckCannotRun() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getWeblogPermissions(account)).thenReturn(List.of());
        when(mocked.userManager().checkPermission(any(GlobalPermission.class), any(User.class)))
                .thenThrow(new WebloggerException("down"));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.deleteAccount(requestFor(account), new MockHttpServletResponse(), flash);

        assertEquals("redirect:/pressroom-ui/account.rol", view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
        verify(mocked.userManager(), never()).removeUser(any());
    }

    /** Review minor: a save that failed must not show its unsaved name as the current one. */
    @Test
    void aFailedSaveShowsTheNameAsStored() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getUserByUserName("reader-x")).thenReturn(account("reader-x"));
        doThrow(new WebloggerException("down")).when(mocked.userManager()).saveUser(any());
        Model model = new ExtendedModelMap();

        assertEquals(".Account", controller.saveName("A Fine Name", requestFor(account), model,
                new RedirectAttributesModelMap()));

        assertEquals(List.of("generic.error.check.logs"), errors(model));
        assertEquals("Reader X", ((User) model.getAttribute("account")).getScreenName(),
                "the page shows the name as stored");
        assertEquals("A Fine Name", model.getAttribute("nameValue"), "the form keeps what was typed");
        verify(mocked.weblogger()).release();
    }

    @Test
    void aFailedSaveWhoseAccountCannotBeReadShowsNoName() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getUserByUserName("reader-x")).thenThrow(new WebloggerException("down"));
        doThrow(new WebloggerException("down")).when(mocked.userManager()).saveUser(any());
        Model model = new ExtendedModelMap();

        controller.saveName("A Fine Name", requestFor(account), model, new RedirectAttributesModelMap());

        assertNull(model.getAttribute("account"));
        assertEquals(List.of(), model.getAttribute("comments"));
        assertEquals(List.of("generic.error.check.logs"), errors(model));
    }

    /** Review minor: the comment id is a request parameter; written raw, a CR/LF in it forges log lines. */
    @Test
    void aCommentIdIsNeverWrittenToTheLog() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getUserByUserName(any(), any())).thenThrow(new WebloggerException("down"));
        List<String> lines;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            controller.deleteComment("c1\r\nFORGED a line", requestFor(account), new RedirectAttributesModelMap());
            lines = capture.everything();
        }

        assertTrue(lines.stream().anyMatch(l -> l.contains("reader-x")), "control: the failure was logged");
        assertTrue(lines.stream().noneMatch(l -> l.contains("FORGED")), "the raw id reached the log: " + lines);
    }

    @Test
    void deletionIsRefusedWhenTheAuthorshipCheckCannotRun() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getWeblogPermissions(account)).thenReturn(List.of());
        when(mocked.weblogEntryManager().hasEntriesCreatedBy(account)).thenThrow(new WebloggerException("down"));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        assertEquals("redirect:/pressroom-ui/account.rol",
                controller.deleteAccount(requestFor(account), new MockHttpServletResponse(), flash));

        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
        verify(mocked.userManager(), never()).removeUser(any());
    }

    @Test
    void aStoreErrorWhileDeletingACommentIsReported() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.userManager().getUserByUserName(any(), any())).thenThrow(new WebloggerException("down"));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.deleteComment("c1", requestFor(account), flash);

        assertEquals("redirect:/pressroom-ui/account.rol", view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
    }

    /**
     * Each comment's entry is labelled by a plain-text title the page escapes
     * once (EntriesController.plainTitle): a stored title is already escaped,
     * and an UNTITLED entry's displayTitle is its body with complete tags
     * stripped, so an unterminated {@code <img onerror>} survives into it.
     */
    @Test
    void eachCommentsEntryGetsAPlainTextTitleThePageEscapesOnce() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        WeblogEntry titled = new WeblogEntry();
        titled.setId("e-titled");
        titled.setTitle("Tom &amp; Jerry");
        WeblogEntry untitled = new WeblogEntry();
        untitled.setId("e-untitled");
        untitled.setText("<img src=x onerror=alert(1)//");
        ReaderComment onTitled = new ReaderComment();
        onTitled.setEntry(titled);
        ReaderComment onUntitled = new ReaderComment();
        onUntitled.setEntry(untitled);
        when(mocked.commentManager().getCommentsByAuthor(account)).thenReturn(List.of(onTitled, onUntitled));
        Model model = new ExtendedModelMap();

        controller.execute(requestFor(account), model);

        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model.getAttribute("entryTitles");
        assertNotNull(titles, "the page has no plain entry titles to label its comments with");
        assertEquals("Tom & Jerry", titles.get("e-titled"));
        assertEquals("<img src=x onerror=alert(1)//", titles.get("e-untitled"),
                "raw text, for the page to escape exactly once");
    }

    /** A display path: what cannot be read shows empty, with an error, rather than failing the page. */
    @Test
    void aPageThatCannotLoadShowsAnErrorAndNoComments() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        when(mocked.commentManager().getCommentsByAuthor(account)).thenThrow(new WebloggerException("down"));
        Model model = new ExtendedModelMap();

        assertEquals(".Account", controller.execute(requestFor(account), model));

        assertEquals(List.of(), model.getAttribute("comments"));
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        assertEquals("Reader X", model.getAttribute("nameValue"));
    }

    /**
     * Expiring comes after the deletion has committed: a weblog that cannot be
     * expired is logged, the others still are, and the account stays deleted
     * and signed out.
     */
    @Test
    void aWeblogThatCannotExpireDoesNotUndoTheDeletion() throws Exception {
        AccountController controller = mockedController();
        User account = account("reader-x");
        Weblog broken = weblog("broken");
        Weblog fine = weblog("fine");
        when(mocked.userManager().getWeblogPermissions(account)).thenReturn(List.of());
        when(mocked.commentManager().getWeblogsWithPublishedCommentsBy(account)).thenReturn(List.of(broken, fine));
        doThrow(new WebloggerException("down")).when(mocked.weblogManager()).saveWeblog(broken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.deleteAccount(requestFor(account), response, flash);

        assertEquals("redirect:/", view);
        verify(mocked.userManager()).removeUser(account);
        verify(mocked.weblogManager()).saveWeblog(fine);
        assertEquals("/", response.getCookie("remember-me").getPath(), "no context path: the root");
    }

    // ------------------------------------------------------------- helpers

    private interface Request {
        MvcResult run() throws Exception;
    }

    /** With markup stripped from names, as {@code weblogAdminsUntrusted} (the shipped default) does. */
    private static MvcResult withMarkupStripped(Request request) throws Exception {
        Boolean sanitizing = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            return request.run();
        } finally {
            HTMLSanitizer.xssEnabled = sanitizing;
        }
    }

    /** One request, then the end of its persistence session, as the request filter does. */
    private static MvcResult perform(RequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn();
        } finally {
            TestUtils.endSession(true);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(MvcResult result) {
        return ((List<ReaderComment>) model(result, "comments")).stream().map(ReaderComment::getId).toList();
    }

    private static Object model(MvcResult result, String name) {
        return result.getModelAndView().getModel().get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> modelErrors(MvcResult result) {
        Object errors = result.getModelAndView() == null ? null : model(result, "errors");
        return errors == null ? List.of() : (List<String>) errors;
    }

    @SuppressWarnings("unchecked")
    private static List<String> flash(MvcResult result, String name) {
        Object values = result.getFlashMap().get(name);
        return values == null ? List.of() : (List<String>) values;
    }

    /** A reader as the provider leaves them signed in. */
    private static RequestPostProcessor as(User who) {
        return authentication(readerAuthentication(who));
    }

    private static Authentication readerAuthentication(User who) {
        List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority("reader"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject("sub-" + who.getUserName())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        PressroomOidcUser principal = new PressroomOidcUser(new DefaultOidcUser(roles, idToken),
                who.getUserName(), roles, false);
        return new OAuth2AuthenticationToken(principal, roles, "oidc");
    }

    /** A browser session as a real sign-in leaves it: the security context stored in the session. */
    private static MockHttpSession signedInSession(User who) {
        SecurityContext signedIn = SecurityContextHolder.createEmptyContext();
        signedIn.setAuthentication(readerAuthentication(who));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, signedIn);
        return session;
    }

    private static CommentService service() {
        return context.getBean(CommentService.class);
    }

    private static CommentManager comments() {
        return TestUtils.weblogger().getCommentManager();
    }

    private static User stored(User who) throws Exception {
        return TestUtils.weblogger().getUserManager().getUserByUserName(who.getUserName(), null);
    }

    /** A PUBLISHED comment by {@code who}: posted (held, for a new reader), then approved by the owner. */
    private ReaderComment published(User who, WeblogEntry on, String body) throws Exception {
        ReaderComment c = service().post(who, on.getId(), body, null).comment();
        TestUtils.endSession(true);
        service().setStatus(comments().getComment(c.getId()), Status.PUBLISHED, TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
        return c;
    }

    /** A known lastModified, then a pause, so that a later bump is a later millisecond. */
    private static Date freshLastModified(Weblog weblog) throws Exception {
        TestUtils.weblogger().getWeblogManager().saveWeblog(TestUtils.getManagedWebsite(weblog));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        Date known = TestUtils.getManagedWebsite(weblog).getLastModified();
        Thread.sleep(5);
        return known;
    }

    private Weblog newWeblog(String handle) throws Exception {
        Weblog w = TestUtils.setupWeblog(handle, TestUtils.getManagedUser(owner));
        weblogIds.add(w.getId());
        TestUtils.endSession(true);
        Weblog managed = TestUtils.getManagedWebsite(w);
        managed.setCommentsEnabled(true);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return w;
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

    private static void rename(User who, String screenName) throws Exception {
        User managed = TestUtils.getManagedUser(who);
        managed.setScreenName(screenName);
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /** The controller over a mocked tier, for the failures a real database will not produce on demand. */
    private AccountController mockedController() {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        return withMessages(new AccountController(new CommentService(mocked.weblogger(), comment -> { })));
    }

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId("id-" + handle);
        w.setHandle(handle);
        return w;
    }

    private static User account(String userName) {
        User u = new User();
        u.setId("id-" + userName);
        u.setUserName(userName);
        u.setScreenName("Reader X");
        u.setFullName("Reader X");
        u.setEnabled(Boolean.TRUE);
        return u;
    }
}
