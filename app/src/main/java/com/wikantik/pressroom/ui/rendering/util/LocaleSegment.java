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

package com.wikantik.pressroom.ui.rendering.util;

import java.util.Locale;

/**
 * Whether a url path segment names a locale.
 *
 * <p>This is the first decision made about any weblog url, and it decides what
 * every segment after it means: read {@code /myblog/de/entry/x} with "de" as a
 * locale and "entry" is the context; read it as a context and the whole url is
 * something else entirely. A url is not rejected when this is wrong -- it is
 * silently understood as a different request.
 *
 * <p>It therefore has to be one rule. It was two: {@code WeblogRequest} and
 * {@code WeblogRequestMapper} each carried their own copy, byte for byte
 * identical, one deciding how to route a url and the other how to parse it.
 * They agreed only because nobody had edited either. CPD does not catch it --
 * the block is around a hundred tokens and the gate runs at two hundred.
 */
public final class LocaleSegment {

    private LocaleSegment() {}

    /**
     * True for {@code xx} and {@code xx_YY}, case-insensitively.
     *
     * <p>Only 2- and 5-character segments are considered at all, so a
     * three-letter language code is not a locale here, and neither is
     * {@code en-US} -- this scheme separates language from country with an
     * underscore. The consequence worth knowing: a static page whose slug is
     * exactly two letters can never be reached, because the segment is read as
     * a locale first. See WeblogPathInfoParsingTest.
     */
    public static boolean isLocale(String potentialLocale) {

        if (potentialLocale == null
                || (potentialLocale.length() != 2 && potentialLocale.length() != 5)) {
            return false;
        }

        // e.g. "en" or "en_US"; capitalisation is not checked
        String[] langCountry = potentialLocale.split("_");

        if (langCountry.length == 1) {
            return langCountry[0].length() == 2;
        }
        if (langCountry.length == 2) {
            return langCountry[0].length() == 2 && langCountry[1].length() == 2;
        }
        return false;
    }

    /**
     * Turns a locale string into a {@link Locale}.
     *
     * <p>Three places parsed this independently and did not agree.
     * AbstractWeblogEntriesPager and SearchResultsPager handled a three-part
     * string ("en_US_POSIX") as language/country/variant, carrying a comment
     * about the NullPointerException that used to result from not doing so --
     * I18nMessages.getMessages(Locale) dereferences its argument
     * unconditionally. WeblogRequest.getLocaleInstance handled one and two
     * parts and returned null for three, which is the bug that comment
     * describes, still present in the copy that never got the fix.
     *
     * <p>This is the complete version. A string with more than three parts uses
     * the first three; anything null-ish gives null, which every caller already
     * treats as "fall back to the weblog's own locale".
     */
    public static Locale toLocale(String localeString) {

        if (localeString == null) {
            return null;
        }

        String[] langCountry = localeString.split("_");

        if (langCountry.length == 1) {
            return Locale.of(langCountry[0]);
        }
        if (langCountry.length == 2) {
            return Locale.of(langCountry[0], langCountry[1]);
        }
        return Locale.of(langCountry[0], langCountry[1], langCountry[2]);
    }
}
