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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 2026-08-09 hardening wave's escaping sweep so it cannot regress
 * one file at a time, the way it was originally introduced one file at a
 * time. The listed EL expressions are author-controlled free text (no
 * save-time escaping or charset restriction on any of them -- verified per
 * field during the sweep), and each one appeared raw in at least one editor
 * JSP as element content, an attribute value, or a JS string, all of which
 * were exploitable stored XSS. Every occurrence in every JSP must go
 * through fn:escapeXml (or an equivalently escaping wrapper).
 *
 * <p>If this test fails on a NEW usage you are adding: wrap it. If it fails
 * because a field gained save-time escaping and you want it delisted, move
 * the escaping evidence into this comment and remove the entry -- do not
 * loosen the pattern.
 */
class EditorJspEscapingTest {

    /**
     * Author-controlled free-text EL expressions that must never render raw.
     * Kept to exact expressions (not a heuristic) so the failure list is
     * always actionable and never noisy.
     */
    private static final List<String> AUTHOR_CONTROLLED = List.of(
            "mediaFile.name",
            "bean.altText",
            "category.name",
            "category.description",
            "category.image",
            "directory.name",
            "newFile.name",
            "newImage.name",
            "post.title",
            "entry.title",
            "bean.customDomain",
            // The form-bean twins of the display expressions above. Adding
            // these closed a real stored-XSS hole: the list used to name only
            // the read-side expressions, so the *edit form* -- which renders
            // the very same author text back into value="..." attributes,
            // <textarea> bodies and inline-JS string literals -- was
            // unguarded. See this class's javadoc for the two traps that made
            // the gap easy to miss.
            "bean.name",
            "bean.description",
            "bean.title",
            "bean.tagline",
            "bean.summary",
            "bean.text",
            "bean.about",
            "bean.metaTitle",
            "bean.searchDescription",
            "bean.canonicalUrl",
            "bean.slug",
            "bean.link",
            "bean.tagsAsString",
            "bean.eventLocation",
            "bean.emailAddress",
            "bean.image",
            "bean.icon",
            "bean.originalPath",
            "bean.content",
            "bean.contentsStandard",
            "bean.userName",
            // The template screens' own author text: a template's description,
            // a stylesheet's source, a typed content type, the refused new
            // template name echoed back, and the current theme's name.
            "p.description",
            "contentsStandard",
            "manualContentType",
            "newTmplName",
            "currentThemeName",
            // The SEO drawer's picked-image ids: String bean fields the
            // author's own POST fills, reflected into value="...".
            "bean.featuredImageId",
            "bean.ogImageId",
            // The moderation page's reader-controlled fields (reader comments).
            "author.screenName",
            "author.userName",
            "c.body",
            "reply.body",
            // The reader's account page: a display name a reader typed (any
            // account's screenName, however reached) and the form's echo of it.
            "screenName",
            "nameValue");

    @Test
    void authorControlledTextNeverRendersRawInAJsp() throws IOException {
        Path jsps = Path.of("src/main/webapp/WEB-INF/jsps");
        assertTrue(Files.isDirectory(jsps), "JSP root moved? " + jsps.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(jsps)) {
            files.filter(p -> p.toString().endsWith(".jsp"))
                    .forEach(p -> scan(p, violations));
        }

        assertTrue(violations.isEmpty(),
                "Raw author-controlled EL found -- wrap in ${fn:escapeXml(...)}:\n  "
                        + String.join("\n  ", violations));
    }

    /**
     * Review Focus 3, the account page's half: the display name a reader typed
     * is shown there (the form's value and the heading), and it goes through
     * fn:escapeXml in both places, so {@code "><img onerror>} is text.
     */
    @Test
    void displayNameIsEscapedOnTheAccountPage() throws IOException {
        Path account = Path.of("src/main/webapp/WEB-INF/jsps/core/Account.jsp");
        assertTrue(Files.isRegularFile(account), "the account page is missing: " + account.toAbsolutePath());
        String source = Files.readString(account);

        assertTrue(source.contains("${fn:escapeXml(nameValue)}"), "the name field's value is escaped");
        assertTrue(source.contains("${fn:escapeXml(account.screenName)}"), "the shown name is escaped");
        List<String> violations = new ArrayList<>();
        scan(account, violations);
        assertTrue(violations.isEmpty(), "raw on the account page: " + violations);
    }

    /** A raw field inside an EL list is as raw as a bare one; a wrapped one is not. */
    @Test
    void theScanSeesIntoAnElList(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path jsp = dir.resolve("T.jsp");
        Files.writeString(jsp, """
                <spring:message code="a" arguments="${[bean.title]}"/>
                <spring:message code="b" arguments="${[actionWeblog.handle, entry.title]}"/>
                <spring:message code="c" arguments="${[fn:escapeXml(bean.title), x]}"/>
                """);
        List<String> violations = new ArrayList<>();
        scan(jsp, violations);
        assertEquals(2, violations.size(), String.valueOf(violations));
    }

    private static void scan(Path jsp, List<String> violations) {
        final String source;
        try {
            source = Files.readString(jsp);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (String expr : AUTHOR_CONTROLLED) {
            // The property being pinned is "this field must never render
            // raw", not "this exact expression must never appear" -- a
            // caller can reach the same author-controlled field through any
            // access chain (${category.name}, ${entry.category.name},
            // ${post.category.name}, ...), and every one of them is exactly
            // as exploitable as the bare form. So a raw usage is any dotted
            // access path ending in expr, optionally preceded by further
            // "identifier." segments. ${fn:escapeXml(expr)} (or any wrapper
            // taking expr as an argument) still does not match, because the
            // character immediately after "${" is then "f" of "fn:..." --
            // not an identifier-dot chain leading straight into expr -- so
            // the whole prefix-plus-expr sequence never lines up against the
            // closing "}".
            Matcher m = Pattern.compile(
                            "\\$\\{\\s*(?:[A-Za-z][A-Za-z0-9_]*\\.)*"
                                    + Pattern.quote(expr) + "\\s*\\}")
                    .matcher(source);
            while (m.find()) {
                int line = (int) source.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                violations.add(jsp.getFileName() + ":" + line + "  " + m.group().strip());
            }
            // The same raw access as an element of an EL list, ${[a, b]}:
            // the form spring:message arguments take so commas survive.
            Matcher inList = Pattern.compile(
                            "\\$\\{\\s*\\[(?:[^}]*?,)?\\s*(?:[A-Za-z][A-Za-z0-9_]*\\.)*"
                                    + Pattern.quote(expr) + "\\s*[,\\]]")
                    .matcher(source);
            while (inList.find()) {
                int line = (int) source.chars().limit(inList.start()).filter(c -> c == '\n').count() + 1;
                violations.add(jsp.getFileName() + ":" + line + "  " + inList.group().strip());
            }
        }
    }
}
