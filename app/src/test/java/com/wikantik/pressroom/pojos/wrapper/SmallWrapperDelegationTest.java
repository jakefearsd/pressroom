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
package com.wikantik.pressroom.pojos.wrapper;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogEntryAttribute;
import com.wikantik.pressroom.pojos.WeblogEntryTag;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delegation checks for the smaller wrappers.
 *
 * <p>Each accessor is asserted against a value only that field carries, which
 * is what separates a real delegation from a hard-coded constant or a copy of
 * the neighbouring accessor.
 */
class SmallWrapperDelegationTest {

    private final URLStrategy urls = mock(URLStrategy.class);
    private final Weblogger tier = mock(Weblogger.class);

    // ------------------------------------------------------------- category

    @Test
    void categoryWrapperExposesTheCategoryReadOnly() throws Exception {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");
        category.setDescription("Places I went");
        category.setImage("suitcase.png");
        category.setWeblog(weblog);

        WeblogCategoryWrapper wrapper = WeblogCategoryWrapper.wrap(category, urls, tier);

        assertEquals(category.getId(), wrapper.getId());
        assertEquals("Travel", wrapper.getName());
        assertEquals("Places I went", wrapper.getDescription());
        assertEquals("suitcase.png", wrapper.getImage());
        assertEquals("testblog", wrapper.getWebsite().getHandle(),
                "The owning weblog is reachable through the wrapper, itself wrapped");
        assertNull(WeblogCategoryWrapper.wrap(null, urls, tier));
    }

    @Test
    void categoryWrapperReportsWhetherTheCategoryIsInUse() throws Exception {
        // The delete button in the admin UI is enabled from this; an accessor
        // stuck at one answer either blocks every delete or allows a delete
        // that orphans entries.
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");

        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);

        // The wrapper asks the entry manager of the facade it was given; the
        // pojo no longer has an isInUse() at all (plan Task 16).
        WeblogCategoryWrapper wrapper = WeblogCategoryWrapper.wrap(category, urls, weblogger);

        when(entries.isWeblogCategoryInUse(category)).thenReturn(true);
        assertTrue(wrapper.isInUse());

        when(entries.isWeblogCategoryInUse(category)).thenReturn(false);
        assertFalse(wrapper.isInUse());
    }

    @Test
    void categoryWrapperAsksForAllEntriesIncludingTrashedWhenNotPublishedOnly() throws Exception {
        // The "all entries" form is what the category-deletion guard and the
        // move-contents sequence rely on, and it must see trashed entries (a
        // trashed entry still blocks deleting its category). The published-only
        // form is the theme's view and excludes them by construction.
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        WeblogCategoryWrapper wrapper = WeblogCategoryWrapper.wrap(category, urls, weblogger);

        wrapper.retrieveWeblogEntries(false);
        wrapper.retrieveWeblogEntries(true);

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entries, times(2)).getWeblogEntries(criteria.capture());
        WeblogEntrySearchCriteria all = criteria.getAllValues().get(0);
        WeblogEntrySearchCriteria published = criteria.getAllValues().get(1);
        assertTrue(all.isIncludeTrashed());
        assertNull(all.getStatus());
        assertEquals("Travel", all.getCatName());
        assertFalse(published.isIncludeTrashed());
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, published.getStatus());
    }

    @Test
    void userWrapperShowsTheLoginNameWhenTheSiteAllowsIt() {
        // The other branch of the hideUserNames switch. Pinning the setting here
        // rather than reading it means both branches are exercised whichever way
        // the shipped default happens to be set.
        User user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice A");

        // Mocked on WebloggerRuntimeConfig, not WebloggerConfig: the property is
        // runtime-settable, so the database value decides and the startup file
        // is consulted only when no row exists. Stubbing the file here would
        // pin nothing.
        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(WebloggerRuntimeConfig.class)) {
            config.when(() -> WebloggerRuntimeConfig.getBooleanProperty("user.hideUserNames"))
                    .thenReturn(false);
            assertEquals("alice", UserWrapper.wrap(user).getUserName(),
                    "With hiding off the real login name is published");

            config.when(() -> WebloggerRuntimeConfig.getBooleanProperty("user.hideUserNames"))
                    .thenReturn(true);
            assertEquals("Alice A", UserWrapper.wrap(user).getUserName(),
                    "With hiding on the screen name stands in, so a blog never publishes "
                            + "half of somebody's credentials");
        }
    }

    @Test
    void categoryWrapperHandsOutWrappedEntries() throws Exception {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");
        category.setWeblog(weblog);

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("a-trip");
        entry.setTitle("A trip");

        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(entry));

        List<WeblogEntryWrapper> wrapped =
                WeblogCategoryWrapper.wrap(category, urls, weblogger).retrieveWeblogEntries(true);

        assertEquals(1, wrapped.size());
        assertEquals("A trip", wrapped.get(0).getTitle(),
                "Entries reached through a category must come back wrapped too, or "
                        + "the read-only barrier has a hole in it");
    }

    // ------------------------------------------------------------------ tag

    @Test
    void tagWrapperExposesNameAndTime() {
        WeblogEntryTag tag = new WeblogEntryTag();
        tag.setName("java");
        tag.setTime(Timestamp.valueOf("2024-03-09 15:30:00"));

        WeblogEntryTagWrapper wrapper = WeblogEntryTagWrapper.wrap(tag, tier);

        assertEquals("java", wrapper.getName());
        assertEquals(Timestamp.valueOf("2024-03-09 15:30:00"), wrapper.getTime());
        assertNull(WeblogEntryTagWrapper.wrap(null, tier));
    }

    // ------------------------------------------------------------ attribute

    @Test
    void attributeWrapperExposesNameAndValue() {
        WeblogEntryAttribute attribute = new WeblogEntryAttribute();
        attribute.setName("mood");
        attribute.setValue("cheerful");

        WeblogEntryAttributeWrapper wrapper = WeblogEntryAttributeWrapper.wrap(attribute);

        assertEquals("mood", wrapper.getName());
        assertEquals("cheerful", wrapper.getValue());
        assertNull(WeblogEntryAttributeWrapper.wrap(null));
    }

    // -------------------------------------------------------- theme template

    @Test
    void themeTemplateWrapperExposesTheTemplateMetadata() throws Exception {
        WeblogTemplate template = new WeblogTemplate();
        template.setId("template-1");
        template.setName("About");
        template.setDescription("The about page");
        template.setLink("about");
        template.setLastModified(new Date(1_700_000_000_000L));
        template.setHidden(true);
        template.setNavbar(false);
        new CustomTemplateRendition(template, RenditionType.STANDARD);

        ThemeTemplateWrapper wrapper = ThemeTemplateWrapper.wrap(template);

        assertEquals("template-1", wrapper.getId());
        assertEquals("About", wrapper.getName());
        assertEquals("The about page", wrapper.getDescription());
        assertEquals("about", wrapper.getLink());
        assertEquals(new Date(1_700_000_000_000L), wrapper.getLastModified());
        assertTrue(wrapper.isHidden(),
                "A hidden template must still report as hidden through the wrapper -- "
                        + "themes use this to decide what to link to");
        assertFalse(wrapper.isNavbar(),
                "and the navbar flag must not be confused with the hidden flag");

        // Flip both so neither accessor can pass by being hard-wired to one
        // answer or to the other flag.
        template.setHidden(false);
        template.setNavbar(true);
        assertFalse(wrapper.isHidden(),
                "A template made visible again must stop reporting as hidden, or every "
                        + "template disappears from the theme's navigation");
        assertTrue(wrapper.isNavbar());

        assertNull(ThemeTemplateWrapper.wrap(null));
    }

    // ------------------------------------------------------------------ user

    @Test
    void userWrapperHidesTheLoginNameWhenConfiguredTo() {
        // user.hideUserNames exists so a blog does not publish the string an
        // attacker needs half of to log in.
        User user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice A");
        user.setFullName("Alice Anderson");
        user.setEmailAddress("alice@example.com");
        user.setLocale("en_US");
        user.setTimeZone("America/New_York");
        user.setDateCreated(new Date(1_700_000_000_000L));

        UserWrapper wrapper = UserWrapper.wrap(user);
        boolean hidden = WebloggerConfig.getBooleanProperty("user.hideUserNames");

        assertEquals(hidden ? "Alice A" : "alice", wrapper.getUserName(),
                "With user.hideUserNames=" + hidden + " the wrapper must return the "
                        + (hidden ? "screen name instead of the login name" : "login name"));
        assertEquals("Alice A", wrapper.getScreenName());
        assertEquals("Alice Anderson", wrapper.getFullName());
        assertEquals("alice@example.com", wrapper.getEmailAddress());
        assertEquals("en_US", wrapper.getLocale());
        assertEquals("America/New_York", wrapper.getTimeZone());
        assertEquals(new Date(1_700_000_000_000L), wrapper.getDateCreated());
        assertNull(UserWrapper.wrap(null));
    }

    @Test
    void userWrapperSanitisesTheNamesItPublishes() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            // Store the raw value first: the User setters sanitise too, so with
            // the flag already on the pojo would be clean and the wrapper's own
            // sanitising would be untested.
            HTMLSanitizer.xssEnabled = Boolean.FALSE;
            User user = new User();
            user.setScreenName("<script>alert(1)</script>Alice");
            user.setFullName("<script>alert(1)</script>Alice Anderson");
            assertTrue(user.getScreenName().contains("<script>"),
                    "Precondition: the stored value really is unsanitised");

            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            UserWrapper wrapper = UserWrapper.wrap(user);

            assertFalse(wrapper.getScreenName().contains("<script>"),
                    "A screen name stored before sanitising was switched on -- or written "
                            + "by any path that bypasses the setter -- must still be cleaned "
                            + "on the way out to a theme");
            assertFalse(wrapper.getFullName().contains("<script>"));
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void tagWrapperResolvesItsUserThroughTheUserManager() throws Exception {
        WeblogEntryTag tag = new WeblogEntryTag();
        tag.setName("java");
        tag.setCreatorUserName("alice");

        User alice = new User();
        alice.setUserName("alice");
        alice.setScreenName("Alice A");

        UserManager users = mock(UserManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);

        assertEquals("Alice A", WeblogEntryTagWrapper.wrap(tag, weblogger).getUser().getScreenName(),
                "The tag's creator must be reachable, wrapped, through the tier the wrapper was given");
    }

    @Test
    void aMissingUserResolvesToNullRatherThanBreakingTheRender() throws Exception {
        WeblogEntryTag tag = new WeblogEntryTag();
        tag.setName("java");
        tag.setCreatorUserName("deleted-user");

        UserManager users = mock(UserManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("deleted-user"))
                .thenThrow(new WebloggerException("no such user"));

        assertNull(WeblogEntryTagWrapper.wrap(tag, weblogger).getUser(),
                "A tag left behind by a deleted user must render as having no user "
                        + "rather than taking the page down");
    }
}
