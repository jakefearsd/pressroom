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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds every place this codebase names a message-bundle key with a literal,
 * and -- where it can -- how many {@code MessageFormat} arguments that call
 * site passes.
 *
 * <p>Shared by {@link MessageKeyTest} (does the key exist at all?) and
 * {@link MessagePlaceholderContractTest} (does the value's highest
 * {@code &#123;n&#125;} match what the caller passes?), so the two ratchets
 * cannot drift apart on what counts as a call site.
 *
 * <p><b>Only literal keys are seen, by construction.</b> A key assembled at
 * runtime ({@code "weblogEntryQuery.label." + status}, a {@code successKey}
 * parameter, {@code msg.getKey()} replayed off a {@code PressroomMessages}
 * collector) cannot be resolved statically and is skipped rather than guessed
 * at. Same for keys addressed from XML -- {@code runtimeConfigDefs.xml}'s
 * {@code key="..."} and the menu files' {@code name="..."}; those are the
 * orphan ratchet's business, not this scanner's.
 *
 * <p>Argument counting is best-effort and says so: a call site whose argument
 * list is an array-typed expression of unknowable length reports
 * {@link Usage#argCount()} == {@link #UNRESOLVED}, and the placeholder ratchet
 * pins that skip-set rather than letting it grow silently.
 */
final class MessageUsageScanner {

    /** Reported as the argument count when the call site cannot be counted statically. */
    static final int UNRESOLVED = -1;

    static final Path JAVA_ROOT = Paths.get("src/main/java");

    static final Path WEBAPP_ROOT = Paths.get("src/main/webapp");

    /**
     * One place a message key is named.
     *
     * @param key      the literal bundle key
     * @param where    {@code relative/path.java:line}, for failure messages only --
     *                 never for assertion identity, which would churn on every edit
     * @param argCount number of MessageFormat arguments the site passes, or
     *                 {@link #UNRESOLVED}
     */
    record Usage(String key, String where, int argCount) {
    }

    /**
     * The five message helpers on {@code BaseController} plus the two-and-three
     * argument {@code PressroomMessages} forms, which share their names.
     */
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "\\b(getText|addError|addMessage|addFlashMessage|addFlashError)\\s*\\(");

    /**
     * {@code public String getPageTitle() { return "some.key"; }} -- rendered by
     * the layout JSPs as {@code <spring:message code="${pageTitle}"/>}, i.e.
     * always with zero arguments.
     */
    private static final Pattern PAGE_TITLE_RETURN = Pattern.compile(
            "public\\s+String\\s+getPageTitle\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+\"([^\"]*)\"\\s*;");

    /** The other half of the same mechanism: a controller overriding the title per handler. */
    private static final Pattern PAGE_TITLE_ATTRIBUTE = Pattern.compile(
            "addAttribute\\(\\s*\"pageTitle\"\\s*,\\s*\"([^\"]+)\"\\s*\\)");

    private static final Pattern ARRAY_LITERAL = Pattern.compile(
            "^new\\s+\\w+\\s*\\[\\s*\\]\\s*\\{(.*)\\}$", Pattern.DOTALL);

    private static final Pattern ARRAY_CAST = Pattern.compile(
            "^\\(\\s*\\w+\\s*\\[\\s*\\]\\s*\\)");

    /** {@code String[] args = ...} and {@code String... args} both make {@code args} an array. */
    private static final Pattern ARRAY_DECLARATION = Pattern.compile(
            "\\b\\w+\\s*(?:\\[\\s*\\]|\\.\\.\\.)\\s*([A-Za-z_$][A-Za-z0-9_$]*)");

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final Pattern MESSAGE_TAG = Pattern.compile(
            "<(?:spring|fmt):message\\b([^>]*?)/?>", Pattern.DOTALL);

    private static final Pattern CODE_ATTRIBUTE = Pattern.compile(
            "\\bcode\\s*=\\s*[\"']([A-Za-z0-9_.]+)[\"']");

    private static final Pattern ARGUMENTS_ATTRIBUTE = Pattern.compile(
            "\\barguments\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /**
     * {@code <spring:message>} splits {@code arguments} on this by default; a tag
     * that overrides it is reported {@link #UNRESOLVED} rather than mis-counted.
     */
    private static final Pattern ARGUMENT_SEPARATOR_ATTRIBUTE = Pattern.compile(
            "\\bargumentSeparator\\s*=");

    private MessageUsageScanner() {
    }

    static List<Usage> scanAll() throws IOException {
        List<Usage> all = new ArrayList<>(scanJavaSources());
        all.addAll(scanTemplates());
        return all;
    }

    static List<Usage> scanJavaSources() throws IOException {
        List<Usage> usages = new ArrayList<>();
        for (Path file : filesUnder(JAVA_ROOT, ".java")) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String rel = JAVA_ROOT.relativize(file).toString();
            List<String> arrayNames = arrayTypedNames(source);

            Matcher call = MESSAGE_CALL.matcher(source);
            while (call.find()) {
                int openParen = call.end() - 1;
                String inner = argumentList(source, openParen);
                if (inner == null) {
                    continue;
                }
                Usage usage = usageFor(call.group(1), splitTopLevel(inner), arrayNames,
                        rel + ":" + lineOf(source, call.start()));
                if (usage != null) {
                    usages.add(usage);
                }
            }
            addLiteralMatches(usages, PAGE_TITLE_RETURN, source, rel);
            addLiteralMatches(usages, PAGE_TITLE_ATTRIBUTE, source, rel);
        }
        return usages;
    }

    static List<Usage> scanTemplates() throws IOException {
        List<Usage> usages = new ArrayList<>();
        for (Path file : filesUnder(WEBAPP_ROOT, ".jsp", ".tag")) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String rel = WEBAPP_ROOT.relativize(file).toString();

            Matcher tag = MESSAGE_TAG.matcher(source);
            while (tag.find()) {
                String attributes = tag.group(1);
                Matcher code = CODE_ATTRIBUTE.matcher(attributes);
                if (!code.find()) {
                    // code="${dynamicKey}" -- not resolvable statically.
                    continue;
                }
                int argCount;
                Matcher arguments = ARGUMENTS_ATTRIBUTE.matcher(attributes);
                if (ARGUMENT_SEPARATOR_ATTRIBUTE.matcher(attributes).find()) {
                    argCount = UNRESOLVED;
                } else if (!arguments.find()) {
                    argCount = nestedArgumentCount(source, tag);
                } else {
                    String value = arguments.group(1) != null ? arguments.group(1) : arguments.group(2);
                    argCount = countTagArguments(value);
                }
                usages.add(new Usage(code.group(1), rel + ":" + lineOf(source, tag.start()), argCount));
            }
        }
        return usages;
    }

    /**
     * Arguments passed as nested {@code <spring:argument>} tags (the form that
     * survives a comma in a value, which an {@code arguments} string does not);
     * zero for a self-closing tag.
     */
    private static int nestedArgumentCount(String source, Matcher tag) {
        if (tag.group().endsWith("/>")) {
            return 0;
        }
        int close = source.indexOf("</spring:message>", tag.end());
        if (close < 0) {
            return 0;
        }
        Matcher nested = SPRING_ARGUMENT.matcher(source.substring(tag.end(), close));
        int count = 0;
        while (nested.find()) {
            count++;
        }
        return count;
    }

    private static void addLiteralMatches(List<Usage> usages, Pattern pattern, String source, String rel) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!key.isEmpty()) {
                usages.add(new Usage(key, rel + ":" + lineOf(source, matcher.start()), 0));
            }
        }
    }

    /**
     * Splits an already-extracted argument list into the key and the
     * MessageFormat arguments that follow it.
     *
     * <p>Two shapes share these method names. {@code BaseController} puts the
     * key second ({@code addError(model, "key", param, request)}) and always
     * ends with the request / RedirectAttributes; {@code PressroomMessages} puts
     * it first ({@code errors.addError("key", arg)}) with no trailing request.
     * {@code getText} is a third: key first, request last.
     */
    private static Usage usageFor(String method, List<String> args, List<String> arrayNames, String where) {
        String key;
        List<String> messageArgs;
        if ("getText".equals(method)) {
            if (args.size() < 2 || !isStringLiteral(args.get(0))) {
                return null;
            }
            key = literalValue(args.get(0));
            messageArgs = args.subList(1, args.size() - 1);
        } else if (!args.isEmpty() && isStringLiteral(args.get(0))) {
            key = literalValue(args.get(0));
            messageArgs = args.subList(1, args.size());
        } else if (args.size() > 1 && isStringLiteral(args.get(1))) {
            key = literalValue(args.get(1));
            messageArgs = args.size() > 2 ? args.subList(2, args.size() - 1) : List.of();
        } else {
            return null;
        }
        return new Usage(key, where, countMessageArguments(messageArgs, arrayNames));
    }

    private static int countMessageArguments(List<String> messageArgs, List<String> arrayNames) {
        if (messageArgs.isEmpty()) {
            return 0;
        }
        if (messageArgs.size() > 1) {
            return UNRESOLVED;
        }
        String only = messageArgs.get(0);
        Matcher array = ARRAY_LITERAL.matcher(only);
        if (array.matches()) {
            String body = array.group(1).trim();
            return body.isEmpty() ? 0 : splitTopLevel(body).size();
        }
        if (ARRAY_CAST.matcher(only).find()) {
            return UNRESOLVED;
        }
        if (SIMPLE_IDENTIFIER.matcher(only).matches() && arrayNames.contains(only)) {
            return UNRESOLVED;
        }
        // Anything else is one ordinary value: a getter call, a String.valueOf(...),
        // a literal. The single-param overload can only ever carry one.
        return 1;
    }

    private static List<String> arrayTypedNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher matcher = ARRAY_DECLARATION.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static final Pattern SPRING_ARGUMENT = Pattern.compile("<spring:argument\\b");

    private static final Pattern EL_LIST_ARGUMENTS = Pattern.compile("\\$\\{\\s*\\[(.*)]\\s*}", Pattern.DOTALL);

    /**
     * Counts the top-level, comma-separated arguments of a {@code
     * <spring:message arguments="...">} attribute, ignoring commas nested inside
     * an EL expression (a {@code ${fn:substring(x,1,2)}} is one argument).
     */
    private static int countTagArguments(String value) {
        if (value.isBlank()) {
            return 0;
        }
        // ${[a, b]}: an EL list, which the tag takes as the argument array
        // without splitting (how a value that may hold a comma is passed).
        Matcher list = EL_LIST_ARGUMENTS.matcher(value);
        if (list.matches()) {
            return list.group(1).isBlank() ? 0 : splitTopLevel(list.group(1)).size();
        }
        int depth = 0;
        int count = 1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '$' || c == '#') && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /** The text between the parentheses of a call whose {@code (} is at {@code openParen}. */
    private static String argumentList(String source, int openParen) {
        int depth = 0;
        for (int i = openParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                i = endOfStringLiteral(source, i);
            } else if (isCharLiteralStart(source, i)) {
                i = endOfCharLiteral(source, i);
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(openParen + 1, i);
                }
            }
        }
        return null;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = endOfStringLiteral(text, i);
            } else if (isCharLiteralStart(text, i)) {
                i = endOfCharLiteral(text, i);
            } else if (c == '(' || c == '[' || c == '{') {
                // Deliberately not '<'/'>': generics are balanced anyway, while a
                // lambda arrow or a comparison would drive the depth negative.
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static int endOfStringLiteral(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return s.length() - 1;
    }

    /**
     * True only for a real {@code 'x'} / {@code '\n'} char literal. An apostrophe
     * in a comment inside an argument list would otherwise swallow the rest of
     * the call.
     */
    private static boolean isCharLiteralStart(String s, int i) {
        if (s.charAt(i) != '\'') {
            return false;
        }
        if (i + 2 < s.length() && s.charAt(i + 1) != '\\' && s.charAt(i + 2) == '\'') {
            return true;
        }
        return i + 3 < s.length() && s.charAt(i + 1) == '\\' && s.charAt(i + 3) == '\'';
    }

    private static int endOfCharLiteral(String s, int start) {
        return s.charAt(start + 1) == '\\' ? start + 3 : start + 2;
    }

    private static boolean isStringLiteral(String arg) {
        return arg.length() >= 2 && arg.charAt(0) == '"' && arg.charAt(arg.length() - 1) == '"'
                && arg.indexOf('"', 1) == arg.length() - 1;
    }

    private static String literalValue(String arg) {
        return arg.substring(1, arg.length() - 1);
    }

    private static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Path> filesUnder(Path root, String... suffixes) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        for (String suffix : suffixes) {
                            if (name.endsWith(suffix)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .sorted()
                    .toList();
        }
    }
}
