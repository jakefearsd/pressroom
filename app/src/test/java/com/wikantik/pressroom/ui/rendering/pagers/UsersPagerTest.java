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

package com.wikantik.pressroom.ui.rendering.pagers;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsersPager}, which backs the site-wide member
 * directory and its A-Z letter pages.
 *
 * <p>Same shape as {@link WeblogsPagerTest}: the page-size arithmetic has to be
 * exact at both ends, and the letter listing has its own link builders that
 * must not lose the letter while paging.
 */
class UsersPagerTest {

    private static final String BASE_URL = "http://localhost/roller/users";
    private static final int LENGTH = 4;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    private static List<User> users(int count) {
        List<User> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = new User();
            user.setUserName("user" + i);
            list.add(user);
        }
        return list;
    }

    /** The facade the pager under test is constructed with; set per test by {@link #withUserManager}. */
    private Weblogger weblogger;

    /**
     * Runs the body with {@link #weblogger} answering with the supplied manager.
     * Nothing is installed statically: the pager takes the facade in its
     * constructor (plan Task 11), so a regression to static location would
     * reach whatever -- if anything -- the JVM-wide shim holds, not this mock.
     */
    private void withUserManager(UserManager manager, Runnable body) {
        weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(manager);
        body.run();
    }

    @Test
    void usersComeFromTheInjectedFacadesUserManager() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(users(2));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);
            assertEquals(2, pager.getItems().size(), "the injected manager's users are the page");
        });
        verify(manager).getUsers(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void exactlyOnePageOfResultsReportsNoFurtherPages() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(users(LENGTH));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size());
            assertFalse(pager.hasMoreItems(),
                    "A page filled exactly to the limit is the last page");
            assertNull(pager.getNextLink());
        });
    }

    @Test
    void oneExtraRowReportsAFurtherPageButIsNotItselfDisplayed() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(users(LENGTH + 1));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size(),
                    "The extra probe row must not be rendered");
            assertTrue(pager.hasMoreItems());
            assertEquals(BASE_URL + "?page=1", pager.getNextLink());
        });
    }

    @Test
    void queryAsksForOneRowMoreThanThePageSizeAtTheRightOffset() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt())).thenReturn(users(0));

        withUserManager(manager, () ->
                new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 2, LENGTH));

        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> max = ArgumentCaptor.forClass(Integer.class);
        verify(manager).getUsers(any(), any(), any(), offset.capture(), max.capture());

        assertEquals(2 * LENGTH, offset.getValue(),
                "Page 2 of 4-item pages starts at row 8");
        assertEquals(LENGTH + 1, max.getValue(),
                "One extra row is fetched purely to detect a further page");
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingThePage() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertTrue(pager.getItems().isEmpty(),
                    "A failed lookup must yield an empty list, never null");
        });
    }

    @Test
    void plainListingDelegatesItsPreviousLinkToTheBasePager() throws Exception {
        // Without a letter the overrides fall through to AbstractPager, which
        // is a separate code path from the letter-carrying versions below.
        UserManager manager = mock(UserManager.class);
        when(manager.getUsers(any(), any(), any(), anyInt(), anyInt())).thenReturn(users(2));

        withUserManager(manager, () -> {
            assertNull(new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH).getPrevLink(),
                    "Page 0 of the plain directory has nothing before it");
            assertEquals(BASE_URL + "?page=0",
                    new UsersPager(urlStrategy, weblogger, BASE_URL, "en", -1, 1, LENGTH).getPrevLink(),
                    "Page 1 of the plain directory steps back to page 0 with no letter");
        });
    }

    @Test
    void letterListingQueriesByItsFirstCharacter() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsersByLetter(anyChar(), anyInt(), anyInt())).thenReturn(users(2));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "a", "en", -1, 0, LENGTH);

            assertEquals(2, pager.getItems().size());
        });

        verify(manager).getUsersByLetter(eq('a'), eq(0), eq(LENGTH + 1));
    }

    @Test
    void letterListingCarriesTheLetterOnItsNextLink() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsersByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(users(LENGTH + 1));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "a", "en", -1, 0, LENGTH);

            String next = pager.getNextLink();
            assertNotNull(next, "There is another page of A users");
            assertTrue(next.contains("page=1"), "Next must advance the page; got: " + next);
            assertTrue(next.contains("letter=a"),
                    "Dropping the letter would page out of the letter listing "
                            + "into the full directory; got: " + next);
        });
    }

    @Test
    void letterListingOffersNoNextLinkOnItsLastPage() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsersByLetter(anyChar(), anyInt(), anyInt())).thenReturn(users(LENGTH));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "a", "en", -1, 0, LENGTH);

            assertNull(pager.getNextLink(),
                    "The letter listing must stop offering pages when the letter runs out");
        });
    }

    @Test
    void letterListingOffersNoPreviousLinkOnItsFirstPage() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsersByLetter(anyChar(), anyInt(), anyInt())).thenReturn(users(2));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "a", "en", -1, 0, LENGTH);

            assertNull(pager.getPrevLink(),
                    "Page 0 has nothing before it; the guard is page-1 >= 0");
        });
    }

    @Test
    void letterListingPreviousLinkAppearsFromPageOneAndKeepsTheLetter() throws Exception {
        UserManager manager = mock(UserManager.class);
        when(manager.getUsersByLetter(anyChar(), anyInt(), anyInt())).thenReturn(users(2));

        withUserManager(manager, () -> {
            UsersPager pager = new UsersPager(urlStrategy, weblogger, BASE_URL, "a", "en", -1, 1, LENGTH);

            String prev = pager.getPrevLink();
            assertNotNull(prev, "Page 1 can go back to page 0");
            assertTrue(prev.contains("page=0"), "Previous must step back one page; got: " + prev);
            assertTrue(prev.contains("letter=a"), "Previous must keep the letter; got: " + prev);
        });
    }
}
