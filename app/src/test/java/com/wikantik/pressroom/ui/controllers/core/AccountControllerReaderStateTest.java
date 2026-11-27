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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.business.reader.ReaderStateService;
import com.wikantik.pressroom.business.reader.ReaderStateServiceAccess;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.util.GenericThrottle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
 * The account page as the reader's hub (0.18.0, Part E task E3): Saved,
 * Following and Reading history above the comments, each list's actions
 * posting back to the page and landing on their own section, the history
 * switch, and the newsletter offer. Driven through the real UI security chain
 * (CSRF on), the real interceptor and the real {@link ReaderStateService}
 * against the database, with listmonk mocked.
 */
class AccountControllerReaderStateTest {

    private static final String ACCOUNT = "/pressroom-ui/account.rol";
    private static final String UNSAVE = "/pressroom-ui/account!unsave.rol";
    private static final String UNFOLLOW = "/pressroom-ui/account!unfollow.rol";
    private static final String CLEAR_HISTORY = "/pressroom-ui/account!clearHistory.rol";
    private static final String SAVE_HISTORY = "/pressroom-ui/account!saveHistorySetting.rol";
    private static final String SUBSCRIBE = "/pressroom-ui/account!subscribe.rol";
    private static final String ISSUER = "https://issuer.test/account-reader-state";
    private static final String LIST_UUID = "6a7d2c4e-0000-4000-8000-000000000e03";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static volatile ListmonkClient listmonk;

    private final List<String> userNames = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    private User owner;
    private User reader;
    private Weblog blogA;
    private Weblog blogB;
    private WeblogEntry a1;
    private WeblogEntry a2;
    private WeblogEntry b1;
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

        /** The suite's tier through a view that is not a bean to dispose (AccountControllerTest says why). */
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

        @Bean(destroyMethod = "")
        Weblogger weblogger() {
            return TestUtils.weblogger();
        }

        @Bean
        CommentService commentService() {
            return new CommentService(TestUtils.weblogger(), comment -> { });
        }

        @Bean
        ReaderStateService readerStateService() {
            return ReaderStateServiceAccess.service(TestUtils.weblogger(), ReaderStateControllerTest::strategy,
                    new GenericThrottle(1000, 60_000, 100), () -> listmonk);
        }

        @Bean
        AccountController accountController(CommentService comments, ReaderStateService readers) {
            return new AccountController(comments, readers);
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
                "closing this test's context must leave the suite's runtime config attached");
    }

    @BeforeEach
    void setUp() throws Exception {
        listmonk = mock(ListmonkClient.class);
        when(listmonk.isUnconfigured()).thenReturn(false);
        owner = TestUtils.setupUser("arsowner");
        userNames.add(owner.getUserName());
        blogA = newWeblog("arsbloga");
        blogB = newWeblog("arsblogb");
        Weblog listed = TestUtils.getManagedWebsite(blogA);
        listed.setNewsletterListUuid(LIST_UUID);
        TestUtils.weblogger().getWeblogManager().saveWeblog(listed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        a1 = newEntry(blogA, "arsa1");
        a2 = newEntry(blogA, "arsa2");
        b1 = newEntry(blogB, "arsb1");
        reader = newReader("reader-arsreader1", "Ann Reader");
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

    // ------------------------------------------------------------- the page

    /**
     * The three lists, newest first: Saved, Following (with the offer where
     * the blog has a list), Reading history; and the history switch, on.
     */
    @Test
    void thePageShowsSavedFollowingAndHistoryNewestFirst() throws Exception {
        save(a1);
        save(b1);
        follow(blogB);
        follow(blogA);
        read(a2);
        read(a1);

        MvcResult result = perform(get(ACCOUNT).with(as(reader)));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(b1.getId(), a1.getId()), entryIds(model(result, "saved")));
        assertEquals(List.of(blogA.getId(), blogB.getId()), weblogIds(model(result, "following")));
        assertEquals(List.of(a1.getId(), a2.getId()), entryIds(model(result, "history")));
        assertEquals(Map.of(blogA.getId(), true, blogB.getId(), false), model(result, "newsletterOffered"));
        assertEquals(Boolean.TRUE, model(result, "keepHistory"));
        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model(result, "entryTitles");
        assertTrue(titles.keySet().containsAll(List.of(a1.getId(), a2.getId(), b1.getId())),
                "every listed entry has its plain-text label: " + titles);
    }

    /** An author's own page works: their saves and follows are their own. */
    @Test
    void anAuthorsAccountPageWorks() throws Exception {
        readers().save(TestUtils.getManagedUser(owner), managed(b1));
        TestUtils.endSession(true);

        MvcResult result = perform(get(ACCOUNT).with(user(owner.getUserName())));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(".Account", result.getModelAndView().getViewName());
        assertEquals(List.of(b1.getId()), entryIds(model(result, "saved")));
        assertEquals(List.of(), entryIds(model(result, "history")));
    }

    // ------------------------------------------------------------- the actions

    @Test
    void unsaveRoundTripsToTheSavedSection() throws Exception {
        save(a1);
        save(b1);

        MvcResult result = perform(post(UNSAVE).with(csrf()).with(as(reader)).param("entry", a1.getId()));

        assertEquals(302, result.getResponse().getStatus());
        assertEquals(ACCOUNT + "#saved", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.saved.removed"), flash(result, "messages"));
        assertEquals(List.of(b1.getId()), entryIds(model(perform(get(ACCOUNT).with(as(reader))), "saved")));
    }

    @Test
    void unfollowRoundTripsToTheFollowingSection() throws Exception {
        follow(blogA);
        follow(blogB);

        MvcResult result = perform(post(UNFOLLOW).with(csrf()).with(as(reader)).param("weblog", blogA.getId()));

        assertEquals(ACCOUNT + "#following", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.following.removed"), flash(result, "messages"));
        assertEquals(List.of(blogB.getId()), weblogIds(model(perform(get(ACCOUNT).with(as(reader))), "following")));
    }

    @Test
    void clearHistoryRoundTripsToTheHistorySection() throws Exception {
        read(a1);
        read(b1);

        MvcResult result = perform(post(CLEAR_HISTORY).with(csrf()).with(as(reader)));

        assertEquals(ACCOUNT + "#history", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.history.cleared"), flash(result, "messages"));
        assertEquals(List.of(), entryIds(model(perform(get(ACCOUNT).with(as(reader))), "history")));
        assertTrue(stored(reader).isKeepHistory(), "clearing is not switching off");
    }

    /**
     * "Keep my reading history" off (an unticked box sends nothing) clears
     * the history and stops recording; on again records again.
     */
    @Test
    void turningHistoryOffClearsItAndStopsRecording() throws Exception {
        read(a1);

        MvcResult off = perform(post(SAVE_HISTORY).with(csrf()).with(as(reader)));

        assertEquals(ACCOUNT + "#history", off.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.history.saved"), flash(off, "messages"));
        assertFalse(stored(reader).isKeepHistory());
        assertTrue(stored(reader).isNotifyReplies(), "the email switch is not this form's");
        MvcResult page = perform(get(ACCOUNT).with(as(reader)));
        assertEquals(List.of(), entryIds(model(page, "history")), "turning it off clears it");
        assertEquals(Boolean.FALSE, model(page, "keepHistory"));
        read(b1);
        assertEquals(List.of(), entryIds(model(perform(get(ACCOUNT).with(as(reader))), "history")),
                "and nothing more is recorded");

        MvcResult on = perform(post(SAVE_HISTORY).with(csrf()).with(as(reader)).param("keepHistory", "true"));

        assertEquals(ACCOUNT + "#history", on.getResponse().getRedirectedUrl());
        assertTrue(stored(reader).isKeepHistory());
        read(b1);
        assertEquals(List.of(b1.getId()), entryIds(model(perform(get(ACCOUNT).with(as(reader))), "history")));
    }

    /** "Email me new posts": each listmonk answer is its own calm message, back on Following. */
    @Test
    void subscribeAnswersEachOutcomeOnTheFollowingSection() throws Exception {
        follow(blogA);
        when(listmonk.subscribe(anyString(), anyString())).thenReturn(200, 409, 500);

        MvcResult confirm = subscribe(blogA);
        assertEquals(ACCOUNT + "#following", confirm.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.following.subscribe.confirm"), flash(confirm, "messages"));
        verify(listmonk).subscribe(reader.getEmailAddress(), LIST_UUID);

        assertEquals(List.of("account.following.subscribe.already"), flash(subscribe(blogA), "messages"));

        MvcResult down = subscribe(blogA);
        assertEquals(List.of("account.following.subscribe.unavailable"), flash(down, "errors"));
        assertEquals(ACCOUNT + "#following", down.getResponse().getRedirectedUrl());
    }

    /** Listmonk unreachable is the same calm retry line, never an error page. */
    @Test
    void subscribeFailureIsCalm() throws Exception {
        when(listmonk.subscribe(anyString(), anyString())).thenThrow(new IOException("connection refused"));

        MvcResult result = subscribe(blogA);

        assertEquals(ACCOUNT + "#following", result.getResponse().getRedirectedUrl());
        assertEquals(List.of("account.following.subscribe.unavailable"), flash(result, "errors"));
    }

    /** A blog with no list, or an unknown id, offers nothing: refused, and listmonk is never asked. */
    @Test
    void subscribeWhereNothingIsOfferedIsRefused() throws Exception {
        for (String id : List.of(blogB.getId(), "no-such-weblog")) {
            MvcResult result = perform(post(SUBSCRIBE).with(csrf()).with(as(reader)).param("weblog", id));
            assertEquals(ACCOUNT + "#following", result.getResponse().getRedirectedUrl());
            assertEquals(List.of("account.following.subscribe.notOffered"), flash(result, "errors"), id);
        }
        verify(listmonk, never()).subscribe(anyString(), anyString());
    }

    /** Every new action needs a signed-in account and the CSRF token, and does nothing otherwise. */
    @Test
    void eachActionNeedsASignedInAccountAndItsToken() throws Exception {
        save(a1);
        follow(blogA);
        read(a1);
        List<String> actions = List.of(UNSAVE, UNFOLLOW, CLEAR_HISTORY, SAVE_HISTORY, SUBSCRIBE);
        for (String action : actions) {
            MvcResult anonymous = perform(post(action).with(csrf())
                    .param("entry", a1.getId()).param("weblog", blogA.getId()));
            assertEquals("/pressroom-ui/login.rol", anonymous.getResponse().getRedirectedUrl(), action);
            MvcResult tokenless = perform(post(action).with(as(reader))
                    .param("entry", a1.getId()).param("weblog", blogA.getId()));
            assertEquals(403, tokenless.getResponse().getStatus(), action);
        }
        MvcResult page = perform(get(ACCOUNT).with(as(reader)));
        assertEquals(List.of(a1.getId()), entryIds(model(page, "saved")));
        assertEquals(List.of(blogA.getId()), weblogIds(model(page, "following")));
        assertEquals(List.of(a1.getId()), entryIds(model(page, "history")));
        assertTrue(stored(reader).isKeepHistory());
        verify(listmonk, never()).subscribe(anyString(), anyString());
    }

    // ------------------------------------------------------------- the words

    /** The delete confirmation says what goes: comments, saves, follows and the reading history. */
    @Test
    void theDeleteConfirmationMentionsSavesFollowsAndHistory() throws Exception {
        Properties words = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/ApplicationResources.properties"))) {
            words.load(in);
        }
        for (String key : List.of("account.delete.confirm", "account.delete.help")) {
            String text = words.getProperty(key);
            for (String part : List.of("comment", "save", "follow", "reading history")) {
                assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains(part), key + " names " + part + ": " + text);
            }
        }
    }

    /**
     * The sections in the spec's order, each its own anchor the "You" menu
     * links to, and "Answered by {author}" on a comment with a reply.
     */
    @Test
    void theSectionsAreInTheSpecsOrderWithTheirAnchors() throws Exception {
        String jsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsps/core/Account.jsp"));
        int last = -1;
        for (String anchor : List.of("saved", "following", "history", "comments", "email", "name", "delete")) {
            int at = jsp.indexOf("<section id=\"" + anchor + "\"");
            assertTrue(at > last, anchor + " comes next, as its own section");
            last = at;
        }
        assertTrue(jsp.contains("code=\"account.comments.answeredBy\""), "Answered by {author}");
    }

    // ------------------------------------------------------------- failing closed

    /** A list that cannot be read is shown empty, with an error, never a 500. */
    @Test
    void aListThatCannotBeReadShowsEmptyWithAnError() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        ReaderStateService failing = mock(ReaderStateService.class);
        when(failing.lists(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new WebloggerException("down"));
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), failing));
        User account = account("reader-x");
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        assertEquals(".Account", controller.execute(requestFor(account), model));

        assertEquals(List.of(), model.get("saved"));
        assertEquals(List.of(), model.get("following"));
        assertEquals(List.of(), model.get("history"));
        assertEquals(List.of("generic.error.check.logs"), errors(model));
    }

    /**
     * A reader-state failure degrades those sections only: "Your comments" still loads, and the delete
     * refusal is still worked out (a member's page does not grow a Delete button).
     */
    @Test
    void aReaderStateFailureStillShowsCommentsAndTheDeleteRefusal() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        ReaderStateService failing = mock(ReaderStateService.class);
        when(failing.lists(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new WebloggerException("down"));
        com.wikantik.pressroom.pojos.ReaderComment mine = new com.wikantik.pressroom.pojos.ReaderComment();
        when(mocked.commentManager().getCommentsByAuthor(any())).thenReturn(List.of(mine));
        when(mocked.userManager().getWeblogPermissions(any(User.class)))
                .thenReturn(List.of(mock(com.wikantik.pressroom.pojos.WeblogPermission.class)));
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), failing));
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        controller.execute(requestFor(account("reader-x")), model);

        assertEquals(List.of(mine), model.get("comments"));
        assertEquals("account.delete.member", model.get("deleteRefusal"));
        assertEquals(List.of("generic.error.check.logs"), errors(model));
    }

    /** The delete refusal fails closed: when it cannot be worked out, the page hides the Delete button. */
    @Test
    void aDeleteRefusalThatCannotBeWorkedOutHidesTheButton() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        when(mocked.userManager().getWeblogPermissions(any(User.class))).thenThrow(new WebloggerException("down"));
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), emptyLists()));
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        controller.execute(requestFor(account("reader-x")), model);

        assertEquals("generic.error.check.logs", model.get("deleteRefusal"));
    }

    /** Turning the history on with a save that cannot run is reported; nothing is cleared. */
    @Test
    void aFailedHistorySettingSaveIsReported() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        ReaderStateService readers = mock(ReaderStateService.class);
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), readers));
        User account = account("reader-x");
        doThrow(new WebloggerException("down")).when(mocked.userManager()).saveUser(account);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.saveHistorySetting("true", requestFor(account), flash);

        assertEquals("redirect:/pressroom-ui/account.rol#history", view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
        verify(readers, never()).clearHistory(any());
        verify(mocked.weblogger()).release();
    }

    /**
     * Turning the history off clears it first and saves the switch only
     * after: a clear that cannot run leaves the switch on and the history as
     * it was, never a switch that is off over a history that stayed.
     */
    @Test
    void turningHistoryOffClearsBeforeTheSwitchIsSaved() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        ReaderStateService readers = mock(ReaderStateService.class);
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), readers));
        User account = account("reader-x");
        doThrow(new WebloggerException("down")).when(readers).clearHistory(account);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.saveHistorySetting(null, requestFor(account), flash);

        assertEquals("redirect:/pressroom-ui/account.rol#history", view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(flash));
        verify(mocked.userManager(), never()).saveUser(any());
        assertTrue(account.isKeepHistory(), "the switch is not turned off over a history that stayed");
    }

    /** An unsave, unfollow or clear that cannot run is reported, not shown as done. */
    @Test
    void aFailedRemovalIsReported() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        ReaderStateService readers = mock(ReaderStateService.class);
        doThrow(new WebloggerException("down")).when(readers).unsave(any(), anyString());
        doThrow(new WebloggerException("down")).when(readers).unfollow(any(), anyString());
        doThrow(new WebloggerException("down")).when(readers).clearHistory(any());
        AccountController controller = withMessages(
                new AccountController(new CommentService(mocked.weblogger(), comment -> { }), readers));
        User account = account("reader-x");

        RedirectAttributesModelMap unsave = new RedirectAttributesModelMap();
        assertEquals("redirect:/pressroom-ui/account.rol#saved", controller.unsave("e1", requestFor(account), unsave));
        assertEquals(List.of("generic.error.check.logs"), flashErrors(unsave));
        RedirectAttributesModelMap unfollow = new RedirectAttributesModelMap();
        assertEquals("redirect:/pressroom-ui/account.rol#following",
                controller.unfollow("w1", requestFor(account), unfollow));
        assertEquals(List.of("generic.error.check.logs"), flashErrors(unfollow));
        RedirectAttributesModelMap clear = new RedirectAttributesModelMap();
        assertEquals("redirect:/pressroom-ui/account.rol#history", controller.clearHistory(requestFor(account), clear));
        assertEquals(List.of("generic.error.check.logs"), flashErrors(clear));
    }

    // ------------------------------------------------------------- helpers

    private MvcResult subscribe(Weblog weblog) throws Exception {
        return perform(post(SUBSCRIBE).with(csrf()).with(as(reader)).param("weblog", weblog.getId()));
    }

    private void save(WeblogEntry entry) throws Exception {
        readers().save(TestUtils.getManagedUser(reader), managed(entry));
        TestUtils.endSession(true);
        Thread.sleep(5);
    }

    private void follow(Weblog weblog) throws Exception {
        readers().follow(TestUtils.getManagedUser(reader), TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        Thread.sleep(5);
    }

    /** A read as the beacon records it, by the account as stored (its history switch included). */
    private void read(WeblogEntry entry) throws Exception {
        readers().recordRead(stored(reader), managed(entry));
        TestUtils.endSession(true);
        Thread.sleep(5);
    }

    private static ReaderStateService readers() {
        return context.getBean(ReaderStateService.class);
    }

    /** One request, then the end of its persistence session, as the request filter does. */
    private static MvcResult perform(RequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn();
        } finally {
            TestUtils.endSession(true);
        }
    }

    private static Object model(MvcResult result, String name) {
        return result.getModelAndView().getModel().get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> entryIds(Object entries) {
        return ((List<WeblogEntry>) entries).stream().map(WeblogEntry::getId).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> weblogIds(Object weblogs) {
        return ((List<Weblog>) weblogs).stream().map(Weblog::getId).toList();
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

    private static User stored(User who) throws Exception {
        return TestUtils.weblogger().getUserManager().getUserByUserName(who.getUserName(), null);
    }

    private static WeblogEntry managed(WeblogEntry e) throws Exception {
        return TestUtils.weblogger().getWeblogEntryManager().getWeblogEntry(e.getId());
    }

    private Weblog newWeblog(String handle) throws Exception {
        Weblog w = TestUtils.setupWeblog(handle, TestUtils.getManagedUser(owner));
        weblogIds.add(w.getId());
        TestUtils.endSession(true);
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

    /** A reader-state service whose every list is empty. */
    private static ReaderStateService emptyLists() throws Exception {
        ReaderStateService empty = mock(ReaderStateService.class);
        when(empty.lists(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.wikantik.pressroom.business.reader.ReaderLists(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), java.util.Set.of()));
        return empty;
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
