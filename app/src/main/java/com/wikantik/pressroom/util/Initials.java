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
package com.wikantik.pressroom.util;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * A reader's initials, one rule for the "You" menu (weblog.vm, through
 * {@code UtilitiesModel.initials}) and {@code reader.rol}'s {@code initials}.
 */
public final class Initials {

    private Initials() {
    }

    /**
     * The first letters of a name's first and last words, upper-cased: "AR"
     * for "Ann Marie Reader", "J" for "jake", "?" for no name.
     */
    public static String of(String name) {
        String[] words = StringUtils.split(StringUtils.defaultString(name));
        if (words.length == 0) {
            return "?";
        }
        String first = firstLetter(words[0]);
        return words.length == 1 ? first : first + firstLetter(words[words.length - 1]);
    }

    private static String firstLetter(String word) {
        return new String(Character.toChars(word.codePointAt(0))).toUpperCase(Locale.ROOT);
    }
}
