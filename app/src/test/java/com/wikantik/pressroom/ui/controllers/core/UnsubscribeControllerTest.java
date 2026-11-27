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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.util.Date;
import java.util.UUID;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.comments.UnsubscribeTokens;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * The one-click unsubscribe page (spec, Part F), driven through the real UI
 * security chain (CSRF on), the real interceptor and the real tier: signed
 * out, with no CSRF token, as a mail client's one-click POST arrives.
 */
class UnsubscribeControllerTest {

    private static final String PAGE = "/pressroom-ui/unsubscribe.rol";
    private static final String ISSUER = "https://issuer.test/unsubscribe";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    private User reader;
    private String token;
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

        /** Not itself disposable: see AccountControllerTest, which this follows. */
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
        UnsubscribeTokens unsubscribeTokens() {
            return new UnsubscribeTokens(TestUtils.weblogger());
        }

        @Bean
        UnsubscribeController unsubscribeController(UnsubscribeTokens tokens) {
            return new UnsubscribeController(tokens);
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
        reader = newReader("reader-unsub-" + UUID.randomUUID().toString().substring(0, 8));
        token = context.getBean(UnsubscribeTokens.class).issue(reader.getId(), UnsubscribeTokens.REPLIES);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocked != null) {
            mocked.detach();
        }
        ControllerTestFixture.useDefaultWeblogger();
        TestUtils.endSession(false);
        UserManager users = TestUtils.weblogger().getUserManager();
        if (users.getUserByUserName(reader.getUserName(), null) != null) {
            TestUtils.teardownUser(reader.getUserName());
        }
        TestUtils.endSession(true);
    }

    @Test
    void getShowsTheConfirmationAndChangesNothing() throws Exception {
        MvcResult result = perform(get(PAGE).param("t", token));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(".Unsubscribe", result.getModelAndView().getViewName());
        assertEquals("confirm", model(result, "unsubscribeState"));
        assertEquals(token, model(result, "token"));
        assertEquals("private, no-store", result.getResponse().getHeader("Cache-Control"));
        assertTrue(stored().isNotifyReplies(), "a GET (a mail scanner following links) changes nothing");
    }

    @Test
    void postTurnsReplyEmailsOffWhileSignedOut() throws Exception {
        MvcResult result = perform(post(PAGE).param("t", token));

        assertEquals(200, result.getResponse().getStatus(), "no sign-in and no CSRF token: the token authorises");
        assertEquals(".Unsubscribe", result.getModelAndView().getViewName());
        assertEquals("done", model(result, "unsubscribeState"));
        assertFalse(stored().isNotifyReplies());

        assertEquals("done", model(perform(post(PAGE).param("t", token)), "unsubscribeState"),
                "a second click says the same");
        assertTrue(stored().isKeepHistory(), "only the reply switch moves");
    }

    /**
     * What MockMvc can prove: the security chain and the controller create no
     * session for an anonymous unsubscribe POST. MockMvc does not render the JSP,
     * so this says nothing about the page itself; in a real container the GET's
     * confirmation form renders a CSRF token, and loading that token may create a
     * session for the visitor.
     */
    @Test
    void theChainAndControllerCreateNoSessionForAnAnonymousPost() throws Exception {
        MvcResult result = perform(post(PAGE).param("t", token));

        assertEquals("done", model(result, "unsubscribeState"));
        assertNull(result.getRequest().getSession(false));
    }

    /**
     * The page is anonymous on purpose: the signed token is the authority, so the
     * interceptor must neither demand a sign-in nor a weblog nor any permission.
     */
    @Test
    void thePageIsDeliberatelyAnonymous() {
        UnsubscribeController controller = new UnsubscribeController(context.getBean(UnsubscribeTokens.class));

        assertFalse(controller.isUserRequired(), "no sign-in: a reader opens this from their mail");
        assertFalse(controller.isWeblogRequired());
        assertTrue(controller.requiredWeblogPermissionActions().isEmpty());
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
        assertEquals("unsubscribe.title", controller.getPageTitle());
    }

    /** RFC 8058: the mail client POSTs the List-Unsubscribe URL with this form body and nothing else. */
    @Test
    void theOneClickPostWorks() throws Exception {
        MvcResult result = perform(post(PAGE + "?t=" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("List-Unsubscribe=One-Click"));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("done", model(result, "unsubscribeState"));
        assertFalse(stored().isNotifyReplies());
    }

    @Test
    void aBadTokenGetsTheErrorPageWith400() throws Exception {
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        String otherPurpose = context.getBean(UnsubscribeTokens.class).issue(reader.getId(), "newsletter");
        String gone = context.getBean(UnsubscribeTokens.class).issue("no-such-user-id", UnsubscribeTokens.REPLIES);
        for (RequestBuilder request : new RequestBuilder[] {
                get(PAGE).param("t", tampered), post(PAGE).param("t", tampered),
                get(PAGE), post(PAGE), post(PAGE).param("t", ""),
                post(PAGE).param("t", otherPurpose), post(PAGE).param("t", gone)}) {
            MvcResult result = perform(request);

            assertEquals(400, result.getResponse().getStatus());
            assertEquals(".Unsubscribe", result.getModelAndView().getViewName());
            assertEquals("invalid", model(result, "unsubscribeState"));
            assertNull(model(result, "token"), "a refused token is not echoed into the page");
        }
        assertTrue(stored().isNotifyReplies(), "nothing changed");
    }

    /** The exemption is this path's alone: another POST without a token is still refused by CSRF. */
    @Test
    void theCsrfExemptionCoversOnlyThisPage() throws Exception {
        MvcResult result = perform(post("/pressroom-ui/account!saveName.rol").param("screenName", "x"));

        assertEquals(403, result.getResponse().getStatus());
    }

    /** A save that cannot run is not reported as done (fail closed). */
    @Test
    void aStoreFailureIsNotReportedAsDone() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        User held = new User();
        held.setId("id-held");
        held.setUserName("held");
        UnsubscribeTokens tokens = new UnsubscribeTokens(TestUtils.weblogger());
        when(mocked.getUserManager().getUser("id-held")).thenReturn(held);
        doThrow(new WebloggerException("db down")).when(mocked.getUserManager()).saveUser(any());
        UnsubscribeController controller = ControllerTestFixture.withMessages(new UnsubscribeController(tokens));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.unsubscribe(tokens.issue("id-held", UnsubscribeTokens.REPLIES),
                ControllerTestFixture.requestFor(null), response, model);

        assertEquals(".Unsubscribe", view);
        assertEquals("error", model.get("unsubscribeState"));
        assertEquals(500, response.getStatus());
    }

    /** A lookup that cannot run refuses too: it is not "no such user", and not "done". */
    @Test
    void aLookupFailureIsAnErrorNotARefusalOrSuccess() throws Exception {
        mocked = MockWeblogger.create();
        ControllerTestFixture.useWeblogger(mocked.weblogger());
        UnsubscribeTokens tokens = new UnsubscribeTokens(TestUtils.weblogger());
        when(mocked.getUserManager().getUser(any())).thenThrow(new WebloggerException("db down"));
        UnsubscribeController controller = ControllerTestFixture.withMessages(new UnsubscribeController(tokens));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ExtendedModelMap model = new ExtendedModelMap();

        controller.confirm(tokens.issue("id-x", UnsubscribeTokens.REPLIES),
                ControllerTestFixture.requestFor(null), response, model);

        assertEquals("error", model.get("unsubscribeState"));
        assertEquals(500, response.getStatus());
    }

    // ------------------------------------------------------------- helpers

    private static MvcResult perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static Object model(MvcResult result, String name) {
        return result.getModelAndView().getModel().get(name);
    }

    private User stored() throws Exception {
        TestUtils.endSession(false);
        return TestUtils.weblogger().getUserManager().getUserByUserName(reader.getUserName(), null);
    }

    private static User newReader(String userName) throws Exception {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName("Una Subscriber");
        u.setFullName("Una Subscriber");
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject("sub-" + UUID.randomUUID());
        TestUtils.weblogger().getUserManager().addReader(u);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return u;
    }
}
