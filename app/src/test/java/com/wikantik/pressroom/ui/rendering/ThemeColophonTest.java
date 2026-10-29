/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
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

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import com.wikantik.pressroom.business.Colophon;
import com.wikantik.pressroom.ui.rendering.model.UtilitiesModel;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colophon on blog pages: AC3 (every page-type template of the four
 * shared themes ends with it) and AC2 (the macro escapes the raw
 * {@link Colophon} strings exactly once). The macro tests evaluate the real
 * {@code weblog.vm} macro through Velocity with a stub {@code $config}, so
 * each omission rule is checked against exact output, not source text:
 * Velocity prints an unresolved reference literally.
 */
class ThemeColophonTest {

    private static final Path THEMES = Path.of("src/main/webapp/themes");
    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");

    /**
     * Templates that are fragments or shells, not pages a reader lands on:
     * the day-list partial, the editor's live-preview shell (it renders into
     * an iframe, with no footer), and frontpage's header/decorator/entry/
     * directory-row partials. Everything not listed must show the colophon,
     * so a new template without it fails.
     */
    private static final Set<String> NOT_A_PAGE = Set.of(
            "_day.vm", "preview.vm", "_header.vm", "_decorator.vm", "_entry.vm", "_blogdirectory.vm");

    private static final Pattern COMMENTS = Pattern.compile("(?s)#\\*.*?\\*#|##[^\\r\\n]*");
    private static final Pattern MACRO_CALL = Pattern.compile(
            "(?m)^[ \\t]*#showColophon\\(\\$model\\.weblog\\)[ \\t]*$");
    private static final Pattern PARSE_CALL = Pattern.compile(
            "#includeTemplate\\(\\$model\\.weblog \"([^\"]+)\"\\)");

    static boolean callsColophon(String template) {
        return MACRO_CALL.matcher(COMMENTS.matcher(template).replaceAll("")).find();
    }

    // ------------------------------------------------------------------- AC3

    @Test
    void onlyARealMacroInvocationCountsAsTheColophonCall() {
        assertTrue(callsColophon("<footer>\n    #showColophon($model.weblog)\n</footer>"));
        assertFalse(callsColophon("## #showColophon($model.weblog)"));
        assertFalse(callsColophon("#* #showColophon($model.weblog) *#"));
        assertFalse(callsColophon("<p>call the showColophon macro</p>"));
    }

    @Test
    void everyHtmlTemplateOfEverySharedThemeShowsTheColophon() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> themes = Files.list(THEMES)) {
            for (Path theme : themes.filter(Files::isDirectory).toList()) {
                Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(theme.resolve("theme.xml").toFile()).getDocumentElement();
                NodeList templates = root.getElementsByTagName("template");
                for (int i = 0; i < templates.getLength(); i++) {
                    Element t = (Element) templates.item(i);
                    if (!"text/html".equals(text(t, "contentType"))) {
                        continue;
                    }
                    String file = text(t, "contentsFile");
                    if (NOT_A_PAGE.contains(file)) {
                        continue;
                    }
                    checked++;
                    if (!showsColophon(theme, file)) {
                        missing.add(theme.getFileName() + "/" + file);
                    }
                }
            }
        }
        assertTrue(checked >= 13, "expected to find the themes' page templates, found " + checked);
        assertTrue(missing.isEmpty(), "templates without the colophon: " + missing);
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent().trim();
    }

    /** The template calls the macro, or #includeTemplate-s one (frontpage's _footer) that does. */
    private static boolean showsColophon(Path theme, String file) throws Exception {
        String source = Files.readString(theme.resolve(file), StandardCharsets.UTF_8);
        if (callsColophon(source)) {
            return true;
        }
        var parsed = PARSE_CALL.matcher(COMMENTS.matcher(source).replaceAll(""));
        while (parsed.find()) {
            Path partial = theme.resolve(parsed.group(1) + ".vm");
            if (Files.exists(partial)
                    && callsColophon(Files.readString(partial, StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void frontpageNoLongerSaysPoweredByRoller() throws Exception {
        String footer = Files.readString(THEMES.resolve("frontpage/_footer.vm"), StandardCharsets.UTF_8);
        assertFalse(footer.contains("Powered by Roller"), footer);
    }

    // ------------------------------------------------------------------- AC2

    /** Stand-in for $config: hands back whatever record the test set. */
    public static final class StubConfig {
        private final Colophon colophon;

        StubConfig(Colophon colophon) {
            this.colophon = colophon;
        }

        public Colophon getColophon(Object weblog) {
            return colophon;
        }
    }

    /** Stand-in for $url; public, so Velocity can introspect it. */
    public static final class StubUrl {
        public String getLogin() {
            return "/roller-ui/login-redirect.rol";
        }
    }

    /** Stand-in for $text: the real bundle through MessageFormat, as MessageModel does. */
    public static final class StubText {
        private final ResourceBundle bundle = ResourceBundle.getBundle("ApplicationResources");

        public String get(String key) {
            return bundle.getString(key);
        }

        public String get(String key, List<Object> args) {
            return MessageFormat.format(bundle.getString(key), args.toArray());
        }
    }

    private static String render(Colophon colophon) throws Exception {
        VelocityEngine engine = new VelocityEngine();
        engine.init();
        VelocityContext context = new VelocityContext();
        context.put("config", new StubConfig(colophon));
        context.put("text", new StubText());
        // A real render always inits utils with the parsed request; here the
        // visitor is simply anonymous.
        context.put("utils", new UtilitiesModel() {
            @Override
            public boolean isUserAuthenticated() {
                return false;
            }
        });
        context.put("url", new StubUrl());
        context.put("model", new Object() {
            public Object getWeblog() {
                return null;
            }
        });
        StringWriter out = new StringWriter();
        engine.evaluate(context, out, "colophon",
                Files.readString(WEBLOG_VM, StandardCharsets.UTF_8) + "\n#showColophon($model.weblog)\n");
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static Colophon facts(String text, String holder, String privacy, String date, String revision) {
        return new Colophon("0.4.0", revision, date, text, holder, 2026, privacy);
    }

    @Test
    void colophonEscapesCustomCopyrightOnce() throws Exception {
        String html = render(facts("Maiia & <b>", "Blog", null, null, ""));
        assertTrue(html.contains("<p class=\"colophon-copyright\">Maiia &amp; &lt;b&gt;</p>"), html);
        assertFalse(html.contains("&amp;amp;"), html);
        assertFalse(html.contains("<b>"), html);
    }

    @Test
    void colophonDefaultUsesBlogNameEscapedOnce() throws Exception {
        String html = render(facts(null, "Fish & Chips", null, null, ""));
        assertTrue(html.contains("<p class=\"colophon-copyright\">© 2026 Fish &amp; Chips. All rights reserved.</p>"),
                html);
        assertFalse(html.contains("&amp;amp;"), html);
    }

    @Test
    void colophonOmitsCopyrightWithNoHolderAndNoText() throws Exception {
        String html = render(facts(null, "", null, null, ""));
        assertFalse(html.contains("colophon-copyright"), html);
        assertTrue(html.contains("Roller 0.4.0"), html);
    }

    @Test
    void colophonOmitsPrivacyLinkWhenUnset() throws Exception {
        assertFalse(render(facts(null, "B", null, null, "")).contains("colophon-privacy"));
        String html = render(facts(null, "B", "https://x.example/p?a=1&b=2", null, ""));
        assertTrue(html.contains(
                "<span class=\"colophon-privacy\"><a href=\"https://x.example/p?a=1&amp;b=2\">Privacy policy</a></span>"),
                html);
    }

    @Test
    void colophonOmitsReleasedWhenDateNull() throws Exception {
        assertFalse(render(facts(null, "B", null, null, "")).contains("released"));
        String html = render(facts(null, "B", null, "2026-10-24", ""));
        assertTrue(html.contains("<span class=\"colophon-released\">released 2026-10-24</span>"), html);
    }

    @Test
    void colophonOmitsRevision() throws Exception {
        String html = render(facts(null, "B", null, null, "abc1234"));
        assertTrue(html.contains("<span class=\"colophon-version\">Roller 0.4.0</span>"), html);
        assertFalse(html.contains("abc1234"), html);
    }

    @Test
    void colophonOmitsVersionWhenUnknownButKeepsTheWayIn() throws Exception {
        Colophon bare = new Colophon("", "", null, null, "B", 2026, null);
        String html = render(bare);
        assertFalse(html.contains("colophon-version"), html);
        assertTrue(html.contains("<p class=\"colophon-meta\"> <span class=\"colophon-account\">"
                + "<a href=\"/roller-ui/login-redirect.rol\">Sign in</a></span> </p>"),
                "with nothing else to say the meta line still offers Sign in: " + html);
        assertEquals(1, html.split("colophon-copyright", -1).length - 1);
    }
}
