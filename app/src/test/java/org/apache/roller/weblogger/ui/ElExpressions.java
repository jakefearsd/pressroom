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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape of one EL expression's body, for the JSP escaping scans
 * ({@code JspConsistencyTest}, {@code MediaJspEscapingTest}): whether it is a
 * list and of what, and whether it is exactly one call.
 */
final class ElExpressions {

    /** An EL list, {@code [a, b]}: the spring:message form that keeps commas. */
    private static final Pattern EL_LIST = Pattern.compile("\\[(.*)]", Pattern.DOTALL);

    private ElExpressions() {
    }

    /** A list's elements, split at its top-level commas; null when the expression is not a list. */
    static List<String> listElements(String expression) {
        Matcher list = EL_LIST.matcher(expression);
        return list.matches() ? splitTopLevel(list.group(1)) : null;
    }

    /**
     * True when the expression is ONE call that {@code start} opens, whose
     * parenthesis closes at the very end -- not a ternary or a concatenation
     * that happens to start and end with one.
     */
    static boolean isOneCall(String expression, Pattern start) {
        Matcher opening = start.matcher(expression);
        if (!opening.lookingAt()) {
            return false;
        }
        int depth = 1;
        for (int i = opening.end(); i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                int end = expression.indexOf(c, i + 1);
                if (end < 0) {
                    return false;
                }
                i = end;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i == expression.length() - 1;
            }
        }
        return false;
    }

    /** Splits on commas outside parentheses, brackets and quotes. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int end = text.indexOf(c, i + 1);
                i = end < 0 ? text.length() : end;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i).strip());
                from = i + 1;
            }
        }
        parts.add(text.substring(from).strip());
        return parts;
    }
}
