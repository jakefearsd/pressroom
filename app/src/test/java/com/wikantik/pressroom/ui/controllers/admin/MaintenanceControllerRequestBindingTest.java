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
package com.wikantik.pressroom.ui.controllers.admin;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ViewResolver;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the actual Spring MVC request-parameter BINDING for {@link
 * MaintenanceController}'s three POST handlers, the one thing neither
 * {@link MaintenanceControllerTest} (calls the handler method directly, so
 * {@code @RequestParam} binding never runs) nor {@link MaintenanceJspTest}
 * (a source-text scan of the JSP) can see.
 *
 * <p>{@code Maintenance.jsp}'s weblog-picker placeholder {@code <option>} is
 * both {@code disabled} and, while nothing is chosen, {@code selected}. Per
 * WHATWG HTML's form-entry-list construction, a {@code <select>}'s option
 * contributes an entry only when it is selected AND NOT disabled -- so
 * leaving the placeholder untouched and clicking an action button submits a
 * POST with NO {@code weblogId} parameter at all, not an empty one. Before
 * the three {@code @RequestParam("weblogId")}s were relaxed to {@code
 * required = false}, that missing parameter failed Spring's argument
 * resolution with {@code MissingServletRequestParameterException} --
 * {@code DispatcherServlet}'s default exception handling turns that into
 * HTTP 400 before the controller method ever runs, which {@code
 * WebContainerConfig} then routes to the bare, unstyled, hardcoded-English
 * {@code 404.jsp} instead of the page-chrome {@code
 * maintenance.error.noSuchWeblog} error the controller and the JSP's own
 * comment both claimed would happen.
 *
 * <p>This is the third instance in this program of a safety property being
 * asserted at the model/source layer while the actual defect lived in how a
 * browser's rendered form submits (or fails to submit) an entry -- a plain
 * unit test calling Java methods, and a scan of JSP source text, are both
 * structurally blind to it. Only a test that runs a real request through
 * Spring's own argument-resolution machinery can catch it, which is what
 * standalone {@link MockMvc} (no full application context; see {@code
 * SecurityConfigTest} for why this project cannot use {@code
 * @SpringBootTest}/{@code @AutoConfigureMockMvc}) gives us here.
 */
class MaintenanceControllerRequestBindingTest {

    private MockWeblogger weblogger;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());

        MaintenanceController controller = ControllerTestFixture.withMessages(new MaintenanceController());

        // No real view resolution is needed -- these tests only care about
        // the HTTP status the request produced and the model the controller
        // populated, not rendered JSP output. A no-op ViewResolver/View pair
        // (both are single-abstract-method interfaces) avoids needing a real
        // application context or JSP pipeline, matching the "no full Boot
        // context" constraint SecurityConfigTest documents.
        ViewResolver noOpViewResolver = (viewName, locale) -> (model, request, response) -> { };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(noOpViewResolver)
                .build();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void flushCacheWithNoWeblogIdParameterAtAllReachesTheErrorPageNotA400() throws Exception {
        MvcResult result = mockMvc.perform(post("/pressroom-ui/admin/maintenance!flushCache.rol")
                        .requestAttr("authenticatedUser", new User()))
                .andExpect(status().isOk())
                .andReturn();

        assertNoSuchWeblogErrorInModel(result);
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void indexWithNoWeblogIdParameterAtAllReachesTheErrorPageNotA400() throws Exception {
        MvcResult result = mockMvc.perform(post("/pressroom-ui/admin/maintenance!index.rol")
                        .requestAttr("authenticatedUser", new User()))
                .andExpect(status().isOk())
                .andReturn();

        assertNoSuchWeblogErrorInModel(result);
        verify(weblogger.indexManager(), never()).rebuildWeblogIndex(any());
    }

    @Test
    void regenerateRenditionsWithNoWeblogIdParameterAtAllReachesTheErrorPageNotA400() throws Exception {
        MvcResult result = mockMvc.perform(post("/pressroom-ui/admin/maintenance!regenerateRenditions.rol")
                        .requestAttr("authenticatedUser", new User()))
                .andExpect(status().isOk())
                .andReturn();

        assertNoSuchWeblogErrorInModel(result);
        verify(weblogger.mediaFileManager(), never()).regenerateRenditions(any());
    }

    @SuppressWarnings("unchecked")
    private static void assertNoSuchWeblogErrorInModel(MvcResult result) {
        var modelMap = result.getModelAndView().getModel();
        var errors = (java.util.List<String>) modelMap.get("errors");
        assertTrue(errors != null && errors.contains("maintenance.error.noSuchWeblog"),
                "Expected a no-such-weblog error in the model, got: " + errors);
    }
}
