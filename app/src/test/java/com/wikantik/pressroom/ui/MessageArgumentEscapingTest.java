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
package com.wikantik.pressroom.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value substituted into a flash or validation message reaches the reader
 * <strong>unescaped</strong>, so every user-typed one must be escaped at the
 * call site.
 *
 * <p>{@code tiles/messages.jsp} renders {@code ${msg}} bare and
 * {@code <c:out value="${error}" escapeXml="false"/>}, and it does so
 * deliberately: several messages carry a link or a {@code <strong>} of their
 * own. That decision is fine and is not what this ratchet is about -- it just
 * means the boundary moved to the controller. A message argument that came
 * from a form field is stored HTML in a success banner otherwise: name a
 * template {@code <img src=x onerror=alert(1)>} and the "saved" notice runs
 * it.
 *
 * <p>The scan is deliberately blunt: <em>any</em> accessor call
 * ({@code .get...}) passed as a message argument must be wrapped in
 * {@code StringEscapeUtils.escapeHtml4}, or named in {@link #SAFE_ARGUMENTS}
 * with a reason. A cleverer scan would try to decide which accessors return
 * author input, which is exactly the judgement that has gone wrong here twice
 * -- {@code Weblog.getName()} looks safe because {@code WeblogWrapper} escapes
 * it on the way to a theme, and {@code EntryBean.getTitle()} looks safe
 * because {@code WeblogEntry.getTitle()} is stored escaped, and neither
 * inference holds at these call sites.
 *
 * <p><b>Over-escaping is a real cost, not a free default</b>, which is why the
 * allowlist exists rather than a blanket "escape everything": a value that is
 * already stored escaped renders as {@code &amp;lt;} if escaped twice, and a
 * legitimate {@code &} in a deployer-authored name renders as {@code &amp;}.
 * Every entry below names the mechanism that makes its argument safe, so the
 * claim can be re-checked rather than taken on trust.
 */
class MessageArgumentEscapingTest {

    private static final Path CONTROLLERS =
            Path.of("src/main/java/com/wikantik/pressroom/ui/controllers");

    /**
     * The message helpers, and the zero-based index of their {@code key}
     * argument. Everything between the key and the trailing {@code request} is
     * a message argument; a call with nothing between them substitutes nothing
     * and is not this scan's business.
     */
    private static final Map<String, Integer> KEY_INDEX = Map.of(
            "addError", 1,
            "addMessage", 1,
            "addFlashError", 1,
            "addFlashMessage", 1,
            // (model, fieldId, key, args, request) -- the same sink through
            // addError, one argument further along.
            "addFieldError", 2);

    /**
     * Arguments that are safe without escaping, each with the mechanism that
     * makes them so. Keyed by {@code <file> -> <expression>}, so an accessor
     * that is safe in one controller does not become invisible in another.
     */
    private static final Set<String> SAFE_ARGUMENTS = Set.of(
            // EntryFieldRules.escapeTitle runs escapeHtml4 on the title once,
            // at save time -- WeblogEntry.getTitle() therefore already returns
            // entity-escaped markup, and escaping it again renders &amp;lt;.
            // See CLAUDE.md, "Entry titles are stored HTML-escaped".
            "editor/EntryRemoveController.java -> entry.getTitle()",
            // getDisplayTitle() is that same stored-escaped title through
            // Utilities.removeHTML(); its blank-title fallback excerpts the
            // entry text with the same tag strip.
            "editor/TrashController.java -> entry.getDisplayTitle()",
            // CategoryEditController.myValidate refuses any name that differs
            // from its own escapeHtml4 of itself, so a stored category name
            // cannot contain <, >, &, " or ' at all.
            "editor/CategoryEditController.java -> category.getName()",
            "editor/CategoryEditController.java -> new Object[]{bean.getName()}",
            "editor/CategoryRemoveController.java -> category.getName()",
            // A theme's display name is read from its own theme.xml on disk:
            // deployer-authored file content, never request input.
            "editor/ThemeEditController.java -> t.getName()",
            "editor/ThemeEditController.java -> newTheme.getName()",
            // The duplicate's title is the ORIGINAL's, run through the same
            // stored-escaping as EntryRemoveController's above.
            "editor/EntriesController.java -> copy.getTitle()",
            // A formatted date, not text anyone typed.
            "editor/EntryEditController.java -> DateUtil.fullDate(entry.getPubTime())",
            // A URL the server built from a weblog and a media-file id --
            // URLStrategy output, with no author text in it. Escaping it would
            // turn the & joining its query parameters into &amp;.
            "editor/MediaFileAddController.java -> weblogger.getUrlStrategy()"
                    + ".getMediaFileURL(upload.getWeblog(), upload.getId(), true)",
            // CreateWeblogController.myValidate refuses any handle that is not
            // identical to itself filtered through username.allowedChars
            // (default A-Za-z0-9) before this line can be reached.
            "core/CreateWeblogController.java -> bean.getHandle()");

    /**
     * Every message argument that is an accessor call is either escaped or
     * allowlisted.
     */
    @Test
    void everyUserTypedMessageArgumentIsEscaped() throws IOException {
        List<String> violations = new ArrayList<>();
        int argumentsChecked = 0;

        for (Path controller : controllers()) {
            String rel = controller.getParent().getFileName() + "/" + controller.getFileName();
            if ("controllers/BaseController.java".equals(rel)) {
                // The helpers themselves; their own javadoc names every one of
                // the call shapes this scan looks for.
                continue;
            }
            String src = withoutComments(Files.readString(controller, StandardCharsets.UTF_8));
            for (Call call : callsIn(src)) {
                for (String argument : call.messageArguments()) {
                    if (!argument.contains(".get")) {
                        continue;
                    }
                    argumentsChecked++;
                    String site = rel + " -> " + argument;
                    if (argument.contains("escapeHtml4(") || SAFE_ARGUMENTS.contains(site)) {
                        continue;
                    }
                    violations.add(site);
                }
            }
        }

        assertTrue(argumentsChecked >= 20,
                "Only " + argumentsChecked + " accessor-valued message arguments found -- "
                        + "the scan is not looking where it thinks it is.");
        assertTrue(violations.isEmpty(),
                "messages.jsp renders errors and messages UNESCAPED, so a user-typed "
                        + "message argument is stored HTML in a banner. Wrap it in "
                        + "StringEscapeUtils.escapeHtml4, or add it to SAFE_ARGUMENTS with "
                        + "the mechanism that makes it safe:\n  "
                        + String.join("\n  ", new TreeSet<>(violations)));
    }

    /**
     * Guards the allowlist itself: an entry naming a call site that no longer
     * exists is excusing nothing, and would go on looking like a considered
     * decision indefinitely.
     */
    @Test
    void everyAllowlistedArgumentStillExists() throws IOException {
        Set<String> live = new TreeSet<>();
        for (Path controller : controllers()) {
            String rel = controller.getParent().getFileName() + "/" + controller.getFileName();
            String src = withoutComments(Files.readString(controller, StandardCharsets.UTF_8));
            for (Call call : callsIn(src)) {
                for (String argument : call.messageArguments()) {
                    if (argument.contains(".get")) {
                        live.add(rel + " -> " + argument);
                    }
                }
            }
        }
        assertEquals(Set.of(), new TreeSet<>(SAFE_ARGUMENTS.stream()
                        .filter(entry -> !live.contains(entry))
                        .toList()),
                "SAFE_ARGUMENTS names call sites that no longer exist -- drop them");
    }

    private static List<Path> controllers() throws IOException {
        try (Stream<Path> walk = Files.walk(CONTROLLERS)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** One {@code addX(...)} call: the helper's name and its argument list. */
    private record Call(String method, List<String> arguments) {

        /**
         * Everything between the key and the trailing {@code request}. Empty
         * when the call substitutes nothing.
         */
        List<String> messageArguments() {
            int from = KEY_INDEX.get(method) + 1;
            int to = arguments.size() - 1;
            return from >= to ? List.of() : arguments.subList(from, to);
        }
    }

    private static final Pattern CALL_START = Pattern.compile(
            "\\b(addError|addMessage|addFlashError|addFlashMessage|addFieldError)\\s*\\(");

    private static List<Call> callsIn(String src) {
        List<Call> calls = new ArrayList<>();
        Matcher m = CALL_START.matcher(src);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matchingParen(src, open);
            if (close < 0) {
                continue;
            }
            calls.add(new Call(m.group(1), splitTopLevel(src.substring(open + 1, close))));
        }
        return calls;
    }

    /** The index of the {@code )} closing the {@code (} at {@code open}. */
    private static int matchingParen(String src, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '(' -> depth++;
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                default -> { }
            }
        }
        return -1;
    }

    /**
     * Splits an argument list on commas that are not nested inside parentheses,
     * braces (a {@code new Object[]{...}} literal) or a string.
     */
    private static List<String> splitTopLevel(String arguments) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (inString || inChar) {
                current.append(c);
                if (c == '\\' && i + 1 < arguments.length()) {
                    current.append(arguments.charAt(++i));
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '(', '{', '[' -> depth++;
                case ')', '}', ']' -> depth--;
                default -> { }
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    /**
     * Java source with comments removed. Javadoc naming a helper by its
     * signature ({@code addError(Model, String, HttpServletRequest)}) would
     * otherwise be parsed as a call site; a line comment is stripped only
     * outside a string literal, so a URL keeps its {@code //}.
     */
    private static String withoutComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inBlock = false;
        boolean inLine = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            }
            out.append(c);
        }
        return out.toString();
    }
}
