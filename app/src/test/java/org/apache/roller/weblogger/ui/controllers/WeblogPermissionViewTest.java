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
package org.apache.roller.weblogger.ui.controllers;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WeblogPermissionView} is what the admin JSPs iterate instead of raw
 * {@link WeblogPermission}s: the permission used to resolve its weblog and
 * user by name through the static service locator from inside getters
 * ({@code ${perms.weblog.name}}, {@code ${perm.user.id}}); the controller now
 * resolves them once, through the facade it holds, and hands the JSP a row.
 */
class WeblogPermissionViewTest {

    private Weblogger weblogger;
    private WeblogManager weblogs;
    private UserManager users;
    private WeblogEntryManager entries;
    private org.apache.roller.weblogger.business.CommentManager commentManager;
    private Weblog weblog;
    private User alice;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        weblogs = mock(WeblogManager.class);
        users = mock(UserManager.class);
        entries = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        commentManager = mock(org.apache.roller.weblogger.business.CommentManager.class);
        when(weblogger.getCommentManager()).thenReturn(commentManager);

        weblog = new Weblog();
        weblog.setHandle("testblog");
        alice = new User();
        alice.setUserName("alice");
        when(weblogs.getWeblogByHandle("testblog", null)).thenReturn(weblog);
        when(users.getUserByUserName("alice")).thenReturn(alice);
    }

    @Test
    void resolvesTheWeblogByHandleAndTheUserByNameThroughTheFacade() throws Exception {
        WeblogPermission perm = new WeblogPermission(weblog, alice, List.of(WeblogPermission.POST));

        WeblogPermissionView row = WeblogPermissionView.of(perm, weblogger);

        assertSame(perm, row.getPermission());
        assertSame(weblog, row.getWeblog());
        assertSame(alice, row.getUser());
        assertTrue(row.hasAction(WeblogPermission.POST));
        assertFalse(row.hasAction(WeblogPermission.ADMIN));
    }

    @Test
    void anUnassignedPermissionResolvesToNothingWithoutAskingTheTier() throws Exception {
        // A permission with no weblog handle and no user name -- the shape the
        // old getters answered with null for -- must not even ask the managers.
        WeblogPermission perm = new WeblogPermission();

        WeblogPermissionView row = WeblogPermissionView.of(perm, weblogger);

        assertNull(row.getWeblog());
        assertNull(row.getUser());
        verifyNoInteractions(weblogs, users);
    }

    @Test
    void theEntryCountIsAskedOfTheEntryManagerOnceAndRemembered() throws Exception {
        when(entries.getEntryCount(weblog)).thenReturn(7L);
        WeblogPermissionView row =
                WeblogPermissionView.of(new WeblogPermission(weblog, alice, List.of("post")), weblogger);

        assertEquals(7L, row.getEntryCount());
        assertEquals(7L, row.getEntryCount());
        verify(entries, times(1)).getEntryCount(weblog);
    }

    @Test
    void thePendingCommentCountIsAskedOnceAndRemembered() throws Exception {
        when(commentManager.countComments(weblog,
                org.apache.roller.weblogger.pojos.ReaderComment.Status.PENDING)).thenReturn(4L);
        WeblogPermissionView row =
                WeblogPermissionView.of(new WeblogPermission(weblog, alice, List.of("post")), weblogger);

        assertEquals(4L, row.getPendingCommentCount());
        assertEquals(4L, row.getPendingCommentCount());
        verify(commentManager, times(1)).countComments(weblog,
                org.apache.roller.weblogger.pojos.ReaderComment.Status.PENDING);
    }

    @Test
    void aPendingCountThatCannotBeReadIsZero() throws Exception {
        when(commentManager.countComments(any(), any())).thenThrow(new WebloggerException("down"));
        WeblogPermissionView row =
                WeblogPermissionView.of(new WeblogPermission(weblog, alice, List.of("post")), weblogger);

        assertEquals(0L, row.getPendingCommentCount());
    }

    /**
     * I1: moderation needs the weblog POST permission, and a weblog's creator holds
     * "admin" alone (the Members page grants single-action rows too), so the row
     * answers by implication, as the interceptor's check does, never by looking for
     * the literal "post" action in the row.
     */
    @Test
    void canModerateFollowsTheImpliedPostPermissionNotTheLiteralAction() throws Exception {
        assertTrue(rowWith(WeblogPermission.ADMIN).isCanModerate(), "an admin-only row is a creator's row");
        assertTrue(rowWith(WeblogPermission.POST).isCanModerate());
        assertTrue(rowWith(WeblogPermission.ADMIN, WeblogPermission.POST, WeblogPermission.EDIT_DRAFT)
                .isCanModerate());
        assertFalse(rowWith(WeblogPermission.EDIT_DRAFT).isCanModerate(), "a limited member cannot moderate");
        assertFalse(WeblogPermissionView.of(new WeblogPermission(), weblogger).isCanModerate(),
                "a row granting nothing implies nothing");
    }

    /**
     * The badge's gate is the row's answer. A JSP cannot be rendered in a unit test,
     * so this pins only that the page asks the row (the behaviour is above, and
     * CommentsIT sees the badge for a weblog's creator).
     */
    @Test
    void mainMenuGatesThePendingBadgeOnTheRowsModerationAnswer() throws Exception {
        java.nio.file.Path jsp = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("src/main/webapp/WEB-INF/jsps/core/MainMenu.jsp");
        String source = java.nio.file.Files.readString(jsp);

        assertTrue(source.contains("perms.canModerate and perms.pendingCommentCount > 0"),
                "the badge shows only to a member who may moderate, and only above 0");
        assertTrue(source.contains("mainMenu.pendingComments") && source.contains("comments.rol"),
                "the badge names the count and links to the moderation page");
        assertFalse(source.contains("hasAction(\"post\") and perms.pendingCommentCount"),
                "the literal post action misses a creator's admin-only row");
    }

    private WeblogPermissionView rowWith(String... actions) throws Exception {
        return WeblogPermissionView.of(new WeblogPermission(weblog, alice, List.of(actions)), weblogger);
    }

    @Test
    void anEntryCountThatCannotBeReadIsZeroRatherThanAFailedPage() throws Exception {
        when(entries.getEntryCount(any())).thenThrow(new WebloggerException("database down"));
        WeblogPermissionView row =
                WeblogPermissionView.of(new WeblogPermission(weblog, alice, List.of("post")), weblogger);

        assertEquals(0L, row.getEntryCount());
    }

    /**
     * The JSPs iterate rows named {@code perms}/{@code perm}, so every property
     * they read must be on the row or on the raw weblog/user it carries. The
     * entry count is the one that moved: {@code Weblog.getEntryCount()} is gone
     * (it queried the entry manager from inside the entity), and a JSP still
     * reading {@code perms.weblog.entryCount} fails only at render time -- no
     * unit test renders a JSP and {@code jspc-validate} does not resolve EL
     * properties. Pinned here as a source scan, the way the editor JSP
     * hygiene tests pin their pages.
     */
    @Test
    void theMainMenuReadsTheEntryCountFromTheRowNotTheEntity() throws Exception {
        java.nio.file.Path jsp = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("src/main/webapp/WEB-INF/jsps/core/MainMenu.jsp");
        String source = java.nio.file.Files.readString(jsp);

        assertTrue(source.contains("${perms.entryCount}"),
                "MainMenu.jsp must read the entry count from the WeblogPermissionView row");
        assertFalse(source.contains("perms.weblog.entryCount"),
                "Weblog.getEntryCount() no longer exists; ${perms.weblog.entryCount} would fail at render");
    }

    @Test
    void resolveMapsEveryPermissionInOrder() throws Exception {
        WeblogPermission first = new WeblogPermission(weblog, alice, List.of("post"));
        WeblogPermission second = new WeblogPermission();

        List<WeblogPermissionView> rows = WeblogPermissionView.resolve(List.of(first, second), weblogger);

        assertEquals(2, rows.size());
        assertSame(first, rows.get(0).getPermission());
        assertSame(second, rows.get(1).getPermission());
    }
}
