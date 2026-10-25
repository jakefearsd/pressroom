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
package org.apache.roller.weblogger.business;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.jpa.QueryRecorder;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.WeblogWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The site space: the hidden, system-owned weblog that will own the site's
 * pages. It must exist once, hold no permission rows, stay out of every
 * weblog listing and count, yet still resolve by handle.
 */
class SiteSpaceTest {

    private User admin;
    private Weblog ordinary;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        admin = TestUtils.setupUser("siteSpaceAdmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        ordinary = TestUtils.setupWeblog("sitespacesibling", admin);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog site = mgr.getSiteSpace();
        if (site == null) {
            // by handle too: a test that fails mid-way may leave one the
            // remembered absence hides
            site = mgr.getWeblogByHandle(Weblog.SITE_SPACE_HANDLE, null);
        }
        if (site != null) {
            mgr.removeWeblog(site);
        }
        TestUtils.teardownWeblog(ordinary.getId());
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
    }

    private WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private Weblog createSite() throws Exception {
        Weblog site = weblogs().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        return site;
    }

    @Test
    void getOrCreateIsIdempotent() throws Exception {
        assertNull(weblogs().getSiteSpace());
        Weblog first = createSite();
        TestUtils.endSession(true);
        Weblog second = createSite();

        assertEquals(first.getId(), second.getId());
        assertTrue(second.isSiteSpace());
        assertEquals("site-space", second.getHandle());
        assertEquals(Weblog.SITE_SPACE_HANDLE, second.getHandle());
        assertEquals("frontpage", second.getEditorTheme());
        assertEquals(second.getId(), weblogs().getSiteSpace().getId());
    }

    /**
     * "No site space" is remembered (the public mapper asks on every unclaimed
     * single-segment url), so it must be forgotten the moment one is created,
     * and learned again once it is removed.
     */
    @Test
    void anAbsentSiteSpaceIsRememberedOnlyUntilOneIsCreated() throws Exception {
        assertNull(weblogs().getSiteSpace());
        assertNull(weblogs().getSiteSpace());
        Weblog site = createSite();
        TestUtils.endSession(true);
        assertEquals(site.getId(), weblogs().getSiteSpace().getId());

        weblogs().removeWeblog(weblogs().getSiteSpace());
        TestUtils.endSession(true);
        assertNull(weblogs().getSiteSpace());

        Weblog again = createSite();
        TestUtils.endSession(true);
        assertEquals(again.getId(), weblogs().getSiteSpace().getId());
    }

    /**
     * A remembered absence must not outlive a site space that exists: one that
     * came to exist behind the memory's back (here: a weblog flagged directly)
     * is seen again once getOrCreateSiteSpace -- the admin opening the main
     * menu -- finds it.
     */
    @Test
    void aStaleAbsenceHealsWhenGetOrCreateFindsTheSiteSpace() throws Exception {
        assertNull(weblogs().getSiteSpace(), "absence now remembered");
        Weblog behindItsBack = TestUtils.setupWeblog(Weblog.SITE_SPACE_HANDLE, admin);
        behindItsBack.setSiteSpace(true);
        weblogs().saveWeblog(behindItsBack);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Weblog found = createSite();
        TestUtils.endSession(true);

        assertEquals(behindItsBack.getId(), found.getId(), "getOrCreate found it, not a second one");
        assertNotNull(weblogs().getSiteSpace(), "and the absence is forgotten");
        assertEquals(found.getId(), weblogs().getSiteSpace().getId());
    }

    /** A weblog merely holding the handle is not the site space. */
    @Test
    void aWeblogHoldingTheHandleWithoutTheFlagIsNotTheSiteSpace() throws Exception {
        Weblog impostor = TestUtils.setupWeblog(Weblog.SITE_SPACE_HANDLE, admin);
        TestUtils.endSession(true);
        try {
            assertNull(weblogs().getSiteSpace());
            assertNull(weblogs().getSiteSpace(), "nor once the handle is cached");
            assertThrows(WebloggerException.class, this::createSite,
                    "and none can be created over it");
        } finally {
            TestUtils.endSession(false);
            TestUtils.teardownWeblog(impostor.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void anAbsentSiteSpaceIsLookedForOnce() throws Exception {
        assertNull(weblogs().getSiteSpace());

        List<String> queries = QueryRecorder.during(() -> assertNull(weblogs().getSiteSpace()));

        assertEquals(List.of(), queries);
    }

    @Test
    void theSiteSpaceHasNoPermissionRows() throws Exception {
        Weblog site = createSite();
        assertTrue(TestUtils.weblogger().getUserManager().getWeblogPermissions(site).isEmpty());
        TestUtils.endSession(true);
        assertTrue(TestUtils.weblogger().getUserManager()
                .getWeblogPermissions(weblogs().getSiteSpace()).isEmpty());
    }

    @Test
    void everyListingLeavesItOut() throws Exception {
        long before = weblogs().getWeblogCount();
        Map<String, Long> lettersBefore = weblogs().getWeblogHandleLetterMap();
        createSite();
        TestUtils.endSession(true);

        assertFalse(handles(weblogs().getWeblogs(null, null, null, null, 0, -1)).contains("site-space"));
        assertFalse(handles(weblogs().getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1))
                .contains("site-space"));
        assertTrue(handles(weblogs().getWeblogs(null, null, null, null, 0, -1))
                .contains("sitespacesibling"));
        assertFalse(handles(weblogs().getWeblogsByLetter('S', 0, -1)).contains("site-space"));
        assertTrue(handles(weblogs().getWeblogsByLetter('S', 0, -1)).contains("sitespacesibling"));
        assertEquals(lettersBefore, weblogs().getWeblogHandleLetterMap());
        assertEquals(before, weblogs().getWeblogCount());
    }

    @Test
    void itStillResolvesByHandle() throws Exception {
        Weblog site = createSite();
        TestUtils.endSession(true);
        Weblog found = weblogs().getWeblogByHandle("site-space");
        assertNotNull(found);
        assertEquals(site.getId(), found.getId());
    }

    @Test
    void theWrapperSpeaksForTheSite() throws Exception {
        Weblog site = createSite();
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        String oldName = config.get("site.name").getValue();
        String oldDesc = config.get("site.description").getValue();
        try {
            config.get("site.name").setValue("Fish & Chips");
            config.get("site.description").setValue("Plain text");
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();

            URLStrategy urls = mock(URLStrategy.class);
            WeblogWrapper wrapped = WeblogWrapper.wrap(site, urls, TestUtils.weblogger());
            assertEquals("Fish &amp; Chips", wrapped.getName());
            assertEquals("Plain text", wrapped.getTagline());
            assertTrue(wrapped.isSiteSpace());

            WeblogWrapper other = WeblogWrapper.wrap(ordinary, urls, TestUtils.weblogger());
            assertEquals("Test Weblog", other.getName());
            assertEquals("Test Weblog", other.getTagline());
            assertFalse(other.isSiteSpace());
        } finally {
            config = pmgr.getProperties();
            config.get("site.name").setValue(oldName);
            config.get("site.description").setValue(oldDesc);
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
        }
        assertNotNull(WebloggerRuntimeConfig.getProperty("site.name"));
    }

    @Test
    void aSecondSiteSpaceIsRefused() throws Exception {
        createSite();
        TestUtils.endSession(true);

        Weblog second = new Weblog("sitespacetwo", admin.getUserName(), "Second", "Second",
                "x@dev.null", "journal", "en_US", "America/Los_Angeles");
        second.setSiteSpace(true);
        second.setDateCreated(new Date());
        assertThrows(Exception.class, () -> {
            weblogs().addWeblog(second);
            TestUtils.weblogger().flush();
        });
        TestUtils.weblogger().release();
    }

    @Test
    void aBlogHoldingTheHandleIsNamedInTheError() throws Exception {
        // an ordinary weblog cannot normally take this handle (the hyphen is
        // outside the allowed characters), so plant one directly
        Weblog squatter = new Weblog("site-space", admin.getUserName(), "Squatter", "Squatter",
                "x@dev.null", "journal", "en_US", "America/Los_Angeles");
        squatter.setDateCreated(new Date());
        weblogs().addWeblog(squatter);
        TestUtils.weblogger().flush();
        String id = squatter.getId();
        try {
            WebloggerException e = assertThrows(WebloggerException.class, this::createSite);
            assertTrue(e.getMessage().contains("site-space"));
        } finally {
            TestUtils.weblogger().release();
            TestUtils.teardownWeblog(id);
        }
    }

    private static List<String> handles(List<Weblog> list) {
        return list.stream().map(Weblog::getHandle).toList();
    }
}
