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
package org.apache.roller.weblogger.pojos.wrapper;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Checks {@link WeblogWrapper}'s delegation and the one piece of real logic it
 * owns: turning a stored icon path into a usable URL.
 */
class WeblogWrapperTest {

    private Weblog weblog;
    private URLStrategy urls;
    private Weblogger weblogger;
    private WeblogWrapper wrapper;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setName("My Blog");
        weblog.setTagline("A tagline");
        weblog.setAbout("All about me");
        weblog.setEmailAddress("owner@example.com");
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/New_York");
        weblog.setEntryDisplayCount(37);
        weblog.setAnalyticsSiteId("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        weblog.setAnalyticsShareUrl("https://analytics.example.com/share/abc123");
        weblog.setNewsletterListUuid("94b12e10-1234-4321-aaaa-2c963f66afa6");
        weblog.setDateCreated(new Date(1_700_000_000_000L));
        weblog.setVisible(Boolean.FALSE);
        weblog.setActive(Boolean.TRUE);

        urls = mock(URLStrategy.class);
        weblogger = mock(Weblogger.class);
        wrapper = WeblogWrapper.wrap(weblog, urls, weblogger);
    }

    @Test
    void scalarAccessorsReportTheWrappedWeblogsOwnValues() {
        // Every value here is distinct, so an accessor reading the wrong field
        // shows up rather than coincidentally matching.
        assertEquals(weblog.getId(), wrapper.getId());
        assertEquals("testblog", wrapper.getHandle());
        assertEquals("A tagline", wrapper.getTagline());
        assertEquals("All about me", wrapper.getAbout());
        assertEquals("owner@example.com", wrapper.getEmailAddress());
        assertEquals("journal", wrapper.getEditorTheme());
        assertEquals("en_US", wrapper.getLocale());
        assertEquals("America/New_York", wrapper.getTimeZone());
        assertEquals(37, wrapper.getEntryDisplayCount());
        assertEquals(new Date(1_700_000_000_000L), wrapper.getDateCreated());
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", wrapper.getAnalyticsSiteId());
        assertEquals("https://analytics.example.com/share/abc123", wrapper.getAnalyticsShareUrl());
        assertEquals("94b12e10-1234-4321-aaaa-2c963f66afa6", wrapper.getNewsletterListUuid());
        assertEquals(Locale.US, wrapper.getLocaleInstance());
        assertEquals(TimeZone.getTimeZone("America/New_York"), wrapper.getTimeZoneInstance());
    }

    /**
     * {@code getLanguageTag} exists because {@code <html lang="...">} needs
     * BCP-47 and the stored locale is Java's underscore form: emitting
     * {@code lang="en_US"} straight from {@code getLocale()} is a value no
     * user agent recognises, so the document silently has no declared
     * language at all. The three cases below are the three shapes a stored
     * locale actually takes -- language+country, bare language, and unset
     * (Weblog#getLocaleInstance falls back to the JVM default, which is
     * forced here so the assertion does not depend on the build machine).
     */
    @Test
    void theLanguageTagIsTheBcp47FormOfTheStoredLocale() {
        assertEquals("en-US", wrapper.getLanguageTag());

        weblog.setLocale("fr");
        assertEquals("fr", wrapper.getLanguageTag());

        Locale previousDefault = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            weblog.setLocale(null);
            assertEquals("de-DE", wrapper.getLanguageTag());
        } finally {
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    void everyBooleanFlagTracksItsOwnFieldInBothStates() {
        // A boolean accessor has only two possible answers, so reading it once
        // proves nothing: an accessor hard-wired to a constant, or wired to the
        // neighbouring flag, passes half the time. Each flag is therefore
        // flipped and read again, and every other flag is held at the opposite
        // value so a mis-wired accessor cannot agree by coincidence.
        setAllFlags(false);
        assertEquals(Boolean.FALSE, wrapper.getVisible());
        assertEquals(Boolean.FALSE, wrapper.getActive());
        assertEquals(Boolean.FALSE, wrapper.getEnabled(),
                "the deprecated alias follows visible in both states too");

        setAllFlags(true);
        assertEquals(Boolean.TRUE, wrapper.getVisible(),
                "A weblog switched back to visible must report as visible; a getVisible() "
                        + "stuck at one answer either hides every blog or publishes every "
                        + "hidden one");
        assertEquals(Boolean.TRUE, wrapper.getActive());
        assertEquals(Boolean.TRUE, wrapper.getEnabled());
    }

    private void setAllFlags(boolean value) {
        weblog.setVisible(value);
        weblog.setActive(value);
    }

    /**
     * The comments section offers no form on a custom-domain weblog (its
     * session cookie lives on the site host), so the template has to be able
     * to ask; and it asks the weblog, never the request, because the
     * rendered page is cached by handle and shared by both hosts.
     */
    @Test
    void theCustomDomainIsTheWeblogsOwn() {
        assertNull(wrapper.getCustomDomain());
        weblog.setCustomDomain("blog.example.com");
        assertEquals("blog.example.com", wrapper.getCustomDomain());
    }

    @Test
    void theDeprecatedEnabledAliasStillMirrorsVisible() {
        // Themes written for Roller 5.0 call $weblog.enabled; it has to keep
        // answering the same as $weblog.visible or those themes start lying.
        assertEquals(wrapper.getVisible(), wrapper.getEnabled());
    }

    @Test
    void theWeblogNameIsHtmlEscapedRatherThanStripped() {
        // The name is emitted into attributes and page titles, where escaping
        // (not removal) is what keeps an apostrophe or ampersand rendering.
        weblog.setName("Tea & Biscuits");

        assertEquals("Tea &amp; Biscuits", wrapper.getName(),
                "An ampersand in a blog name must reach the page as an entity; leaving it "
                        + "raw produces invalid markup in the feed");
    }

    @Test
    void freeTextFieldsRunThroughTheSanitiser() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            weblog.setTagline("Hand-built guides <script>alert(1)</script>");
            weblog.setAbout("About us <img src=x onerror=alert(1)>");

            assertFalse(wrapper.getTagline().contains("<script"),
                    "the tagline is emitted by theme headers, some of them unescaped");
            assertFalse(wrapper.getAbout().contains("onerror"),
                    "the about text is emitted into pages too");
            assertTrue(wrapper.getTagline().contains("Hand-built guides"),
                    "and the author's actual words must survive: " + wrapper.getTagline());
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void anAbsoluteIconUrlIsLeftAlone() {
        weblog.setIconPath("http://example.com/icon.png");

        assertEquals("http://example.com/icon.png", wrapper.getIcon(),
                "An icon hosted elsewhere must be used as given");
        verifyNoInteractions(urls);
    }

    @Test
    void aRootRelativeIconPathIsLeftAlone() {
        weblog.setIconPath("/static/icon.png");

        assertEquals("/static/icon.png", wrapper.getIcon(),
                "A path already rooted at the server is usable as-is");
        verifyNoInteractions(urls);
    }

    @Test
    void aBareIconFilenameIsResolvedAgainstTheWeblogsUploads() {
        weblog.setIconPath("icon.png");
        when(urls.getWeblogResourceURL(weblog, "icon.png", false))
                .thenReturn("/roller/testblog/resource/icon.png");

        assertEquals("/roller/testblog/resource/icon.png", wrapper.getIcon(),
                "A bare filename names a file in the weblog's own uploads area and has "
                        + "to be expanded into a URL; emitting it unchanged would resolve "
                        + "against whatever page happened to be rendering");
    }

    @Test
    void aWeblogWithNoIconHasNoIconUrl() {
        weblog.setIconPath(null);

        assertNull(wrapper.getIcon(),
                "No icon must read as null so the theme can omit the <img> altogether");
        verifyNoInteractions(urls);
    }

    @Test
    void categoriesComeBackWrapped() {
        WeblogCategory general = new WeblogCategory();
        general.setName("General");
        weblog.getWeblogCategories().add(general);

        List<WeblogCategoryWrapper> categories = wrapper.getWeblogCategories();
        assertEquals(1, categories.size());
        assertEquals("General", categories.get(0).getName(),
                "Categories reach the theme wrapped, so a template cannot rename one");
    }

    @Test
    void wrappingNullGivesNull() {
        assertNull(WeblogWrapper.wrap(null, urls, weblogger));
    }

    @Test
    void thePojoEscapeHatchReturnsTheVeryObjectThatWasWrapped() {
        assertSame(weblog, wrapper.getPojo());
    }
}
