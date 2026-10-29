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
package com.wikantik.pressroom.business.jpa;

import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal and filter branches of {@link JPAWeblogManagerImpl}: what a
 * missing argument does (it is refused, never turned into a query that
 * matches everything), how the visible/active/date filters narrow a listing,
 * and how the handle cache behaves once the weblog behind it is gone.
 *
 * <p>Fixtures are removed by identity in {@code tearDown}.
 */
class JPAWeblogManagerBranchesTest {

    private User user;
    private Weblog first;
    private Weblog second;
    private WeblogManager mgr;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("wmbranches");
        first = TestUtils.setupWeblog("qzaone", user);
        second = TestUtils.setupWeblog("qzbtwo", user);
        TestUtils.endSession(true);
        mgr = TestUtils.weblogger().getWeblogManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String handle : new String[] {"qzaone", "qzbtwo"}) {
            Weblog w = mgr.getWeblogByHandle(handle, null);
            if (w != null) {
                TestUtils.teardownWeblog(w.getId());
            }
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static boolean contains(List<Weblog> weblogs, String handle) {
        return weblogs.stream().anyMatch(w -> handle.equals(w.getHandle()));
    }

    // ---------------------------------------------------------- argument guards

    @Test
    void templateLookupsRefuseAMissingWeblogOrMissingKey() {
        Weblog w = new Weblog();
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByLink(null, "x"));
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByLink(w, null));
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByAction(null, ThemeTemplate.ComponentType.WEBLOG));
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByAction(w, null));
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByName(null, "x"));
        assertThrows(WebloggerException.class, () -> mgr.getTemplateByName(w, null));
        assertThrows(WebloggerException.class, () -> mgr.getTemplates(null));
    }

    @Test
    void aHandleThatIsNullOrNotAlphanumericIsRefusedBeforeAnyLookup() {
        assertThrows(WebloggerException.class, () -> mgr.getWeblogByHandle(null, Boolean.TRUE));
        assertThrows(WebloggerException.class, () -> mgr.getWeblogByHandle("not a handle!", Boolean.TRUE));
    }

    @Test
    void aNullNewsletterListFindsNoWeblog() throws Exception {
        assertNull(mgr.getWeblogByNewsletterListUuid(null));
    }

    @Test
    void aNullUserHasNoWeblogs() throws Exception {
        assertTrue(mgr.getUserWeblogs(null, false).isEmpty());
    }

    // ------------------------------------------------------------ handle lookup

    @Test
    void aVisibilityMismatchHidesAWeblogEvenWhenItsHandleIsCached() throws Exception {
        Weblog w = mgr.getWeblogByHandle("qzaone", Boolean.TRUE);
        assertNotNull(w);
        w.setVisible(Boolean.FALSE);
        mgr.saveWeblog(w);
        TestUtils.endSession(true);

        assertNull(mgr.getWeblogByHandle("qzaone", Boolean.TRUE), "hidden weblog must not be served as visible");
        assertNotNull(mgr.getWeblogByHandle("qzaone", Boolean.FALSE));
        assertNotNull(mgr.getWeblogByHandle("qzaone", null), "null visibility means either");
    }

    @Test
    void aRemovedWeblogIsNotServedFromTheHandleCache() throws Exception {
        assertNotNull(mgr.getWeblogByHandle("qzbtwo", Boolean.TRUE), "primes the handle to id cache");
        TestUtils.teardownWeblog(second.getId());
        TestUtils.endSession(true);

        assertNull(mgr.getWeblogByHandle("qzbtwo", Boolean.TRUE), "a stale cache entry must not resurrect it");
    }

    // ---------------------------------------------------------------- listings

    @Test
    void getWeblogsNarrowsByVisibleActiveAndCreationWindow() throws Exception {
        Date past = new Date(System.currentTimeMillis() - 3_600_000L);
        Date future = new Date(System.currentTimeMillis() + 3_600_000L);

        assertTrue(contains(mgr.getWeblogs(null, null, past, future, 0, -1), "qzaone"));
        assertFalse(contains(mgr.getWeblogs(null, null, future, null, 0, -1), "qzaone"),
                "created before the window opens");
        assertFalse(contains(mgr.getWeblogs(null, null, null, past, 0, -1), "qzaone"),
                "created after the window closes");

        Weblog w = mgr.getWeblogByHandle("qzaone", null);
        w.setActive(Boolean.FALSE);
        mgr.saveWeblog(w);
        TestUtils.endSession(true);

        assertFalse(contains(mgr.getWeblogs(null, Boolean.TRUE, null, null, 0, -1), "qzaone"));
        assertTrue(contains(mgr.getWeblogs(null, Boolean.FALSE, null, null, 0, -1), "qzaone"));
        assertTrue(contains(mgr.getWeblogs(Boolean.TRUE, Boolean.FALSE, past, future, 0, -1), "qzaone"));
        assertFalse(contains(mgr.getWeblogs(Boolean.FALSE, Boolean.FALSE, past, future, 0, -1), "qzaone"));
    }

    @Test
    void getWeblogsPagesByOffsetAndLength() throws Exception {
        List<Weblog> all = mgr.getWeblogs(null, null, null, null, 0, -1);
        List<Weblog> firstPage = mgr.getWeblogs(null, null, null, null, 0, 1);
        List<Weblog> secondPage = mgr.getWeblogs(null, null, null, null, 1, 1);

        assertEquals(1, firstPage.size());
        assertEquals(1, secondPage.size());
        assertEquals(all.get(0).getId(), firstPage.get(0).getId());
        assertEquals(all.get(1).getId(), secondPage.get(0).getId());
    }

    @Test
    void weblogsByLetterListsTheMatchingHandlesInOrderAndPages() throws Exception {
        List<Weblog> all = mgr.getWeblogsByLetter('Q', 0, -1);
        List<String> handles = all.stream().map(Weblog::getHandle).toList();
        assertTrue(handles.indexOf("qzaone") >= 0 && handles.indexOf("qzaone") < handles.indexOf("qzbtwo"),
                "ordered by handle");

        List<Weblog> pagedPast = mgr.getWeblogsByLetter('Q', all.size() - 1, 5);
        assertEquals(1, pagedPast.size(), "offset moves the window, length caps it");
        assertEquals(handles.get(handles.size() - 1), pagedPast.get(0).getHandle());
        assertEquals(1, mgr.getWeblogsByLetter('Q', 0, 1).size());
    }

    @Test
    void userWeblogsAndWeblogUsersHonourTheEnabledOnlyFilter() throws Exception {
        Weblog hidden = mgr.getWeblogByHandle("qzbtwo", null);
        hidden.setVisible(Boolean.FALSE);
        mgr.saveWeblog(hidden);
        TestUtils.endSession(true);

        User managed = TestUtils.getManagedUser(user);
        List<String> everything = mgr.getUserWeblogs(managed, false).stream().map(Weblog::getHandle).toList();
        List<String> visibleOnly = mgr.getUserWeblogs(managed, true).stream().map(Weblog::getHandle).toList();
        assertTrue(everything.contains("qzbtwo"));
        assertFalse(visibleOnly.contains("qzbtwo"), "a hidden weblog is not listed when enabledOnly");
        assertTrue(visibleOnly.contains("qzaone"));

        managed.setEnabled(Boolean.FALSE);
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.endSession(true);
        Weblog w = mgr.getWeblogByHandle("qzaone", null);
        assertEquals(0, mgr.getWeblogUsers(w, true).size(), "a disabled account is not listed when enabledOnly");
        assertEquals(1, mgr.getWeblogUsers(w, false).size(),
                "enabledOnly=false means every member, disabled or not");
    }

    /**
     * Found by this class: removing a weblog revoked each member's permission
     * through {@code getUserByUserName(name)}, which is enabled-only, so a
     * disabled member came back null and the revoke threw a
     * NullPointerException -- a weblog with a disabled member could not be
     * deleted at all.
     */
    @Test
    void aWeblogWithADisabledMemberCanStillBeRemoved() throws Exception {
        User managed = TestUtils.getManagedUser(user);
        managed.setEnabled(Boolean.FALSE);
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.endSession(true);

        TestUtils.teardownWeblog(mgr.getWeblogByHandle("qzaone", null).getId());
        TestUtils.endSession(true);

        assertNull(mgr.getWeblogByHandle("qzaone", null));
        assertTrue(TestUtils.weblogger().getUserManager().getWeblogPermissions(managed).stream()
                .noneMatch(p -> "qzaone".equals(p.getObjectId())), "its permission rows must go with it");
    }
}
