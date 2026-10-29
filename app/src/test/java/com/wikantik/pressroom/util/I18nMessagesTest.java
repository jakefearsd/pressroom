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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the message lookup used by every rendered page and notification email.
 *
 * <p>The two behaviours worth protecting are that a missing key degrades to
 * the key itself instead of throwing (a template must never 500 because a
 * translation is absent) and that instances are cached per locale, since a new
 * ResourceBundle lookup per message would be a per-request cost.
 */
public class I18nMessagesTest {

    private static final String KNOWN_KEY = "generic.tagline";
    private static final String KEY_WITH_ARGUMENT = "categoryForm.changesSaved";

    @Test
    public void looksUpAMessageFromTheBundle() {
        assertEquals("Tagline", I18nMessages.getMessages(Locale.ENGLISH).getString(KNOWN_KEY));
    }

    @Test
    public void returnsTheKeyItselfWhenTheMessageIsMissing() {
        // A template asking for a key that no longer exists must render the
        // key, not blow up the page. Same for the two formatting overloads.
        I18nMessages messages = I18nMessages.getMessages(Locale.ENGLISH);
        assertEquals("no.such.key.exists", messages.getString("no.such.key.exists"));
        assertEquals("no.such.key.exists", messages.getString("no.such.key.exists", new Object[]{"x"}));
        assertEquals("no.such.key.exists", messages.getString("no.such.key.exists", List.of("x")));
    }

    @Test
    public void substitutesArgumentsFromAnArrayAndFromAList() {
        I18nMessages messages = I18nMessages.getMessages(Locale.ENGLISH);
        assertEquals("Category \"Roller\" updated",
                messages.getString(KEY_WITH_ARGUMENT, new Object[]{"Roller"}));
        assertEquals("Category \"Roller\" updated",
                messages.getString(KEY_WITH_ARGUMENT, List.of("Roller")));
    }

    @Test
    public void servesTheTranslationForTheRequestedLocale() {
        // The weblog's locale, not the server's, decides the language of a
        // notification email.
        assertEquals("Abbrechen", I18nMessages.getMessages(Locale.GERMAN).getString("generic.cancel"));
        assertEquals("Cancel", I18nMessages.getMessages(Locale.ENGLISH).getString("generic.cancel"));
    }

    @Test
    public void instancesAreCachedPerLocale() {
        assertSame(I18nMessages.getMessages(Locale.ITALIAN), I18nMessages.getMessages(Locale.ITALIAN));
        assertNotSame(I18nMessages.getMessages(Locale.ITALIAN), I18nMessages.getMessages(Locale.GERMAN));
    }

    @Test
    public void theStringAndLocaleLookupsShareOneCacheEntry() {
        // getMessages("fr") and getMessages(Locale.FRENCH) must not build two
        // separate bundles for the same language.
        assertSame(I18nMessages.getMessages(Locale.FRENCH), I18nMessages.getMessages("fr"));
        assertEquals(Locale.FRENCH, I18nMessages.getMessages("fr").getLocale());
    }

    @Test
    public void aLanguageWithNoTranslationFallsBackToTheDefaultBundle() {
        // Nothing is shipped for Klingon, so the base bundle answers rather
        // than the lookup failing.
        assertEquals("Tagline", I18nMessages.getMessages("tlh").getString(KNOWN_KEY));
    }

    @Test
    public void reloadingABundleDropsTheCachedInstanceSoNewTextIsPickedUp() {
        // The admin "reload messages" action exists so that an edited
        // properties file takes effect without a restart. If the cached
        // instance survives the reload, the action does nothing at all and
        // the old text is served until the JVM is bounced.
        Locale locale = Locale.KOREAN;
        I18nMessages before = I18nMessages.getMessages(locale);

        I18nMessages.reloadBundle(locale);

        assertNotSame(before, I18nMessages.getMessages(locale),
                "reloadBundle() left the previous I18nMessages in the cache, so the admin "
                        + "'reload messages' function silently does nothing.");
    }

    @Test
    public void reloadingABundleAlsoClearsTheJdksOwnBundleCache() {
        // Dropping our cached wrapper is not enough: ResourceBundle keeps its
        // own cache, so without clearing that too the "new" instance reads the
        // same stale properties. Identity of the bundle is the only visible
        // proof that the file would actually be re-read.
        ResourceBundle before = ResourceBundle.getBundle("ApplicationResources", Locale.JAPANESE);

        I18nMessages.reloadBundle(Locale.JAPANESE);

        assertNotSame(before, ResourceBundle.getBundle("ApplicationResources", Locale.JAPANESE),
                "The JDK's ResourceBundle cache still holds the old bundle, so an edited "
                        + "properties file will not be picked up until the JVM restarts.");
    }
}
