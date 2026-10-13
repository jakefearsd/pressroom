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

package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the string-to-Locale conversion used for weblog and user locales.
 *
 * <p>Locales arrive as strings from the database and from request parameters
 * ("en", "en_US", "en_US_WIN"). Parsing one wrongly picks the wrong resource
 * bundle and the wrong date format for a whole weblog, which is the sort of
 * thing that gets reported as "my blog is in English again".
 */
public class I18nUtilsTest {

    @Test
    public void parsesLanguageOnly() {
        assertEquals(Locale.of("en"), I18nUtils.toLocale("en"));
        assertEquals("en", I18nUtils.toLocale("en").getLanguage());
    }

    @Test
    public void parsesLanguageAndCountry() {
        Locale locale = I18nUtils.toLocale("en_US");
        assertEquals("en", locale.getLanguage());
        assertEquals("US", locale.getCountry());
    }

    @Test
    public void parsesLanguageCountryAndVariant() {
        Locale locale = I18nUtils.toLocale("en_US_WIN");
        assertEquals("en", locale.getLanguage());
        assertEquals("US", locale.getCountry());
        assertEquals("WIN", locale.getVariant());
    }

    @Test
    public void fallsBackToTheServerLocaleForNullAndEmptyInput() {
        // A weblog row with no locale set must still render.
        assertEquals(Locale.getDefault(), I18nUtils.toLocale(null));
        assertEquals(Locale.getDefault(), I18nUtils.toLocale(""));
    }

    @Test
    public void fallsBackToTheServerLocaleForSomethingWithTooManyParts() {
        // Four segments is not a locale this parser knows how to build, so it
        // degrades instead of throwing an ArrayIndexOutOfBoundsException.
        assertEquals(Locale.getDefault(), I18nUtils.toLocale("a_b_c_d"));
    }

    @Test
    public void treatsRunsOfUnderscoresAsASingleSeparator() {
        // StringUtils.split collapses empty tokens, so "en__US" is read as
        // language + country rather than as a malformed value.
        Locale locale = I18nUtils.toLocale("en__US");
        assertEquals("en", locale.getLanguage());
        assertEquals("US", locale.getCountry());
    }
}
