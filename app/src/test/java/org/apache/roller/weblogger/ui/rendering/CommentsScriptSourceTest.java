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
package org.apache.roller.weblogger.ui.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comment form script, read as source: the part of the audience-assets
 * macro in {@code WEB-INF/velocity/weblog.vm} between the
 * {@code // --- comments ---} and {@code // --- end comments ---} markers.
 * These are the rules a reviewer would otherwise check by eye on every
 * change -- how server strings enter the DOM, which endpoints the script may
 * call, where the CSRF header comes from, and that the script carries no
 * English and no Velocity metacharacters. What the script DOES in a browser
 * is proven by the browser ITs, not here.
 */
class CommentsScriptSourceTest {

    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");
    private static final String START = "// --- comments ---";
    private static final String END = "// --- end comments ---";

    /** {@code obj.textContent = <expression>}: the only way a server string may reach the page. */
    private static final Pattern TEXT_CONTENT_ASSIGNMENT = Pattern.compile("\\.textContent\\s*=\\s*([^;]+);");

    /** Two words in a string literal: English in the script instead of on the slot. */
    private static final Pattern TWO_WORDS = Pattern.compile("[A-Za-z] +[A-Za-z]");

    private static final Pattern DATA_MSG = Pattern.compile("data-msg-[a-z-]+");

    /** {@code x.href = <url>;}, an assignment, not a comparison. */
    private static final Pattern HREF_ASSIGNMENT = Pattern.compile("\\.href\\s*=(?!=)\\s*([^;]+);");

    /** {@code data-msg-x="$utils.escapeHTML($text.get("key"))"}: a bundle message, escaped for the attribute. */
    private static final Pattern ESCAPED_MESSAGE = Pattern.compile(
            "(data-msg-[a-z-]+)=\"\\$utils\\.escapeHTML\\(\\$text\\.get\\(\"[A-Za-z.]+\"\\)\\)\"");

    @Test
    void theScriptSitsBetweenItsMarkersInTheAudienceAssetsMacro() throws IOException {
        String macro = macro("showAudienceAssets");
        assertEquals(1, count(macro, START), "one start marker");
        assertEquals(1, count(macro, END), "one end marker");
        assertTrue(macro.indexOf(START) < macro.indexOf(END));
        assertTrue(script().contains("comments-slot"), "the script finds the slots the showComments macro renders");
    }

    @Test
    void noServerStringIsParsedAsHtml() throws IOException {
        String script = script();
        for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
                "createContextualFragment", "DOMParser")) {
            assertFalse(script.contains(sink), "the comments script must not use " + sink);
        }
        assertTrue(script.contains(".textContent"), "server strings go in through textContent");
        assertTrue(script.contains("createElement("), "the skeleton is built element by element");
    }

    /** Review Focus 3: a display name holding markup shows as text. */
    @Test
    void displayNameIsEscapedInTheScriptByTextContent() throws IOException {
        List<String> uses = linesMentioning(script(), "displayName");
        assertFalse(uses.isEmpty(), "the script shows the reader's display name");
        for (String line : uses) {
            Matcher m = TEXT_CONTENT_ASSIGNMENT.matcher(line);
            assertTrue(m.find() && m.group(1).contains("displayName"),
                    "every use of the display name is a textContent assignment: " + line.strip());
        }
    }

    @Test
    void eachEndpointIsSuffixChecked() throws IOException {
        String script = script();
        for (String suffix : List.of("/roller-ui/rendering/comments/session.rol",
                "/roller-ui/rendering/comments.rol", "/roller-ui/rendering/comments/")) {
            assertTrue(Pattern.compile("endpointOk\\([^,()]+,\\s*'" + Pattern.quote(suffix) + "'\\)")
                    .matcher(script).find(), "endpointOk(..., '" + suffix + "')");
        }
    }

    @Test
    void everyFetchSendsSameOriginCredentials() throws IOException {
        String script = script();
        int fetches = count(script, "fetch(");
        assertTrue(fetches > 0, "the script fetches");
        assertEquals(fetches, count(script, "credentials: 'same-origin'"),
                "each fetch call carries credentials: 'same-origin'");
    }

    @Test
    void theCsrfHeaderNameComesFromTheSessionAnswer() throws IOException {
        String script = script();
        assertTrue(script.contains("csrf.header"), "the header name is read from the session JSON");
        assertTrue(script.contains("csrf.token"), "and so is the token");
        assertFalse(script.toLowerCase().contains("x-csrf"), "never a literal header name");
        assertFalse(script.toLowerCase().contains("x-xsrf"), "never a literal header name");
    }

    /** Ruling R7: the page carries no usernames; own comments are matched by id. */
    @Test
    void ownCommentsAreFoundByIdNeverByUsername() throws IOException {
        String script = script();
        assertTrue(script.contains("ownPublished"), "the session's own published ids drive Delete");
        assertTrue(script.contains("getElementById('comment-'"), "matched against id=\"comment-<id>\"");
        assertFalse(script.contains("userName"), script);
        assertFalse(script.contains("data-author"), script);
    }

    /**
     * A 201 means the comment exists: whatever happens next, the post is never
     * sent again (a second POST is a duplicate comment). And no 2xx counts as
     * a lost session unless it came through a redirect (a sign-in page).
     */
    @Test
    void aCreatedCommentIsNeverPostedAgain() throws IOException {
        String code = withoutLineComments(script());
        int at = code.indexOf("if (response.status === 201)");
        assertTrue(at >= 0, "the post handles its 201");
        String created = block(code, code.indexOf('{', at));
        assertFalse(created.contains("recover("), "no session recovery after a 201: " + created);
        assertFalse(Pattern.compile("(?<![A-Za-z])post\\(").matcher(created).find(),
                "no second post after a 201: " + created);

        String lost = functionBody(code, "sessionLost");
        assertTrue(lost.contains("redirected"), "a sign-in page is recognised by the redirect: " + lost);
        assertFalse(lost.contains("<"), "no status range counts as lost, so no 2xx does: " + lost);
    }

    /**
     * The script sends the CSRF token to these URLs, so the suffix check is
     * not enough: an absolute URL on another origin ends in the same suffix.
     */
    @Test
    void theCommentEndpointsMustBeSameOriginPaths() throws IOException {
        String code = withoutLineComments(script());
        for (String endpoint : List.of("sessionUrl", "postUrl", "deleteUrl")) {
            assertTrue(code.contains("sameOrigin(" + endpoint + ")"), endpoint + " is checked same-origin");
        }
    }

    @Test
    void aDeleteInFlightCannotBeSentTwice() throws IOException {
        String offer = functionBody(withoutLineComments(script()), "offerDelete");
        assertTrue(offer.contains(".disabled = true"), "the Delete button is disabled while its request runs: " + offer);
    }

    /** The reasons the session answer can give; any other falls to the generic line. */
    @Test
    void everyEntryReasonTheSessionCanGiveIsNamed() throws IOException {
        String reasons = functionBody(withoutLineComments(script()), "reasonText");
        for (String code : List.of("closed", "comments-off", "not-found", "custom-domain")) {
            assertTrue(reasons.contains("'" + code + "'"), code + " has its own line: " + reasons);
        }
    }

    /**
     * Every link the script builds points where the session answer said, and
     * only after that URL was checked to be on this site, in the same function.
     */
    @Test
    void everyHrefIsSetOnlyAfterASameOriginCheck() throws IOException {
        String code = withoutLineComments(script());
        Matcher href = HREF_ASSIGNMENT.matcher(code);
        int seen = 0;
        while (href.find()) {
            seen++;
            String url = href.group(1).strip();
            String before = code.substring(enclosingFunctionStart(code, href.start()), href.start());
            assertTrue(before.contains("sameOrigin(" + url + ")"),
                    "href = " + url + " without sameOrigin(" + url + ") before it in the same function");
        }
        assertTrue(seen >= 2, "the sign-in and account links set an href");
    }

    @Test
    void theTextareaIsCappedAtTheBodyLimit() throws IOException {
        assertTrue(script().contains("maxLength = 4000"), "the textarea's maxlength is the 4000-character limit");
    }

    /**
     * The script is template text: a dollar sign or hash before a word is a
     * Velocity reference or directive (a selector like the comment's own id
     * would be read as a macro call), and Velocity prints what it cannot
     * resolve without complaint.
     */
    @Test
    void theScriptCarriesNoVelocityMetacharacters() throws IOException {
        String script = script();
        assertFalse(script.contains("$"), "no dollar sign in the comments script");
        assertFalse(script.contains("#"), "no hash in the comments script");
    }

    /** Every word a reader sees comes from a data-msg-* attribute the macro renders with $text. */
    @Test
    void theScriptCarriesNoEnglishAndReadsEveryMessageTheSlotCarries() throws IOException {
        String code = withoutLineComments(script());
        assertFalse(code.contains("\"") || code.contains("`"),
                "single-quoted literals only, so this test can read every one of them");
        List<String> english = stringLiterals(code).stream().filter(l -> TWO_WORDS.matcher(l).find()).toList();
        assertEquals(List.of(), english, "English in the script, not on the slot");

        String slots = macro("showComments");
        Set<String> rendered = new TreeSet<>(matches(DATA_MSG, slots));
        assertEquals(rendered, new TreeSet<>(matches(DATA_MSG, code)),
                "the messages the script reads are the ones the slot carries");
        Set<String> viaText = new TreeSet<>();
        Matcher m = ESCAPED_MESSAGE.matcher(slots);
        while (m.find()) {
            viaText.add(m.group(1));
        }
        assertEquals(rendered, viaText, "each message is rendered with $text.get and escaped");
    }

    // ------------------------------------------------------------- helpers

    /** The script between the markers. */
    static String script() throws IOException {
        String macro = macro("showAudienceAssets");
        int start = macro.indexOf(START);
        int end = macro.indexOf(END);
        assertTrue(start >= 0 && end > start, "the comments markers are missing from the audience-assets macro");
        return macro.substring(start + START.length(), end);
    }

    /** One macro's body, from its definition to the first column-zero end directive after it. */
    private static String macro(String name) throws IOException {
        String vm = Files.readString(WEBLOG_VM, StandardCharsets.UTF_8);
        int start = vm.indexOf("#macro(" + name);
        assertTrue(start >= 0, "no macro " + name + " in " + WEBLOG_VM);
        int end = vm.indexOf("\n#end", start);
        assertTrue(end > start, "macro " + name + " has no end");
        return vm.substring(start, end);
    }

    /** The text of the brace block opening at {@code open}, quotes honoured. */
    private static String block(String code, int open) {
        assertTrue(open >= 0 && code.charAt(open) == '{', "no block at " + open);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'') {
                i = endOfLiteral(code, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced block at " + open);
    }

    /** The index of the quote that closes the literal opening at {@code open}. */
    private static int endOfLiteral(String code, int open) {
        int i = open + 1;
        while (i < code.length() && code.charAt(i) != '\'') {
            i += code.charAt(i) == '\\' ? 2 : 1;
        }
        return i;
    }

    /** The body of {@code function name(...)}. */
    private static String functionBody(String code, String name) {
        int at = code.indexOf("function " + name + "(");
        assertTrue(at >= 0, "no function " + name);
        return block(code, code.indexOf('{', at));
    }

    /** Where the innermost function containing {@code index} starts. */
    private static int enclosingFunctionStart(String code, int index) {
        for (int at = code.lastIndexOf("function", index); at >= 0; at = code.lastIndexOf("function", at - 1)) {
            int open = code.indexOf('{', at);
            if (open < index && open + block(code, open).length() > index) {
                return at;
            }
        }
        throw new AssertionError("no function encloses " + index);
    }

    private static String withoutLineComments(String script) {
        return script.lines().filter(l -> !l.strip().startsWith("//")).collect(Collectors.joining("\n"));
    }

    /** The single-quoted string literals of the script, escapes honoured. */
    private static List<String> stringLiterals(String code) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < code.length()) {
            if (code.charAt(i) != '\'') {
                i++;
                continue;
            }
            StringBuilder literal = new StringBuilder();
            i++;
            while (i < code.length() && code.charAt(i) != '\'') {
                if (code.charAt(i) == '\\' && i + 1 < code.length()) {
                    i++;
                }
                literal.append(code.charAt(i));
                i++;
            }
            literals.add(literal.toString());
            i++;
        }
        return literals;
    }

    private static List<String> linesMentioning(String script, String word) {
        return withoutLineComments(script).lines().filter(l -> l.contains(word)).toList();
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
