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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.business.jpa.JPAWebloggerImpl;
import com.wikantik.pressroom.business.reader.ReaderStateService;
import com.wikantik.pressroom.business.reader.ReaderStateServiceAccess;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.PressroomEvent;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.util.GenericThrottle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.anyString;
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
 * {@code /pressroom-ui/rendering/reader.rol}, driven through the real UI
 * security chain ({@link SecurityConfig}, CSRF on), the real
 * {@link PressroomHandlerInterceptor} and the real {@link ReaderStateService}
 * against the database: the JSON contract the page script and the account
 * page read (plan Part E, Task E2), and Review Focus 4 for subscribe.
 *
 * <p>The context is hand-built as {@code CommentsControllerTest}'s is, for the
 * reason {@code SecurityConfigTest}'s javadoc gives.
 */
class ReaderStateControllerTest {

    private static final String PATH = "/pressroom-ui/rendering/reader.rol";
    private static final String ISSUER = "https://issuer.test/reader-state-endpoint";
    private static final String LIST = "6b7d0b8e-1111-2222-3333-444455556666";
    /** The writes an account may make in the test context's window. */
    private static final int ALLOWED_WRITES = 12;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    private final List<String> userNames = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    /** Listmonk as the service sees it; each test sets its own. */
    private static volatile ListmonkClient listmonk;
    private User owner;
    private User reader;
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

        /** The suite's tier, through a view that is not itself a bean to dispose (see CommentsControllerTest). */
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
        ReaderStateService readerStateService() {
            return ReaderStateServiceAccess.service(TestUtils.weblogger(), ReaderStateControllerTest::strategy,
                    new GenericThrottle(ALLOWED_WRITES, 60_000, 100), () -> listmonk);
        }

        @Bean
        ReaderStateController readerStateController(ReaderStateService readers) {
            return new ReaderStateController(readers);
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
        owner = TestUtils.setupUser("rcowner");
        userNames.add(owner.getUserName());
        blog = newWeblog("rcblog");
        entry = newEntry(blog, "rcentry", PubStatus.PUBLISHED);
        reader = newReader("reader-rcreader1", "Ann Marie Reader");
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
    }

    // ------------------------------------------------------------- signed out

    @Test
    void anonymousGetIsSignedOutAndAnonymousPostIs401() throws Exception {
        MvcResult got = perform(get(PATH).param("entry", entry.getId()).param("weblog", "rcblog")
                .param("include", "places,continue,followed,readIds"));
        assertEquals(200, got.getResponse().getStatus());
        assertEquals("no-store", got.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("signedIn", false), json(got));
        assertNull(got.getRequest().getSession(false), "an anonymous visitor is given no session");

        MvcResult posted = postAs(anonymous(), Map.of("action", "save", "entry", entry.getId()));
        assertEquals(401, posted.getResponse().getStatus());
        assertEquals("no-store", posted.getResponse().getHeader("Cache-Control"));
        assertFalse(readers().isSaved(reader, entry.getId()));
    }

    @Test
    void aDisabledAccountIsSignedOutToGetAndRefusedToPost() throws Exception {
        disable(reader);

        assertEquals(Map.of("signedIn", false), json(perform(get(PATH).with(as(reader)))));
        assertEquals(403, postAs(as(reader), Map.of("action", "save", "entry", entry.getId()))
                .getResponse().getStatus());
    }

    @Test
    void anAccountWithoutCommentIsRefused() throws Exception {
        TestUtils.weblogger().getUserManager().revokeRole("reader", TestUtils.getManagedUser(reader));
        TestUtils.endSession(true);

        assertEquals(Map.of("signedIn", false), json(perform(get(PATH).with(as(reader)))));
        assertEquals(403, postAs(as(reader), Map.of("action", "save", "entry", entry.getId()))
                .getResponse().getStatus());
        assertFalse(readers().isSaved(reader, entry.getId()));
    }

    @Test
    void aMissingOrBadCsrfTokenIs403() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("action", "save", "entry", entry.getId()));
        MvcResult none = perform(post(PATH).with(as(reader)).contentType(MediaType.APPLICATION_JSON).content(body));
        assertEquals(403, none.getResponse().getStatus());
        MvcResult bad = perform(post(PATH).with(as(reader)).with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertEquals(403, bad.getResponse().getStatus());
        assertFalse(readers().isSaved(reader, entry.getId()), "a refused post stores nothing");

        assertEquals(200, postAs(as(reader), Map.of("action", "save", "entry", entry.getId()))
                .getResponse().getStatus(), "control: the same post with the token");
    }

    // ------------------------------------------------------------- the state

    @Test
    void theSignedInStateCarriesOnlyWhatWasAskedFor() throws Exception {
        MvcResult bare = perform(get(PATH).with(as(reader)));
        assertEquals(200, bare.getResponse().getStatus());
        assertEquals("no-store", bare.getResponse().getHeader("Cache-Control"));
        Map<String, Object> state = json(bare);
        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf"), state.keySet());
        assertEquals(true, state.get("signedIn"));
        assertEquals("Ann Marie Reader", state.get("name"));
        assertEquals("AR", state.get("initials"), "the first and last word's first letters");
        assertEquals(false, state.get("author"));
        Map<?, ?> token = (Map<?, ?>) state.get("csrf");
        assertEquals("X-CSRF-TOKEN", token.get("header"));
        assertFalse(((String) token.get("token")).isBlank());
        assertFalse(body(bare).contains("@example.com"), "no email address");
        assertFalse(body(bare).contains(reader.getUserName()), "no username");

        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf", "saved"),
                json(perform(get(PATH).param("entry", entry.getId()).with(as(reader)))).keySet(),
                "saved comes with entry");
        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf", "following", "newsletter"),
                json(perform(get(PATH).param("weblog", "rcblog").with(as(reader)))).keySet(),
                "following and newsletter come with weblog");
        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf", "places", "readIds"),
                json(perform(get(PATH).param("include", "places, readIds,unknown").with(as(reader)))).keySet(),
                "only the lists asked for; an unknown name is ignored");
        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf", "continueReading", "followedPosts"),
                json(perform(get(PATH).param("include", "continue,followed").with(as(reader)))).keySet());
        assertEquals(Set.of("signedIn", "name", "initials", "author", "csrf"),
                json(perform(get(PATH).param("include", "saved,following").with(as(reader)))).keySet(),
                "saved and following need an entry and a weblog to answer for");

        Map<String, Object> author = json(perform(get(PATH).with(user(owner.getUserName()))));
        assertEquals(true, author.get("author"), "an author also gets the main menu");
    }

    @Test
    void theListsAreFilledFromTheReadersState() throws Exception {
        WeblogEntry second = newEntry(blog, "rcsecond", PubStatus.PUBLISHED);
        setTitle(second, "Tom &amp; Jerry");
        assertEquals(200, postAs(as(reader), Map.of("action", "read", "entry", entry.getId()))
                .getResponse().getStatus());
        postAs(as(reader), Map.of("action", "read", "entry", second.getId()));
        postAs(as(reader), Map.of("action", "follow", "weblog", "rcblog"));
        postAs(as(reader), Map.of("action", "save", "entry", entry.getId()));

        Map<String, Object> state = json(perform(get(PATH).param("entry", entry.getId()).param("weblog", "rcblog")
                .param("include", "places,continue,followed,readIds").with(as(reader))));

        assertEquals(true, state.get("saved"));
        assertEquals(true, state.get("following"));
        assertEquals(Map.of("available", false), state.get("newsletter"), "the blog has no list");
        String home = TestUtils.weblogger().getUrlStrategy().getWeblogURL(blog, null, false);
        assertTrue(home.endsWith("/rcblog/"), home);
        assertEquals(List.of(Map.of("handle", "rcblog", "name", "Test Weblog", "url", home)), state.get("places"));
        assertEquals(List.of(
                        Map.of("id", second.getId(), "title", "Tom & Jerry", "url", home + "entry/rcsecond",
                                "weblogName", "Test Weblog"),
                        Map.of("id", entry.getId(), "title", "rcentry", "url", home + "entry/rcentry",
                                "weblogName", "Test Weblog")),
                state.get("continueReading"), "most recent first; the title as plain text");
        List<?> posts = (List<?>) state.get("followedPosts");
        assertEquals(2, posts.size());
        Map<?, ?> newest = (Map<?, ?>) posts.get(0);
        assertEquals(Set.of("id", "title", "url", "weblogName", "pubTime"), newest.keySet());
        assertEquals(second.getId(), newest.get("id"));
        assertEquals(second.getPubTime().toInstant(), Instant.parse((String) newest.get("pubTime")));
        assertEquals(List.of(second.getId(), entry.getId()), state.get("readIds"));

        Map<String, Object> unknown = json(perform(get(PATH).param("entry", "nope").param("weblog", "no-such")
                .with(as(reader))));
        assertEquals(false, unknown.get("saved"));
        assertEquals(false, unknown.get("following"));
        assertEquals(Map.of("available", false), unknown.get("newsletter"));
    }

    @Test
    void theNewsletterIsAvailableWithAListAnEmailAndListmonk() throws Exception {
        setNewsletterList(blog, LIST);
        assertEquals(Map.of("available", true), newsletter(reader));

        when(listmonk.isUnconfigured()).thenReturn(true);
        assertEquals(Map.of("available", false), newsletter(reader), "listmonk not configured");
        when(listmonk.isUnconfigured()).thenReturn(false);

        setEmail(reader, " ");
        assertEquals(Map.of("available", false), newsletter(reader), "an account with no email");
    }

    // ------------------------------------------------------------- writes

    @Test
    void saveFollowAndReadRoundTrip() throws Exception {
        MvcResult saved = postAs(as(reader), Map.of("action", "save", "entry", entry.getId()));
        assertEquals(200, saved.getResponse().getStatus());
        assertEquals("no-store", saved.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("ok", true, "saved", true), json(saved));
        assertTrue(readers().isSaved(reader, entry.getId()));
        assertEquals(Map.of("ok", true, "saved", false),
                json(postAs(as(reader), Map.of("action", "unsave", "entry", entry.getId()))));
        assertFalse(readers().isSaved(reader, entry.getId()));

        assertEquals(Map.of("ok", true, "following", true),
                json(postAs(as(reader), Map.of("action", "follow", "weblog", "rcblog"))));
        assertTrue(readers().isFollowing(reader, blog.getId()));
        assertEquals(Map.of("ok", true, "following", false),
                json(postAs(as(reader), Map.of("action", "unfollow", "weblog", "rcblog"))));
        assertFalse(readers().isFollowing(reader, blog.getId()));

        assertEquals(Map.of("ok", true), json(postAs(as(reader), Map.of("action", "read", "entry", entry.getId()))));
        assertEquals(List.of(entry.getId()), json(perform(get(PATH).param("include", "readIds")
                .with(as(reader)))).get("readIds"));
    }

    @Test
    void saveOfADraftIs404() throws Exception {
        WeblogEntry draft = newEntry(blog, "rcdraft", PubStatus.DRAFT);

        MvcResult result = postAs(as(reader), Map.of("action", "save", "entry", draft.getId()));
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        assertFalse(readers().isSaved(reader, draft.getId()));
        assertEquals(404, postAs(as(reader), Map.of("action", "read", "entry", draft.getId()))
                .getResponse().getStatus());
        assertEquals(404, postAs(as(reader), Map.of("action", "save", "entry", "no-such-entry"))
                .getResponse().getStatus());
    }

    @Test
    void followRefusesAnUnknownOrHiddenWeblog() throws Exception {
        for (String handle : List.of("no_such_blog", "not a handle", "site-space")) {
            assertEquals(404, postAs(as(reader), Map.of("action", "follow", "weblog", handle))
                    .getResponse().getStatus(), handle);
        }
        setVisible(blog, false);
        assertEquals(404, postAs(as(reader), Map.of("action", "follow", "weblog", "rcblog"))
                .getResponse().getStatus(), "a hidden blog cannot be followed");
    }

    /**
     * Review minor 1, ruled: unfollow answers 200 {following:false} for any
     * handle, so a hidden or inactive blog's handle cannot be told from an
     * unknown one, and a follow made before a blog went hidden can always be
     * removed (the account page lists it by handle).
     */
    @Test
    void unfollowAnswersAlikeForUnknownHiddenAndStaleHandles() throws Exception {
        Weblog inactive = newWeblog("rcinactive");
        setActive(inactive, false);
        Map<String, Object> answer = Map.of("ok", true, "following", false);
        for (String handle : List.of("no_such_blog", "not a handle", "site-space", "rcinactive")) {
            MvcResult result = postAs(as(reader), Map.of("action", "unfollow", "weblog", handle));
            assertEquals(200, result.getResponse().getStatus(), handle);
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
            assertEquals(answer, json(result), handle);
        }

        postAs(as(reader), Map.of("action", "follow", "weblog", "rcblog"));
        setVisible(blog, false);
        assertEquals(answer, json(postAs(as(reader), Map.of("action", "unfollow", "weblog", "rcblog"))),
                "a stale follow of a blog hidden since is removed");
        setVisible(blog, true);
        assertFalse(readers().isFollowing(reader, blog.getId()), "the row is gone, not just hidden");
    }

    @Test
    void savedIsFalseOnceTheEntryIsUnpublished() throws Exception {
        postAs(as(reader), Map.of("action", "save", "entry", entry.getId()));
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setStatus(PubStatus.DRAFT);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(false, json(perform(get(PATH).param("entry", entry.getId()).with(as(reader)))).get("saved"),
                "the constraint is for every row: an invisible entry is not reported saved");
    }

    /** Review minor 6: refusals Spring makes before the handler runs are no-store too. */
    @Test
    void anUnreadableBodyOrTheWrongTypeIsRefusedNoStore() throws Exception {
        MvcResult malformed = perform(post(PATH).with(csrf()).with(as(reader))
                .contentType(MediaType.APPLICATION_JSON).content("{not json"));
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("no-store", malformed.getResponse().getHeader("Cache-Control"));

        MvcResult text = perform(post(PATH).with(csrf()).with(as(reader))
                .contentType(MediaType.TEXT_PLAIN).content("save"));
        assertEquals(415, text.getResponse().getStatus());
        assertEquals("no-store", text.getResponse().getHeader("Cache-Control"));
        assertFalse(readers().isSaved(reader, entry.getId()));
    }

    @Test
    void aMalformedActionIs400() throws Exception {
        for (Map<String, String> bad : List.of(Map.of("action", "delete", "entry", entry.getId()),
                Map.of("entry", entry.getId()), Map.of("action", "save"), Map.of("action", "unsave"),
                Map.of("action", "follow"), Map.of("action", "subscribe"))) {
            MvcResult result = postAs(as(reader), bad);
            assertEquals(400, result.getResponse().getStatus(), bad.toString());
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        }
    }

    @Test
    void writesAreThrottledAt429() throws Exception {
        User busy = newReader("reader-rcbusy", "Busy Reader");
        for (int i = 0; i < ALLOWED_WRITES; i++) {
            assertEquals(200, postAs(as(busy), Map.of("action", "read", "entry", entry.getId()))
                    .getResponse().getStatus(), "write " + (i + 1));
        }
        MvcResult refused = postAs(as(busy), Map.of("action", "save", "entry", entry.getId()));
        assertEquals(429, refused.getResponse().getStatus());
        assertEquals("no-store", refused.getResponse().getHeader("Cache-Control"));
        assertFalse(readers().isSaved(busy, entry.getId()));
        assertEquals(200, perform(get(PATH).with(as(busy))).getResponse().getStatus(), "reads are not limited");
    }

    // ------------------------------------------------------------- subscribe (Review Focus 4)

    @Test
    void subscribeConfirmsAndRecordsTheEvent() throws Exception {
        setNewsletterList(blog, LIST);
        when(listmonk.subscribe("reader-rcreader1@example.com", LIST)).thenReturn(200);

        MvcResult result = subscribe(reader);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("ok", true, "outcome", "confirm"), json(result));
        assertEquals(1, subscribedEvents());
    }

    @Test
    void anAlreadySubscribedAddressIsAlreadyAndRecordsNothing() throws Exception {
        setNewsletterList(blog, LIST);
        when(listmonk.subscribe(anyString(), anyString())).thenReturn(409);

        MvcResult result = subscribe(reader);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(Map.of("ok", true, "outcome", "already"), json(result));
        assertEquals(0, subscribedEvents());
    }

    @Test
    void subscribeFailureIsCalm() throws Exception {
        setNewsletterList(blog, LIST);
        when(listmonk.subscribe(anyString(), anyString())).thenThrow(new IOException("listmonk is down"));

        MvcResult down = subscribe(reader);
        assertEquals(503, down.getResponse().getStatus());
        assertEquals("no-store", down.getResponse().getHeader("Cache-Control"));
        assertEquals(Map.of("outcome", "unavailable"), json(down));
        assertEquals(0, subscribedEvents(), "nothing is recorded");

        ListmonkClient failing = mock(ListmonkClient.class);
        when(failing.subscribe(anyString(), anyString())).thenReturn(500);
        listmonk = failing;
        MvcResult error = subscribe(reader);
        assertEquals(503, error.getResponse().getStatus());
        assertEquals(Map.of("outcome", "unavailable"), json(error));
        assertEquals(0, subscribedEvents());
    }

    @Test
    void subscribeWithoutAListAnEmailOrListmonkIs404() throws Exception {
        assertEquals(404, subscribe(reader).getResponse().getStatus(), "the blog has no list");

        setNewsletterList(blog, LIST);
        when(listmonk.isUnconfigured()).thenReturn(true);
        assertEquals(404, subscribe(reader).getResponse().getStatus(), "listmonk is not configured");
        when(listmonk.isUnconfigured()).thenReturn(false);

        setEmail(reader, "");
        assertEquals(404, subscribe(reader).getResponse().getStatus(), "the account has no email");

        setVisible(blog, false);
        assertEquals(404, subscribe(owner).getResponse().getStatus(), "a hidden blog");
        verify(listmonk, never()).subscribe(anyString(), anyString());
    }

    @Test
    void initialsComeFromTheFirstAndLastWords() {
        assertEquals("AR", ReaderStateController.initials("Ann Marie Reader"));
        assertEquals("J", ReaderStateController.initials("  jake "));
        assertEquals("ÉZ", ReaderStateController.initials("élodie zola"));
        assertEquals("?", ReaderStateController.initials("   "));
        assertEquals("?", ReaderStateController.initials(null));
    }

    @Test
    void theProductionServiceResolvesTheStrategyOnFirstUse() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<JPAPersistenceStrategy> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(strategy());
        ReaderStateService service = new ReaderStateService(TestUtils.weblogger(), provider);
        verify(provider, never()).getObject();

        assertFalse(service.isSaved(reader, entry.getId()));
        verify(provider).getObject();
        assertTrue(service.admitWrite(reader), "the configured limit admits a first write");
    }

    // ------------------------------------------------------------- helpers

    private MvcResult subscribe(User who) throws Exception {
        return postAs(as(who), Map.of("action", "subscribe", "weblog", "rcblog"));
    }

    private Object newsletter(User who) throws Exception {
        return json(perform(get(PATH).param("weblog", "rcblog").with(as(who)))).get("newsletter");
    }

    private long subscribedEvents() throws Exception {
        long n = TestUtils.weblogger().getEventManager().getEvents(TestUtils.getManagedWebsite(blog), 50).stream()
                .filter(e -> e.getEventType() == PressroomEvent.EventType.NEWSLETTER_SUBSCRIBED).count();
        TestUtils.endSession(false);
        return n;
    }

    private static ReaderStateService readers() {
        return context.getBean(ReaderStateService.class);
    }

    private static MvcResult postAs(RequestPostProcessor who, Map<String, String> payload) throws Exception {
        return perform(post(PATH).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new LinkedHashMap<>(payload))));
    }

    /** One request, then the end of its persistence session, as the request filter does. */
    private static MvcResult perform(RequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn();
        } finally {
            TestUtils.endSession(true);
        }
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

    static JPAPersistenceStrategy strategy() {
        try {
            Field field = JPAWebloggerImpl.class.getDeclaredField("strategy");
            field.setAccessible(true);
            return (JPAPersistenceStrategy) field.get(TestUtils.weblogger());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Weblog newWeblog(String handle) throws Exception {
        Weblog w = TestUtils.setupWeblog(handle, owner);
        weblogIds.add(w.getId());
        TestUtils.endSession(true);
        return w;
    }

    private WeblogEntry newEntry(Weblog weblog, String anchor, PubStatus status) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(owner), status);
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

    private static void setEmail(User who, String email) throws Exception {
        UserManager users = TestUtils.weblogger().getUserManager();
        User current = users.getUserByUserName(who.getUserName(), null);
        current.setEmailAddress(email);
        users.saveUser(current);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setNewsletterList(Weblog weblog, String list) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setNewsletterListUuid(list);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setVisible(Weblog weblog, boolean visible) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setVisible(visible);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setActive(Weblog weblog, boolean active) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setActive(active);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setTitle(WeblogEntry e, String storedTitle) throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(e);
        managed.setTitle(storedTitle);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
