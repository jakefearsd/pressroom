package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a member without publish rights (a contributor: weblog EDIT_DRAFT, no
 * POST) may do to an entry, as one rule for the entry editor and the entries
 * API (spec 2026-11-21 owner decisions, Task 3).
 */
class ContributorRulesTest {

    private static WeblogEntry entry(String creator, PubStatus status) {
        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(creator);
        entry.setStatus(status);
        return entry;
    }

    private static User user(String name) {
        User user = new User();
        user.setUserName(name);
        return user;
    }

    @Test
    void aContributorMayChangeOnlyTheirOwnDraftsAndPendingEntries() {
        assertTrue(ContributorRules.mayChange(entry("dora", PubStatus.DRAFT), user("dora")));
        assertTrue(ContributorRules.mayChange(entry("dora", PubStatus.PENDING), user("dora")));
        assertFalse(ContributorRules.mayChange(entry("dora", PubStatus.PUBLISHED), user("dora")));
        assertFalse(ContributorRules.mayChange(entry("dora", PubStatus.SCHEDULED), user("dora")));
        assertFalse(ContributorRules.mayChange(entry("dora", PubStatus.TRASHED), user("dora")));
        assertFalse(ContributorRules.mayChange(entry("abe", PubStatus.DRAFT), user("dora")));
    }

    @Test
    void noUserOrNoNameOwnsNothing() {
        assertFalse(ContributorRules.mayChange(entry(null, PubStatus.DRAFT), user(null)));
        assertFalse(ContributorRules.mayChange(entry("dora", PubStatus.DRAFT), null));
    }

    @Test
    void publishingOrSchedulingIsSubmittedForReviewAndEverythingElseStands() {
        assertEquals(PubStatus.PENDING, ContributorRules.ceiling(PubStatus.PUBLISHED));
        assertEquals(PubStatus.PENDING, ContributorRules.ceiling(PubStatus.SCHEDULED));
        assertEquals(PubStatus.PENDING, ContributorRules.ceiling(PubStatus.PENDING));
        assertEquals(PubStatus.DRAFT, ContributorRules.ceiling(PubStatus.DRAFT));
        assertEquals(null, ContributorRules.ceiling(null));
    }

    @Test
    void mayPublishAsksForPostOnTheWeblog() throws Exception {
        UserManager users = mock(UserManager.class);
        Weblog weblog = new Weblog();
        weblog.setId("w1");
        when(users.checkPermission(any(), any())).thenAnswer(call ->
                ((WeblogPermission) call.getArgument(0)).getActionsAsList().equals(List.of(WeblogPermission.POST))
                        && "abe".equals(((User) call.getArgument(1)).getUserName()));

        assertTrue(ContributorRules.mayPublish(users, weblog, user("abe")));
        assertFalse(ContributorRules.mayPublish(users, weblog, user("dora")));
    }

    /** A check that cannot be answered is a no: the caller is held to the contributor's rules. */
    @Test
    void aPermissionCheckThatFailsIsANo() throws Exception {
        UserManager users = mock(UserManager.class);
        when(users.checkPermission(any(), any())).thenThrow(new WebloggerException("down"));

        assertFalse(ContributorRules.mayPublish(users, new Weblog(), user("abe")));
    }
}
