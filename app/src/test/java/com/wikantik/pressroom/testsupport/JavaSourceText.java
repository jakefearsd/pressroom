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
package com.wikantik.pressroom.testsupport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code of a Java source file, for the source-scanning tests: comments
 * removed and every string and character literal emptied, in one pass.
 *
 * <p>Two separate regexes (block comments, then line comments) went wrong on
 * literals: {@code "*}{@code /*"} opened a "comment" that swallowed the
 * following lines up to the next real comment end, and {@code "http://x"}
 * cut the rest of its line. Scanning left to right and taking whichever of a
 * literal or a comment starts first keeps a comment marker inside a literal
 * from counting. Literals keep their quotes, so a pattern looking for a
 * literal argument still sees one.
 */
public final class JavaSourceText {

    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\\\n])*\"" // a string literal
                    + "|'(?:\\\\.|[^'\\\\\\n])+'" // a character literal
                    + "|(?s:/\\*.*?\\*/)" // a block comment
                    + "|//[^\\n]*"); // a line comment

    private JavaSourceText() {
    }

    /** {@code source} with comments removed and literals emptied ({@code ""}, {@code ''}). */
    public static String code(String source) {
        Matcher m = TOKEN.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            String replacement = switch (token.charAt(0)) {
                case '"' -> "\"\"";
                case '\'' -> "''";
                default -> token.startsWith("/*") ? keepNewlines(token) : "";
            };
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A block comment's line breaks, so line-based reporting keeps its numbers. */
    private static String keepNewlines(String comment) {
        return comment.replaceAll("[^\\n]", "");
    }
}
