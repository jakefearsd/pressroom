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
package com.wikantik.pressroom.ui.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A password sign-in started from a page's Sign in link goes back to that
 * page: the login form posts the page's path as {@code return}. Anything that
 * is not a path under the context falls back to the old landing,
 * login-redirect, which picks the editor or the Main Menu.
 */
class ReturnPathLoginSuccessHandlerTest {

    private static final Authentication JAKE =
            new UsernamePasswordAuthenticationToken("jake", "n/a", java.util.List.of());

    private static MockHttpServletRequest post(String contextPath, String returnPath) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", contextPath + "/pressroom_j_security_check");
        request.setContextPath(contextPath);
        if (returnPath != null) {
            request.setParameter("return", returnPath);
        }
        return request;
    }

    private static String redirectAfter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ReturnPathLoginSuccessHandler().onAuthenticationSuccess(request, response, JAKE);
        return response.getRedirectedUrl();
    }

    @Test
    void aPageOnTheSiteIsWhereTheSignInGoesBack() throws Exception {
        assertEquals("/investing/entry/capital-gains?page=2",
                redirectAfter(post("", "/investing/entry/capital-gains?page=2")));
        assertEquals("/pressroom/investing/",
                redirectAfter(post("/pressroom", "/pressroom/investing/")));
    }

    @Test
    void withNoReturnTheOldLandingStands() throws Exception {
        assertEquals("/pressroom-ui/login-redirect.rol", redirectAfter(post("", null)));
        assertEquals("/pressroom/pressroom-ui/login-redirect.rol", redirectAfter(post("/pressroom", null)));
    }

    @Test
    void anythingButAPathUnderTheContextIsIgnored() throws Exception {
        for (String hostile : java.util.List.of("//evil.example/", "/\\evil.example/",
                "https://evil.example/", "/elsewhere/x", "/a\r\nSet-Cookie: x=1", "")) {
            assertEquals("/pressroom/pressroom-ui/login-redirect.rol",
                    redirectAfter(post("/pressroom", hostile)), "return " + hostile);
        }
    }

    @Test
    void anExplicitReturnBeatsASavedRequest() throws Exception {
        MockHttpServletRequest saved = new MockHttpServletRequest("GET", "/pressroom-ui/menu.rol");
        MockHttpServletRequest request = post("", "/investing/");
        request.setSession(saved.getSession());
        new HttpSessionRequestCache().saveRequest(saved, new MockHttpServletResponse());

        assertEquals("/investing/", redirectAfter(request));
        assertEquals(null, new HttpSessionRequestCache().getRequest(request, new MockHttpServletResponse()),
                "the saved request is spent, or a later sign-in would land on it");
    }

    @Test
    void aSavedRequestStillWinsOverTheDefault() throws Exception {
        MockHttpServletRequest saved = new MockHttpServletRequest("GET", "/pressroom-ui/authoring/comments.rol");
        saved.setQueryString("weblog=investing");
        MockHttpServletRequest request = post("", null);
        request.setSession(saved.getSession());
        new HttpSessionRequestCache().saveRequest(saved, new MockHttpServletResponse());

        assertEquals("http://localhost/pressroom-ui/authoring/comments.rol?weblog=investing&continue",
                redirectAfter(request));
    }
}
