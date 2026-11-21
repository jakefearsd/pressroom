package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.startup.MockMailProvider;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An entry the API leaves pending tells the reviewers, as the editor's save
 * does (B4). Mail is the observable: the provider records what would go out.
 */
class EntriesWriteApiPendingNoticeTest {

    private EntriesWriteApiFixture f;
    private MockMailProvider mail;

    @BeforeEach
    void setUp() throws Exception {
        mail = MockMailProvider.install();
        f = new EntriesWriteApiFixture();
        f.weblog.setLocale("en_US");
        f.weblog.setName("My Blog");
        User reviewer = new User();
        reviewer.setUserName("reviewer");
        reviewer.setEmailAddress("reviewer@example.invalid");
        User author = new User();
        author.setUserName("maiia");
        author.setEmailAddress("maiia@example.invalid");
        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogs.getWeblogUsers(f.weblog, true)).thenReturn(List.of(reviewer));
        UserManager users = mock(UserManager.class);
        when(users.getUserByUserName("maiia")).thenReturn(author);
        when(users.checkPermission(any(), eq(reviewer))).thenReturn(true);
        when(f.weblogger.getWeblogManager()).thenReturn(weblogs);
        when(f.weblogger.getUserManager()).thenReturn(users);
        when(f.entries.getWeblogCategoryByName(f.weblog, "Food"))
                .thenReturn(new com.wikantik.pressroom.pojos.WeblogCategory(f.weblog, "Food", null, null));
        when(f.urls.getEntryEditURL(any(), any(), eq(true))).thenReturn("https://x.invalid/e");
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
    }

    @Test
    void creatingAPendingEntryNotifiesReviewers() throws Exception {
        f.send(post("/v1/weblogs/myblog/entries")
                .content("{\"title\":\"t\",\"text\":\"x\",\"category\":\"Food\",\"status\":\"PENDING\"}"))
                .andExpect(status().isCreated());

        assertNotNull(mail.onlyMessage());
    }

    /**
     * maiia holds no POST here (the permission stub answers yes only for the
     * reviewer), so her publish is submitted for review by the contributor
     * ceiling (spec 2026-11-21 owner decisions, Task 3), and the reviewers are
     * told as for an explicit PENDING.
     */
    @Test
    void aContributorsPublishSubmittedForReviewNotifiesReviewers() throws Exception {
        f.mayPost = false;

        f.send(post("/v1/weblogs/myblog/entries")
                .content("{\"title\":\"t\",\"text\":\"x\",\"category\":\"Food\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated());

        assertNotNull(mail.onlyMessage());
    }

    @Test
    void updatingAnEntryToPendingNotifiesReviewers() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(f.weblog);
        entry.setCreatorUserName("maiia");
        entry.setTitle("t");
        entry.setText("x");
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(f.entries.getWeblogEntry("e1")).thenReturn(entry);

        f.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/v1/weblogs/myblog/entries/e1").content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isOk());

        assertNotNull(mail.onlyMessage());
    }
}
