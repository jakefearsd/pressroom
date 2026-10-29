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
package com.wikantik.pressroom.ui;

import jakarta.el.ELProcessor;
import org.apache.taglibs.standard.functions.Functions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stored text an author, reader or site admin typed, printed by the admin
 * JSPs, renders as text: a planted {@code <img src=x onerror=...//>} comes
 * out as {@code &lt;img}, never as a tag.
 *
 * <p>The unit suite cannot compile a JSP (ErrorPageContentTest says why), so
 * this renders the one line that prints each field: every EL expression on it
 * is evaluated by the real EL implementation, with {@code fn:escapeXml}
 * bound to JSTL's own function and {@code rc:getProp} to {@link #PROPS}. An
 * expression handed to a tag attribute renders into the attribute as it
 * would reach the tag, so a {@code <c:out escapeXml="false">} or a
 * {@code <spring:message text>} (neither escapes) shows the payload raw.
 * The tree-wide guard that no printed expression goes unescaped is
 * JspConsistencyTest.everyPrintedExpressionIsEscapedOrKnownSafe; this pins
 * what the fixed lines actually print.
 */
public class StoredTextRenderingTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    /** Body text with an unterminated tag: Utilities.removeHTML keeps it verbatim. */
    private static final String PAYLOAD = "<img src=x onerror=alert(1)//";

    private static final Pattern EL_EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");

    /** The runtime properties {@code rc:getProp} answers from. */
    private static final Map<String, String> PROPS = new HashMap<>();

    @Test
    void anUntitledEntrysTitleIsTextInTheTrash() throws IOException {
        Map<String, Object> beans = untitledEntryBeans("entry");

        assertRendersAsText(render("editor/Trash.jsp", "<td>${", beans));
    }

    @Test
    void anUntitledEntrysTitleIsTextOnTheReadersAccountPage() throws IOException {
        Map<String, Object> beans = untitledEntryBeans("entry");
        beans.put("c", Map.of("entry", beans.get("entry")));
        beans.put("urls", new Urls());

        assertRendersAsText(render("core/Account.jsp", "urls.entry(c.entry)", beans));
    }

    @Test
    void anUntitledEntrysTitleIsTextInTheBrowserTab() throws IOException {
        for (String layout : new String[] {"tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp"}) {
            Map<String, Object> beans = new HashMap<>();
            beans.put("tabTitle", PAYLOAD + " — Edit entry");
            beans.put("pageTitle", "weblogEdit.title.editEntry");
            beans.put("actionWeblog", Map.of("handle", "blog"));
            PROPS.put("site.shortName", "Site");

            String title = render(layout, "<title>", beans);

            assertRendersAsText(title);
            assertTrue(title.contains("&lt;img"), layout + ": the title text is missing from " + title);
        }
    }

    @Test
    void theSiteNamesAreTextInTheBannerAndTheBrowserTab() throws IOException {
        PROPS.put("site.name", PAYLOAD);
        PROPS.put("site.shortName", PAYLOAD);

        assertRendersAsText(render("tiles/bannerStatus.jsp", "navbar-brand", new HashMap<>()));
        for (String layout : new String[] {"tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp",
                "tiles/tiles-loginpage.jsp", "tiles/tiles-popuppage.jsp", "tiles/tiles-simplepage.jsp",
                "tiles/tiles-errorpage.jsp"}) {
            Map<String, Object> beans = new HashMap<>();
            beans.put("pageTitle", "title");
            beans.put("tabTitle", "");
            beans.put("actionWeblog", Map.of("handle", "blog"));
            String title = render(layout, "<title>", beans);
            assertTrue(title.contains("&lt;img"), layout + ": the short name is not rendered as text: " + title);
        }
    }

    @Test
    void theSignedInUserNameIsText() throws IOException {
        Map<String, Object> beans = new HashMap<>();
        beans.put("authenticatedUser", Map.of("userName", PAYLOAD));

        assertRendersAsText(render("tiles/userStatus.jsp", "authenticatedUser.userName", beans));
    }

    /** A weblog's "about" is a plain-text description: the settings form takes text and themes strip tags. */
    @Test
    void aWeblogsAboutTextIsTextOnTheMainMenu() throws IOException {
        Map<String, Object> beans = new HashMap<>();
        beans.put("perms", Map.of("weblog", Map.of("about", PAYLOAD)));

        assertRendersAsText(render("core/MainMenu.jsp", "perms.weblog.about", beans));
    }

    // ------------------------------------------------------------------

    private static Map<String, Object> untitledEntryBeans(String var) {
        Map<String, Object> beans = new HashMap<>();
        // Both the stored shape and the controller's plain label carry the
        // payload, so the line fails whichever one it prints raw.
        beans.put(var, Map.of("id", "e1", "displayTitle", PAYLOAD));
        beans.put("entryTitles", Map.of("e1", PAYLOAD));
        return beans;
    }

    private static void assertRendersAsText(String rendered) {
        assertFalse(rendered.contains("<img"), "the planted tag rendered as markup: " + rendered);
    }

    /** The first non-comment line of {@code jsp} holding {@code marker}, with its EL evaluated. */
    private static String render(String jsp, String marker, Map<String, Object> beans) throws IOException {
        String source = Files.readString(JSPS.resolve(jsp), StandardCharsets.UTF_8)
                .replaceAll("(?s)<%--.*?--%>", "");
        String line = source.lines().filter(l -> l.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError(jsp + " has no line holding " + marker));

        ELProcessor el = new ELProcessor();
        try {
            el.defineFunction("fn", "escapeXml", Functions.class.getMethod("escapeXml", String.class));
            el.defineFunction("rc", "getProp", StoredTextRenderingTest.class.getMethod("prop", String.class));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        beans.forEach(el::defineBean);

        Matcher m = EL_EXPRESSION.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = el.eval(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        return m.appendTail(out).toString();
    }

    /** {@code rc:getProp}, answering from {@link #PROPS}. */
    public static String prop(String name) {
        return PROPS.getOrDefault(name, "");
    }

    /** {@code urls}: an entry's permalink. */
    public static final class Urls {
        public String entry(Object entry) {
            return "/blog/entry/e1";
        }
    }
}
