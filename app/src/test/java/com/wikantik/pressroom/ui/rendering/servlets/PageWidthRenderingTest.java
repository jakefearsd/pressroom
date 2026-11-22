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
package com.wikantik.pressroom.ui.rendering.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * The blog setting Page width: a WIDE blog's pages carry the theme's wide
 * class on the body, and each theme's stylesheet answers that class by
 * widening the tokens every column and measure reads.
 */
class PageWidthRenderingTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");
    private static final Map<String, String> PREFIX =
            Map.of("journal", "qj", "portfolio", "pf", "travel", "tg", "frontpage", "fd");

    private String handle;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "pagewidth" + suffix;
        user = TestUtils.setupUser("pagewidthuser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("wide-post", TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void configure(String theme, String width) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setLocale("en_US");
        managed.setPageWidth(width);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet("/pressroom-ui/rendering/page", path);
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), path);
        return response.getContentAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel", "frontpage"})
    void aWideBlogWearsTheWideClassAndAStandardOneDoesNot(String theme) throws Exception {
        String prefix = PREFIX.get(theme);
        configure(theme, "WIDE");
        for (String path : List.of("/" + handle, "/" + handle + "/entry/wide-post")) {
            String body = render(path);
            assertTrue(body.contains("<body class=\"" + prefix + " " + prefix + "-wide\">"),
                    theme + " " + path + ":\n" + body);
        }

        configure(theme, "STANDARD");
        for (String path : List.of("/" + handle, "/" + handle + "/entry/wide-post")) {
            String body = render(path);
            assertTrue(body.contains("<body class=\"" + prefix + "\">"), theme + " " + path + ":\n" + body);
            assertFalse(body.contains(prefix + "-wide"), theme + " " + path + ":\n" + body);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel", "frontpage"})
    void theStylesheetWidensItsTokensUnderTheWideClass(String theme) throws Exception {
        String prefix = PREFIX.get(theme);
        configure(theme, "WIDE");
        String css = render("/" + handle + "/page/" + theme + "-custom.css");
        String selector = "\nbody." + prefix + "." + prefix + "-wide {";
        int at = css.indexOf(selector);
        assertTrue(at >= 0, "expected a rule " + selector.trim() + " in:\n" + css);
        String rule = css.substring(at, css.indexOf('}', at));
        assertTrue(rule.contains("--" + prefix + "-"), "it sets the theme's width tokens:\n" + rule);
    }

    /** Every theme template that opens a body opens it with the wide switch, so no page type is left out. */
    @Test
    void everyBodyTagCarriesTheWideSwitch() throws IOException {
        List<Path> templates;
        try (Stream<Path> files = Files.walk(THEMES)) {
            templates = files.filter(p -> p.toString().endsWith(".vm")).toList();
        }
        int bodies = 0;
        for (Path template : templates) {
            String vm = Files.readString(template, StandardCharsets.UTF_8);
            Matcher open = Pattern.compile("<body class=\"(qj|pf|tg|fd)").matcher(vm);
            while (open.find()) {
                bodies++;
                String prefix = open.group(1);
                String expected = "<body class=\"" + prefix + "#if($model.weblog.wideLayout) " + prefix
                        + "-wide#end\">";
                assertTrue(vm.startsWith(expected, open.start()), template + " opens its body without the switch");
            }
        }
        assertTrue(bodies >= 16, "found only " + bodies + " body tags under " + THEMES);
    }
}
