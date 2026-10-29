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
package com.wikantik.pressroom.ui.controllers.ajax;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.ThemeResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The endpoint the theme chooser calls to preview a theme before committing to
 * it.
 *
 * <p>{@code ThemeEdit.jsp} fetches this on every selection change and writes
 * the description straight into the page, so its two shapes matter: a JSON
 * array when asked for everything, a bare object when asked for one theme. Get
 * that wrong and the chooser shows nothing while logging a parse error nobody
 * reads -- which is exactly the class of failure a rendering test cannot see
 * and this endpoint had no test for at all.
 */
class ThemeDataServletTest {

    private ThemeDataServlet servlet;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        servlet = new ThemeDataServlet(weblogger.weblogger());
        request = new MockHttpServletRequest("GET", "/roller-ui/authoring/themedata");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    @Test
    void askingForEverythingReturnsAJsonArrayOfEveryEnabledTheme() throws Exception {
        // Built before the stubbing call, not inside thenReturn: Mockito treats a
        // mock() created mid-when() as an unfinished stubbing and fails.
        List<SharedTheme> all = List.of(theme("journal", "Journal"), theme("travel", "Travel"));
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(all);

        servlet.doGet(request, response);

        String body = response.getContentAsString();
        assertTrue(body.trim().startsWith("["),
                "the chooser parses this as an array: " + body);
        assertTrue(body.contains("\"journal\"") && body.contains("\"travel\""),
                "every enabled theme must be listed: " + body);
        assertTrue(response.getContentType().startsWith("application/json"),
                "got: " + response.getContentType());
    }

    /**
     * The single-theme answer is deliberately not wrapped in an array: the
     * page reads {@code data.description} off it directly.
     */
    @Test
    void askingForOneThemeReturnsThatThemeUnwrapped() throws Exception {
        SharedTheme portfolio = theme("portfolio", "Portfolio");
        when(weblogger.themeManager().getTheme("portfolio")).thenReturn(portfolio);
        request.setParameter("theme", "portfolio");

        servlet.doGet(request, response);

        String body = response.getContentAsString();
        assertFalse(body.trim().startsWith("["),
                "a single theme must not arrive wrapped in an array: " + body);
        assertTrue(body.contains("\"portfolio\""), "got: " + body);
    }

    /**
     * An id the theme manager rejects is an error, not an empty answer. The
     * chooser leaves the previous description in place on failure, which is
     * only correct if the failure is visible.
     */
    @Test
    void anUnknownThemeIdIsAnError() throws Exception {
        when(weblogger.themeManager().getTheme("nosuch"))
                .thenThrow(new WebloggerException("no such theme"));
        request.setParameter("theme", "nosuch");

        servlet.doGet(request, response);

        assertEquals(500, response.getStatus());
    }

    /** The chooser posts on some paths; both verbs must answer alike. */
    @Test
    void postIsAnAliasForGet() throws Exception {
        SharedTheme journal = theme("journal", "Journal");
        when(weblogger.themeManager().getTheme("journal")).thenReturn(journal);
        request.setMethod("POST");
        request.setParameter("theme", "journal");

        servlet.doPost(request, response);

        assertTrue(response.getContentAsString().contains("\"journal\""),
                "got: " + response.getContentAsString());
    }

    /**
     * The endpoint used to build its JSON by hand with {@code print}, so any
     * theme description carrying a quote or a newline -- both perfectly legal
     * in {@code theme.xml}, and a description is prose -- emitted a document no
     * parser accepts. jQuery answers unparseable JSON by never calling
     * {@code success}, so the failure showed up as a thumbnail and description
     * that silently never changed, with a console message nobody reads.
     *
     * <p>Parsed with the same {@code ObjectMapper} the servlet writes with, so
     * this asserts the response is JSON rather than that it has some particular
     * escaping.
     */
    @Test
    void aDescriptionWithNewlineAndQuoteIsValidJson() throws Exception {
        ThemeResource preview = mock(ThemeResource.class);
        when(preview.getPath()).thenReturn("awkward-preview.png");

        SharedTheme awkward = mock(SharedTheme.class);
        when(awkward.getId()).thenReturn("awkward");
        when(awkward.getName()).thenReturn("Awkward");
        when(awkward.getDescription()).thenReturn("line one\nline \"two\"");
        when(awkward.getPreviewImage()).thenReturn(preview);

        when(weblogger.themeManager().getTheme("awkward")).thenReturn(awkward);
        request.setParameter("theme", "awkward");

        servlet.doGet(request, response);

        String body = response.getContentAsString();
        JsonNode parsed = new ObjectMapper().readTree(body);
        assertEquals("line one\nline \"two\"", parsed.get("description").asText(),
                "the description must survive the round trip intact: " + body);
        assertEquals("/themes/awkward/awkward-preview.png",
                parsed.get("previewPath").asText(), "got: " + body);
    }

    /**
     * A theme stubbed with every field the endpoint reads. The preview image is
     * not optional: the servlet dereferences {@code getPreviewImage().getPath()}
     * unguarded, so a theme without one takes the chooser down with an NPE
     * rather than rendering without a thumbnail.
     */
    private static SharedTheme theme(String id, String name) {
        ThemeResource preview = mock(ThemeResource.class);
        when(preview.getPath()).thenReturn(id + "-preview.png");

        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getId()).thenReturn(id);
        when(theme.getName()).thenReturn(name);
        when(theme.getDescription()).thenReturn(name + " description");
        when(theme.getPreviewImage()).thenReturn(preview);
        return theme;
    }
}
