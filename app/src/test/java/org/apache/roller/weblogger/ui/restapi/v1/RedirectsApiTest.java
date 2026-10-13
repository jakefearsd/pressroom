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

import java.sql.Timestamp;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogRedirectManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogRedirect;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;

/**
 * Redirect-rule CRUD over the API: routing, status codes, the ownership
 * check (404 never 403), the manager-refusal-to-400 mapping, and the JSON
 * shape -- including the hit count and last-hit timestamp, which are the
 * observability half of the redirects spec.
 */
class RedirectsApiTest {

    private MockMvc mockMvc(RedirectsApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogRedirectManager redirectManager = mock(WeblogRedirectManager.class);
        when(weblogger.getWeblogRedirectManager()).thenReturn(redirectManager);
        return weblogger;
    }

    private RedirectsApi controllerFor(Weblogger weblogger) {
        RedirectsApi controller = new RedirectsApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogRedirect aRule(Weblog weblog, String id, String source, String target) {
        WeblogRedirect rule = new WeblogRedirect();
        rule.setId(id);
        rule.setWeblog(weblog);
        rule.setSourcePath(source);
        rule.setTargetPath(target);
        rule.setOrigin(WeblogRedirect.Origin.MANUAL);
        rule.setCreatedAt(new Timestamp(1724500000000L));
        return rule;
    }

    @Test
    void listReturnsRulesWithTheirHitBookkeeping() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogRedirect rule = aRule(weblog, "rule-1", "/old-page", "/new-page");
        rule.setHitCount(41);
        rule.setLastHitAt(new Timestamp(1724500001000L));
        when(weblogger.getWeblogRedirectManager().getRedirects(weblog))
                .thenReturn(List.of(rule));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/redirects").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json =
                new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("/old-page", json.get(0).get("source").asString());
        assertEquals("/new-page", json.get(0).get("target").asString());
        assertEquals("MANUAL", json.get(0).get("origin").asString());
        assertEquals(41, json.get(0).get("hitCount").asLong());
        assertTrue(json.get(0).hasNonNull("lastHitAt"),
                "the observability half must be readable over the API: " + body);
    }

    @Test
    void postCreatesAManualRule() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"/old-page\",\"target\":\"/new-page\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getWeblogRedirectManager()).saveRedirect(any());
        verify(weblogger).flush();
        tools.jackson.databind.JsonNode json =
                new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("/old-page", json.get("source").asString());
        assertEquals("MANUAL", json.get("origin").asString(),
                "the API can only ever mint MANUAL rules");
    }

    /**
     * saveRedirect's refusals (off-site target, chaining, duplicate source)
     * are validation with readable messages, and must surface as 400s --
     * not fall through the generic handler as opaque 500s.
     */
    @Test
    void aManagerRefusalIsBadRequestNotA500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogRedirectManager redirectManager = weblogger.getWeblogRedirectManager();
        doThrow(new WebloggerException("redirect target must be a weblog-relative path"))
                .when(redirectManager).saveRedirect(any());

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"/a\",\"target\":\"//evil.example/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger, never()).flush();
    }

    @Test
    void aMissingSourceOrTargetIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"/old\"}"))
                .andExpect(status().isBadRequest());
        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"/new\"}"))
                .andExpect(status().isBadRequest());

        verify(weblogger.getWeblogRedirectManager(), never()).saveRedirect(any());
    }

    /**
     * The security declaration IS the authorization: RollerHandlerInterceptor
     * reads these before any handler runs, so losing one would silently open
     * the endpoint. POST permission, same bucket as pages and categories --
     * redirects are blog-wide structure.
     */
    @Test
    void theSecurityDeclarationRequiresAUserAndPostPermissionOnTheWeblog() {
        RedirectsApi controller = new RedirectsApi();

        assertTrue(controller.isUserRequired());
        assertTrue(controller.isWeblogRequired());
        assertEquals(List.of(org.apache.roller.weblogger.pojos.WeblogPermission.POST),
                controller.requiredWeblogPermissionActions());
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
    }

    /** roller_weblog_redirect.source_path/target_path are varchar(255) (V028). */
    @Test
    void aPathLongerThanItsColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "/" + "a".repeat(255);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"" + tooLong + "\",\"target\":\"/new\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogRedirectManager(), never()).saveRedirect(any());
    }

    @Test
    void deleteRemovesARule() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogRedirect rule = aRule(weblog, "rule-1", "/old", "/new");
        when(weblogger.getWeblogRedirectManager().getRedirect("rule-1")).thenReturn(rule);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/redirects/{id}", "rule-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getWeblogRedirectManager()).removeRedirect(rule);
        verify(weblogger).flush();
    }

    /**
     * A resource the caller may not see is 404, never 403 -- a foreign
     * rule's id and a genuinely-missing id must be indistinguishable.
     */
    @Test
    void deleteIsNotFoundWhenTheRuleBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        WeblogRedirect foreign = aRule(aWeblog("someoneelse"), "rule-1", "/old", "/new");
        when(weblogger.getWeblogRedirectManager().getRedirect("rule-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/redirects/{id}", "rule-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogRedirectManager(), never()).removeRedirect(any());
    }

    @Test
    void deleteIsNotFoundForAnUnknownId() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getWeblogRedirectManager().getRedirect("nope")).thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/redirects/{id}", "nope")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingSourceNamesTheSourceField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"/new\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("source"));
    }

    @Test
    void aMissingTargetNamesTheTargetField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/redirects")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"/old\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("target"));
    }
}
