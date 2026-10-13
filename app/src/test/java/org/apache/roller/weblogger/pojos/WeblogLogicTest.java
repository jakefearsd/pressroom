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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the parts of {@link Weblog} that do more than hold a field.
 *
 * <p>Three groups of behaviour matter here. The sanitising setters
 * ({@code setName}, {@code setTagline}, {@code setAbout}) are the last line of
 * defence before those values are written into every page of the blog. The
 * locale and timezone accessors turn user-entered strings into JDK objects and
 * are relied on for every date the blog renders. And the convenience finders
 * that Velocity templates call ({@code getRecentWeblogEntries} and friends)
 * clamp their arguments before hitting the database -- a template asking for
 * a million entries must not get them.
 */
class WeblogLogicTest {

    private Weblog weblog;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
    }

    // -------------------------------------------------------- sanitisation

    @Test
    void markupIsStrippedFromTheFieldsRenderedOutsideHtmlContexts() {
        // Name, tagline and about are emitted into feeds, <title> and META
        // description tags, none of which can carry markup safely.
        weblog.setName("My <script>alert(1)</script> Blog");
        assertFalse(weblog.getName().contains("<script>"),
                "The weblog name is rendered into the page title and feed metadata; "
                        + "markup must be removed on the way in, not escaped at each use site");

        weblog.setTagline("A <b>bold</b> tagline");
        assertFalse(weblog.getTagline().contains("<b>"),
                "The tagline goes into the feed description and META tags");

        weblog.setAbout("<iframe src='evil'></iframe>All about me");
        assertFalse(weblog.getAbout().contains("<iframe"),
                "The about text is shown on the blog's about page");
        assertTrue(weblog.getAbout().contains("All about me"),
                "Stripping markup must keep the text the author actually wrote");
    }

    @Test
    void plainTextFieldsPassThroughUnchanged() {
        weblog.setName("Plain Name");
        assertEquals("Plain Name", weblog.getName(),
                "Text with no markup must survive untouched -- an over-eager filter that "
                        + "mangled ordinary names would be worse than none");
    }

    // ------------------------------------------------------ locale/timezone

    @Test
    void localeStringIsParsedIntoALocale() {
        weblog.setLocale("en_US");
        assertEquals(Locale.US, weblog.getLocaleInstance(),
                "The stored locale drives date formatting and message lookup for the "
                        + "whole blog");

        weblog.setLocale("fr");
        assertEquals(Locale.of("fr"), weblog.getLocaleInstance(),
                "A language-only locale must parse as such rather than being rejected");
    }

    @Test
    void aMissingLocaleFallsBackRatherThanThrowing() {
        weblog.setLocale(null);
        assertEquals(Locale.getDefault(), weblog.getLocaleInstance(),
                "A weblog with no locale must render with the server default; throwing "
                        + "here would take out every page of a half-configured blog");
    }

    @Test
    void aMissingTimeZoneIsFilledInFromTheServerDefault() {
        // getTimeZoneInstance deliberately writes the default back onto the
        // weblog, so the blog stops being ambiguous after the first render.
        weblog.setTimeZone(null);

        TimeZone resolved = weblog.getTimeZoneInstance();

        assertEquals(TimeZone.getDefault(), resolved);
        assertEquals(TimeZone.getDefault().getID(), weblog.getTimeZone(),
                "The resolved zone must be written back onto the weblog, otherwise every "
                        + "call re-resolves and a later server default change silently moves "
                        + "the blog's published timestamps");
    }

    @Test
    void anExplicitTimeZoneIsHonoured() {
        weblog.setTimeZone("America/New_York");
        assertEquals(TimeZone.getTimeZone("America/New_York"), weblog.getTimeZoneInstance());
    }

    // ------------------------------------------------------ defensive copies

    @Test
    void theCreationDateCannotBeMutatedThroughItsAccessor() {
        Date original = new Date(1_000_000L);
        weblog.setDateCreated(original);

        original.setTime(2_000_000L);
        assertEquals(1_000_000L, weblog.getDateCreated().getTime(),
                "setDateCreated must copy: java.util.Date is mutable, and holding a "
                        + "reference to the caller's instance lets them change a persisted "
                        + "field behind the entity's back");

        Date handedOut = weblog.getDateCreated();
        handedOut.setTime(3_000_000L);
        assertEquals(1_000_000L, weblog.getDateCreated().getTime(),
                "getDateCreated must copy on the way out for the same reason");
        assertNotSame(handedOut, weblog.getDateCreated());
    }

    @Test
    void aNullCreationDateStaysNull() {
        weblog.setDateCreated(null);
        assertNull(weblog.getDateCreated(),
                "The defensive copy must not turn a null date into an epoch date");
    }

    @Test
    void copyingOneWeblogOntoAnotherCarriesEveryEditableField() {
        // setData() refreshes a detached weblog from a form bean. A field it
        // forgets is a setting the administrator's edit silently discards, so
        // every one is given a value nothing else in this object has.
        WeblogCategory listed = new WeblogCategory();
        listed.setName("Travel");

        Weblog source = new Weblog();
        source.setId("weblog-1");
        source.setHandle("sourceblog");
        source.setName("Source Blog");
        source.setTagline("A tagline");
        source.setCreatorUserName("bob");
        source.setEmailAddress("owner@example.com");
        source.setEditorTheme("journal");
        source.setLocale("fr_FR");
        source.setTimeZone("America/New_York");
        source.setVisible(Boolean.FALSE);
        source.setDateCreated(new Date(1_700_000_000_000L));
        source.setEntryDisplayCount(37);
        source.setActive(Boolean.FALSE);
        source.setLastModified(new Date(1_800_000_000_000L));
        source.setWeblogCategories(new java.util.ArrayList<>(List.of(listed)));

        weblog.setData(source);

        assertEquals("weblog-1", weblog.getId());
        assertEquals("sourceblog", weblog.getHandle());
        assertEquals("Source Blog", weblog.getName());
        assertEquals("A tagline", weblog.getTagline());
        assertEquals("bob", weblog.getCreatorUserName());
        assertEquals("owner@example.com", weblog.getEmailAddress());
        assertEquals("journal", weblog.getEditorTheme());
        assertEquals("fr_FR", weblog.getLocale());
        assertEquals("America/New_York", weblog.getTimeZone());
        assertEquals(Boolean.FALSE, weblog.getVisible());
        assertEquals(new Date(1_700_000_000_000L), weblog.getDateCreated());
        assertEquals(37, weblog.getEntryDisplayCount());
        assertEquals(Boolean.FALSE, weblog.getActive());
        assertEquals(new Date(1_800_000_000_000L), weblog.getLastModified());
        assertEquals(List.of(listed), weblog.getWeblogCategories());
    }

    // ----------------------------------------------------------- categories

    @Test
    void categoriesMustBeNamedAndUnique() {
        WeblogCategory general = new WeblogCategory();
        general.setName("General");
        weblog.addCategory(general);

        assertTrue(weblog.hasCategory("General"));
        assertFalse(weblog.hasCategory("Travel"));

        WeblogCategory duplicate = new WeblogCategory();
        duplicate.setName("General");
        IllegalArgumentException clash =
                assertThrows(IllegalArgumentException.class, () -> weblog.addCategory(duplicate),
                        "Two categories with the same name would make the category URL "
                                + "ambiguous, so the duplicate must be rejected outright");
        assertTrue(clash.getMessage().contains("General"),
                "The error must name the offending category: " + clash.getMessage());

        assertThrows(IllegalArgumentException.class, () -> weblog.addCategory(null),
                "A null category must be rejected rather than added and NPE'd on later");
        assertThrows(IllegalArgumentException.class,
                () -> weblog.addCategory(new WeblogCategory()),
                "A category with no name has no URL and must be rejected");

        assertEquals(1, weblog.getWeblogCategories().size(),
                "None of the rejected categories may have been added");
    }

    // ---------------------------------------------------- media directories

    @Test
    void mediaFileDirectoriesAreFoundByName() {
        MediaFileDirectory photos = new MediaFileDirectory();
        photos.setName("photos");
        weblog.getMediaFileDirectories().add(photos);

        assertTrue(weblog.hasMediaFileDirectory("photos"));
        assertSame(photos, weblog.getMediaFileDirectory("photos"));

        assertFalse(weblog.hasMediaFileDirectory("videos"));
        assertNull(weblog.getMediaFileDirectory("videos"),
                "A directory that does not exist must read as null so the caller can "
                        + "create it, rather than returning an arbitrary other directory");
    }

    // urls: Weblog.getURL()/getAbsoluteURL() moved off the entity (the wrapper
    // and AdminUrls build them from an injected URLStrategy); see AdminUrlsTest
    // and WeblogWrapperDelegationTest for the relative/absolute-flavour checks.

    // recent entry finders, counts, tags, lookups: moved off the entity to
    // WeblogWrapper (plan Task 16) -- see WeblogWrapperDelegationTest.

    // theme: Weblog.getTheme() moved off the entity (plan Task 17) -- callers
    // ask the ThemeManager they hold; see WeblogWrapperDelegationTest.
}
