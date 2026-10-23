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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans over the template, stylesheet and theme screens
 * ({@code Templates.jsp}, {@code TemplatesSidebar.jsp}, {@code TemplateEdit.jsp},
 * {@code StylesheetEdit.jsp}, {@code ThemeEdit.jsp}) from the 2026-10
 * fit-and-finish pass.
 *
 * <p>None of this can fail at compile time: a raw {@code ${...}} renders, a
 * missing label renders, a jQuery {@code .html()} sink renders. The scans are
 * the only thing standing between a later edit and the same defect coming
 * back, which is why they read the files rather than trust a review.
 */
class TemplateJspsTest {

    private static final Path EDITOR = Path.of("src/main/webapp/WEB-INF/jsps/editor");
    private static final Path TEMPLATES = EDITOR.resolve("Templates.jsp");
    private static final Path TEMPLATES_SIDEBAR = EDITOR.resolve("TemplatesSidebar.jsp");
    private static final Path TEMPLATE_EDIT = EDITOR.resolve("TemplateEdit.jsp");
    private static final Path STYLESHEET_EDIT = EDITOR.resolve("StylesheetEdit.jsp");
    private static final Path THEME_EDIT = EDITOR.resolve("ThemeEdit.jsp");
    private static final Path SESSION_EXPIRY_BAR = EDITOR.resolve("SessionExpiryBar.jsp");

    private static final String SESSION_EXPIRY_INCLUDE =
            "<jsp:include page=\"/WEB-INF/jsps/editor/SessionExpiryBar.jsp\"/>";

    /**
     * Author-typed values these five files print. Each is free text with no
     * save-time escaping: a template's description ({@code TemplateEditBean}
     * copies it verbatim), the stylesheet source, the manual content type,
     * the add form's echo of the typed name, and the theme name (read from
     * the installed theme's descriptor). Any access chain ending in one of
     * these, printed bare, is a violation.
     */
    private static final List<String> AUTHOR_TYPED = List.of(
            "description",
            "contentsStandard",
            "manualContentType",
            "newTmplName",
            "currentThemeName");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    private static String markup(Path jsp) throws IOException {
        return read(jsp).replaceAll("(?s)<%--.*?--%>", "");
    }

    private static String scripts(String markup) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("(?s)<script[^>]*>(.*?)</script>").matcher(markup);
        while (m.find()) {
            out.append(m.group(1)).append('\n');
        }
        return out.toString();
    }

    // --- escaping (admin finding 6) ---

    @Test
    void authorTypedValuesNeverRenderRaw() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path jsp : List.of(TEMPLATES, TEMPLATES_SIDEBAR, TEMPLATE_EDIT, STYLESHEET_EDIT, THEME_EDIT)) {
            String src = markup(jsp);
            for (String expr : AUTHOR_TYPED) {
                Matcher m = Pattern.compile("\\$\\{\\s*(?:[A-Za-z][A-Za-z0-9_]*\\.)*"
                        + Pattern.quote(expr) + "\\s*\\}").matcher(src);
                while (m.find()) {
                    violations.add(jsp.getFileName() + ": " + m.group());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Wrap these in ${fn:escapeXml(...)} -- a quote breaks the attribute, a "
                        + "</textarea> ends the editor, a <script> runs: " + violations);
    }

    @Test
    void theTemplateEditorsScriptReadsNoServerValueAndWritesNoMarkup() throws IOException {
        // The link preview used $("#linkPreview").html(link): the typed link
        // became markup. And the page's values were spliced into JS string
        // literals, where fn:escapeXml is the wrong escape (a backslash ends
        // the string, an apostrophe arrives as the literal text &#039;). They
        // ride in data-* attributes now, which have exactly one parser.
        String script = scripts(markup(TEMPLATE_EDIT));
        assertFalse(script.contains(".html("), "use textContent, not a markup sink");
        assertFalse(script.contains("${"), "no EL inside the script; read data-* attributes");
        assertFalse(script.contains("window.alert"), "say it on the page, not in a modal alert box");
    }

    // --- the add form keeps what the reader typed (admin finding 16) ---

    @Test
    void theAddFormEchoesTheTypedNameEscaped() throws IOException {
        assertTrue(markup(TEMPLATES_SIDEBAR).contains("value=\"${fn:escapeXml(newTmplName)}\""),
                "the name field must render the typed name back, escaped");
    }

    // --- session expiry and the main editing field (admin finding 23) ---

    @Test
    void bothSourceEditorsWarnBeforeTheSessionExpires() throws IOException {
        for (Path jsp : List.of(TEMPLATE_EDIT, STYLESHEET_EDIT)) {
            assertTrue(markup(jsp).contains(SESSION_EXPIRY_INCLUDE),
                    jsp.getFileName() + " must include the session-expiry bar");
        }
        String bar = read(SESSION_EXPIRY_BAR);
        assertTrue(bar.contains("id=\"sessionExpiryBar\""), "the bar keeps PageEdit's id");
        assertTrue(bar.contains("data-timeout=\"${pageContext.session.maxInactiveInterval}\""),
                "the warning is local arithmetic against the container's own timeout");
        assertTrue(bar.contains("<spring:message code=\"session.expiringSoon\"/>"),
                "the bar says the same sentence PageEdit's does");
        assertTrue(bar.contains("(timeout - 120) * 1000"), "it warns two minutes ahead");
    }

    @Test
    void theMainEditingFieldIsALabelledFormControl() throws IOException {
        assertLabelledFormControl(markup(TEMPLATE_EDIT), "template_bean_contentsStandard");
        assertLabelledFormControl(markup(STYLESHEET_EDIT), "stylesheet_contentsStandard");
    }

    private static void assertLabelledFormControl(String src, String id) {
        assertTrue(src.contains("<label for=\"" + id + "\""), "no label for #" + id);
        Matcher textarea = Pattern.compile("<textarea[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>")
                .matcher(src);
        assertTrue(textarea.find(), "no textarea #" + id);
        assertTrue(textarea.group().contains("form-control"), "#" + id + " must be a form-control");
        assertFalse(textarea.group().contains("style="), "#" + id + " carries no inline style");
    }

    // --- primary actions (admin finding 21) ---

    @Test
    void theTemplateEditorsSaveIsThePrimaryAction() throws IOException {
        assertTrue(markup(TEMPLATE_EDIT).contains(
                        "<button type=\"submit\" class=\"btn btn-primary\"><spring:message code=\"generic.save\"/></button>"),
                "Save is the page's one primary action");
    }

    @Test
    void everyUpdateThemeButtonIsPrimary() throws IOException {
        String src = markup(THEME_EDIT);
        Matcher saves = Pattern.compile("<button type=\"submit\" class=\"([^\"]*)\"\\s+data-busy-label")
                .matcher(src);
        int count = 0;
        while (saves.find()) {
            count++;
            assertEquals("btn btn-primary", saves.group(1), "Update Theme is the primary action");
        }
        assertEquals(3, count, "one Update Theme button per change block");
    }

    // --- type size, layout and inline styles (admin findings 19, 22, 40) ---

    @Test
    void theThemeEditorUsesNoLeadParagraphsAndStacksOnAPhone() throws IOException {
        String src = markup(THEME_EDIT);
        assertFalse(src.contains("class=\"lead\""), "emphasis is weight, never size");
        assertFalse(src.contains("\"col-6\""), "the chooser cards stack below md");
        assertTrue(src.contains("col-12 col-md-6"), "the chooser cards sit side by side from md up");
    }

    @Test
    void noAlertCarriesInlineSpacing() throws IOException {
        for (Path jsp : List.of(THEME_EDIT, TEMPLATE_EDIT)) {
            assertFalse(markup(jsp).contains("margin-top:3em"),
                    jsp.getFileName() + ": spacing belongs to classes, not style=");
        }
    }

    @Test
    void noFormCarriesTheBootstrap2FormVerticalClass() throws IOException {
        for (Path jsp : List.of(STYLESHEET_EDIT, THEME_EDIT)) {
            assertFalse(markup(jsp).contains("form-vertical"),
                    jsp.getFileName() + ": form-vertical is styled nowhere");
        }
    }

    @Test
    void theStylesheetCopyIsParagraphedOnce() throws IOException {
        String src = markup(STYLESHEET_EDIT);
        // This message carries no <p> of its own, so the page supplies one.
        assertTrue(src.contains("<p><spring:message code=\"stylesheetEdit.customThemeNoStylesheet\"/></p>"),
                "the custom-theme copy needs a paragraph");
        // This one already carries two <p>s; wrapping it nests a <p> in a <p>.
        assertFalse(src.contains("<p><spring:message code=\"stylesheetEdit.sharedThemeNoStylesheetSupport\"/></p>"),
                "a <p> around a message that brings its own <p>s is invalid nesting");
    }

    // --- the URL preview (admin finding 24) ---

    @Test
    void theResultingUrlShowsBeforeTheReaderTypes() throws IOException {
        String src = markup(TEMPLATE_EDIT);
        Matcher good = Pattern.compile("<div id=\"good_link\"[^>]*>").matcher(src);
        assertTrue(good.find(), "the resulting-URL block is missing");
        assertFalse(good.group().contains("display: none"),
                "the resulting URL must show on load when the template has a link");
        assertTrue(good.group().contains("${empty bean.link ? 'hidden' : ''}"),
                "hidden only when there is no link to show");
        assertFalse(src.contains("color:var(--bad)"), "the path is not an error; don't paint it red");
        assertFalse(src.contains("[<button"), "the launch control is a link-button, not [bracketed]");
    }

    // --- the list's icons and cells (admin finding 25) ---

    @Test
    void theTemplateListUsesBootstrapIconsAndNoLegacyCellAttributes() throws IOException {
        String src = markup(TEMPLATES);
        assertFalse(src.contains("images/page_white"), "FamFamFam PNGs give way to Bootstrap Icons");
        assertTrue(src.contains("bi bi-file-earmark-text"), "a template row shows a file icon");
        assertFalse(src.contains("width=\"10\""), "ten pixels was never the intent");
        assertFalse(src.contains("class=\"center\""), "no rule styles .center");
        assertFalse(src.contains("vertical-align"), "use the table's align-middle, not inline styles");
    }
}
