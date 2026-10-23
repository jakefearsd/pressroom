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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media manager's JSPs print nothing raw that is not known to be safe.
 *
 * <p>{@link EditorJspEscapingTest} names the author-controlled expressions it
 * knows about, so a new one is unguarded until somebody adds it. That is how
 * a media folder name -- stored with no check but "no {@code /}" -- reached
 * {@code MediaFileView.jsp} as {@code ${currentDirectory.name}} and
 * {@code ${opt.name}}: stored XSS against every editor of the weblog. These
 * pages are small enough to invert the rule: every EL expression in an output
 * position must be wrapped in {@code fn:escapeXml} or be on {@link #SAFE},
 * with the mechanism that makes it safe.
 *
 * <p>An output position is anywhere but the attributes of the JSTL tags that
 * print nothing ({@code c:if}, {@code c:when}, {@code c:forEach},
 * {@code c:set}; {@code c:param}, whose values are URL-encoded; and a
 * {@code c:url} that stores into a {@code var} -- one without prints its
 * value, which JSTL does not encode). {@code <spring:message arguments>}
 * counts as output: the tag does not escape its arguments.
 */
class MediaJspEscapingTest {

    private static final Path EDITOR_JSPS = Path.of("src/main/webapp/WEB-INF/jsps/editor");

    /** JSTL tags whose attribute values are never printed as markup. */
    private static final Set<String> NON_PRINTING_TAGS =
            Set.of("c:if", "c:when", "c:forEach", "c:set", "c:url", "c:param");

    /** Bare expressions that are safe, each with what makes it so. */
    private static final Set<String> SAFE = Set.of(
            // The servlet container's own context path.
            "pageContext.request.contextPath",
            // CreateWeblogController refuses a handle outside
            // username.allowedChars (default A-Za-z0-9).
            "actionWeblog.handle",
            // Server-generated UUIDs: entity ids, or a directory id the
            // controller put in the model after an ownership lookup found it.
            "mediaFile.id", "opt.id", "newImage.id", "newFile.id", "directoryId",
            // Numbers.
            "mediaFile.thumbnailWidth", "mediaFile.thumbnailHeight",
            "newImage.width", "newImage.height", "newImage.length", "newFile.length",
            "bean.width", "bean.height", "bean.length", "bean.size", "bean.focalX", "bean.focalY",
            // A boolean.
            "mediaFile.isImageFile()",
            // KeyValueObject lists the controller builds: constant keys and
            // bundle-text values.
            "opt.key", "opt.value",
            // ResourceBundle lookups on the search bean.
            "bean.typeLabel", "bean.sizeFilterTypeLabel", "bean.sizeUnitLabel");

    /**
     * Calls whose result is safe whatever they are given: fn:escapeXml
     * escapes; fn:length is a number; urls.* is URLStrategy output built from
     * ids (CLAUDE.md, "Escaping"). Only the opening is matched here; {@link
     * ElExpressions#isOneCall} also requires that its parenthesis closes at the very
     * end, so the expression is that one call and not, say, a ternary that
     * happens to start and end with one.
     */
    private static final Pattern SAFE_CALL_START = Pattern.compile(
            "(?:fn:escapeXml|fn:length|urls\\.[A-Za-z]+)\\(");

    /** A ternary that prints one of two string literals. */
    private static final Pattern LITERAL_TERNARY =
            Pattern.compile(".*\\?\\s*'[^']*'\\s*:\\s*'[^']*'");

    /**
     * A {@code <c:url var=..>}: its result is safe, its parameters having gone
     * through c:param. Trusted by where the variable came from, never by its
     * name -- a {@code <c:set var="xUrl">} is any value at all.
     */
    private static final Pattern URL_VAR_DECLARATION =
            Pattern.compile("<c:url\\b[^>]*?\\bvar\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']");

    private static final Pattern SET_VAR_DECLARATION =
            Pattern.compile("<c:set\\b[^>]*?\\bvar\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']");

    private static final Pattern EL = Pattern.compile("\\$\\{([^}]*)}");

    @Test
    void everyPrintedExpressionInTheMediaJspsIsEscapedOrKnownSafe() throws IOException {
        List<Path> jsps = mediaJsps();
        assertTrue(jsps.size() >= 7, "media JSPs moved? found only " + jsps);

        List<String> violations = new ArrayList<>();
        for (Path jsp : jsps) {
            violations.addAll(rawExpressionsIn(jsp.getFileName().toString(),
                    Files.readString(jsp, StandardCharsets.UTF_8)));
        }

        assertEquals(List.of(), violations,
                "Raw EL in a media JSP -- wrap it in ${fn:escapeXml(...)}, or add it to SAFE "
                        + "with what makes it safe");
    }

    /** The scan itself: it must see a raw sink, and must not see a non-printing one. */
    @Test
    void theScanTellsAPrintedExpressionFromAConditionAndAWrappedOne() {
        String jsp = """
                <c:if test="${not empty dir.name}">
                <c:url var="backUrl" value="/x.rol"><c:param name="d" value="${dir.name}"/></c:url>
                <p>${dir.name}</p>
                <spring:message code="k" arguments="${dir.name}"/>
                <a href="${backUrl}">${fn:escapeXml(dir.name)}</a>
                <option ${opt.id == dir.id ? 'selected' : ''}>x</option>
                </c:if>
                """;

        assertEquals(List.of("T.jsp:3  ${dir.name}", "T.jsp:4  ${dir.name}"),
                rawExpressionsIn("T.jsp", jsp));
    }

    /**
     * Three ways the scan used to be talked out of a raw sink: a
     * {@code <c:url>} with no {@code var} prints its value (JSTL encodes only
     * {@code c:param}); a variable named like a url is trusted only when a
     * {@code <c:url var>} made it; and a "safe call" is safe only when the
     * whole expression is that one call, not a ternary that starts and ends
     * with one.
     */
    @Test
    void theScanCannotBeTalkedOutOfARawSink() {
        String jsp = """
                <c:url value="${dir.name}"/>
                <c:set var="xUrl" value="${dir.name}"/>
                <a href="${xUrl}">x</a>
                <p>${fn:length(a) > 0 ? dir.name : fn:length(b)}</p>
                <c:url var="goodUrl" value="/x.rol"><c:param name="d" value="${dir.name}"/></c:url>
                <a href="${goodUrl}">${fn:length(dir.name)} ${fn:escapeXml(fn:trim(dir.name))}</a>
                """;

        assertEquals(List.of("T.jsp:1  ${dir.name}", "T.jsp:3  ${xUrl}",
                        "T.jsp:4  ${fn:length(a) > 0 ? dir.name : fn:length(b)}"),
                rawExpressionsIn("T.jsp", jsp));
    }

    private static List<Path> mediaJsps() throws IOException {
        try (Stream<Path> files = Files.list(EDITOR_JSPS)) {
            return files.filter(p -> p.getFileName().toString().startsWith("MediaFile"))
                    .filter(p -> p.getFileName().toString().endsWith(".jsp"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> rawExpressionsIn(String name, String source) {
        // Blank every expression's body first, so a '<' or '>' inside one
        // (fn:length(x) > 0) cannot be taken for a tag boundary.
        StringBuilder masked = new StringBuilder(source);
        Matcher all = EL.matcher(source);
        while (all.find()) {
            for (int i = all.start(1); i < all.end(1); i++) {
                masked.setCharAt(i, ' ');
            }
        }

        Set<String> urlVariables = new java.util.HashSet<>();
        Matcher declared = URL_VAR_DECLARATION.matcher(source);
        while (declared.find()) {
            urlVariables.add(declared.group(1));
        }
        Matcher reassigned = SET_VAR_DECLARATION.matcher(source);
        while (reassigned.find()) {
            urlVariables.remove(reassigned.group(1));
        }

        List<String> raw = new ArrayList<>();
        Matcher el = EL.matcher(source);
        while (el.find()) {
            String expression = el.group(1).strip();
            if (insideNonPrintingTag(masked, el.start()) || safe(expression, urlVariables)) {
                continue;
            }
            int line = (int) source.chars().limit(el.start()).filter(c -> c == '\n').count() + 1;
            raw.add(name + ":" + line + "  " + el.group());
        }
        return raw;
    }

    private static boolean insideNonPrintingTag(CharSequence masked, int at) {
        String before = masked.subSequence(0, at).toString();
        int open = before.lastIndexOf('<');
        if (open < 0 || before.lastIndexOf('>') > open) {
            return false;
        }
        Matcher tag = Pattern.compile("<([A-Za-z]+:[A-Za-z]+)").matcher(before.substring(open));
        if (!tag.lookingAt() || !NON_PRINTING_TAGS.contains(tag.group(1))) {
            return false;
        }
        if (!"c:url".equals(tag.group(1))) {
            return true;
        }
        // <c:url value="${x}"/> with no var PRINTS the url, and JSTL
        // encodes only c:param values, never the value attribute.
        String text = masked.toString();
        int close = text.indexOf('>', at);
        String whole = text.substring(open, close < 0 ? text.length() : close);
        return Pattern.compile("\\bvar\\s*=").matcher(whole).find();
    }

    private static boolean safe(String expression, Set<String> urlVariables) {
        List<String> elements = ElExpressions.listElements(expression);
        if (elements != null) {
            return !elements.isEmpty() && elements.stream().allMatch(e -> safe(e, urlVariables));
        }
        return SAFE.contains(expression)
                || ElExpressions.isOneCall(expression, SAFE_CALL_START)
                || LITERAL_TERNARY.matcher(expression).matches()
                || urlVariables.contains(expression);
    }
}
