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
package com.wikantik.pressroom.ui.rendering;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogRedirect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one implementation of the redirect consultation: resolve, 301 with a
 * weblog-derived Location, best-effort hit bookkeeping, and the diagnostic
 * log line. The seams (mapper, PageServlet) only decide WHERE this runs;
 * everything about HOW it answers is pinned here.
 */
class RedirectResponderTest {

    private MockWeblogger mock;
    private WeblogRedirectManager redirects;
    private RedirectResponder responder;
    private Weblog weblog;

    /** Collects formatted messages logged to {@code pressroom.redirects}. */
    private final List<String> logged = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void setUp() throws Exception {
        mock = MockWeblogger.create();
        redirects = mock.weblogRedirectManager();
        responder = new RedirectResponder(mock.weblogger());

        weblog = new Weblog();
        weblog.setHandle("testblog");

        // The Location must derive from the WEBLOG (the vhost-wave rule:
        // never from the request), so the strategy is the seam we stub.
        when(mock.urlStrategy().getWeblogURL(weblog, null, true))
                .thenReturn("https://example.com/roller/testblog/");

        appender = new AbstractAppender("redirect-capture", null, null,
                true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                logged.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig(
                WeblogRedirectManager.LOG_NAME, Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger(WeblogRedirectManager.LOG_NAME, lc);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().removeLogger(WeblogRedirectManager.LOG_NAME);
        ctx.updateLoggers();
        appender.stop();
    }

    private WeblogRedirect rule(String source, String target) {
        WeblogRedirect rule = new WeblogRedirect();
        rule.setWeblog(weblog);
        rule.setSourcePath(source);
        rule.setTargetPath(target);
        rule.setOrigin(WeblogRedirect.Origin.MANUAL);
        return rule;
    }

    @Test
    void aMatchAnswersWithA301DerivedFromTheWeblog() throws Exception {
        when(redirects.resolve(weblog, "/old-page")).thenReturn(rule("/old-page", "/new-page"));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/roller/testblog/old-page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean answered = responder.answer(weblog, "/old-page", request, response);

        assertTrue(answered);
        assertEquals(HttpServletResponse.SC_MOVED_PERMANENTLY, response.getStatus());
        assertEquals("https://example.com/roller/testblog/new-page",
                response.getHeader("Location"));
    }

    @Test
    void theQueryStringSurvivesTheRedirect() throws Exception {
        when(redirects.resolve(weblog, "/old-page")).thenReturn(rule("/old-page", "/new-page"));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/roller/testblog/old-page");
        request.setQueryString("utm_source=old-newsletter&x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(responder.answer(weblog, "/old-page", request, response));

        assertEquals(
                "https://example.com/roller/testblog/new-page?utm_source=old-newsletter&x=1",
                response.getHeader("Location"));
    }

    /**
     * PageServlet sees the FORWARDED request, whose own uri/query name the
     * internal servlet path -- the original request's spelling lives in the
     * forward attributes, and both the Location's query and the log line
     * must use it.
     */
    @Test
    void aForwardedRequestUsesTheOriginalUriAndQuery() throws Exception {
        when(redirects.resolve(weblog, "/old-page")).thenReturn(rule("/old-page", "/new-page"));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/roller/pressroom-ui/rendering/page/testblog/old-page");
        request.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI,
                "/roller/testblog/old-page");
        request.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, "utm=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(responder.answer(weblog, "/old-page", request, response));

        assertEquals("https://example.com/roller/testblog/new-page?utm=1",
                response.getHeader("Location"));
        assertTrue(logged.get(0).contains("/roller/testblog/old-page?utm=1"),
                "the log names the url the reader asked for, not the internal forward: "
                        + logged.get(0));
    }

    @Test
    void theLogLineCarriesEveryDiagnosticField() throws Exception {
        WeblogRedirect matched = rule("/old-page", "/new-page");
        matched.setOrigin(WeblogRedirect.Origin.SLUG_HISTORY);
        when(redirects.resolve(weblog, "/old-page")).thenReturn(matched);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/roller/testblog/old-page");
        request.setQueryString("utm=1");
        request.addHeader("Referer", "https://linker.example/some-post");
        request.addHeader("User-Agent", "Googlebot/2.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(responder.answer(weblog, "/old-page", request, response));

        assertEquals(1, logged.size(), "one line per served redirect");
        String line = logged.get(0);
        assertTrue(line.contains("testblog"), "weblog handle: " + line);
        assertTrue(line.contains(matched.getId()), "rule id: " + line);
        assertTrue(line.contains("SLUG_HISTORY"), "origin: " + line);
        assertTrue(line.contains("/roller/testblog/old-page?utm=1"),
                "requested uri with query: " + line);
        assertTrue(line.contains("https://example.com/roller/testblog/new-page"),
                "target: " + line);
        assertTrue(line.contains("https://linker.example/some-post"),
                "referer: " + line);
        assertTrue(line.contains("Googlebot/2.1"), "user-agent: " + line);
    }

    @Test
    void aMatchRecordsItsHit() throws Exception {
        WeblogRedirect matched = rule("/old-page", "/new-page");
        when(redirects.resolve(weblog, "/old-page")).thenReturn(matched);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/roller/testblog/old-page");

        responder.answer(weblog, "/old-page", request, new MockHttpServletResponse());

        verify(redirects).recordHit(matched);
    }

    @Test
    void noMatchLeavesTheResponseUntouched() throws Exception {
        when(redirects.resolve(any(), anyString())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(responder.answer(weblog, "/never-was",
                new MockHttpServletRequest("GET", "/roller/testblog/never-was"), response));

        assertNull(response.getHeader("Location"));
        assertEquals(200, response.getStatus(), "MockHttpServletResponse default");
        verify(redirects, never()).recordHit(any());
        assertTrue(logged.isEmpty());
    }

    /**
     * A redirect is a favor granted on top of a settled outcome. When the
     * store cannot be asked, the safe degradation is the 404 that was
     * already decided -- log and step aside, never 500.
     */
    @Test
    void aResolverFailureDegradesToTheDecided404() throws Exception {
        when(redirects.resolve(any(), anyString()))
                .thenThrow(new WebloggerException("store unreachable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(responder.answer(weblog, "/old-page",
                new MockHttpServletRequest("GET", "/roller/testblog/old-page"), response));

        assertNull(response.getHeader("Location"));
    }

    @Test
    void aMissingWeblogRootDeclinesRatherThanEmittingABrokenLocation() throws Exception {
        Weblog rootless = new Weblog();
        rootless.setHandle("rootless");
        when(redirects.resolve(rootless, "/old")).thenReturn(rule("/old", "/new"));
        when(mock.urlStrategy().getWeblogURL(rootless, null, true)).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(responder.answer(rootless, "/old",
                new MockHttpServletRequest("GET", "/rootless/old"), response));
    }
}
