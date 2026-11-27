package com.wikantik.pressroom.business;

import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unread state on contact-form submissions: a shared {@code read_at}, set once, per weblog. */
class FormSubmissionReadStateTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("readstateuser");
        weblog = TestUtils.setupWeblog("readstateblog", user);
        otherWeblog = TestUtils.setupWeblog("otherreadstateblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static FormSubmissionManager manager() {
        return TestUtils.weblogger().getFormSubmissionManager();
    }

    private FormSubmission submit(Weblog target, String message) throws Exception {
        FormSubmission submission = new FormSubmission();
        submission.setWeblog(TestUtils.getManagedWebsite(target));
        submission.setName("Alice");
        submission.setEmail("alice@example.com");
        submission.setMessage(message);
        manager().save(submission);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return submission;
    }

    @Test
    void unreadCountCountsNullReadAt() throws Exception {
        FormSubmission first = submit(weblog, "one");
        submit(weblog, "two");
        submit(weblog, "three");
        assertEquals(3, manager().getUnreadCount(TestUtils.getManagedWebsite(weblog)));

        assertEquals(1, manager().markRead(TestUtils.getManagedWebsite(weblog), List.of(first.getId())));
        TestUtils.endSession(true);

        assertEquals(2, manager().getUnreadCount(TestUtils.getManagedWebsite(weblog)));
    }

    @Test
    void markReadIgnoresOtherWeblogsIds() throws Exception {
        FormSubmission foreign = submit(otherWeblog, "for B");

        int changed = manager().markRead(TestUtils.getManagedWebsite(weblog), List.of(foreign.getId()));
        TestUtils.endSession(true);

        assertEquals(0, changed);
        assertEquals(1, manager().getUnreadCount(TestUtils.getManagedWebsite(otherWeblog)));
        assertNull(manager().get(foreign.getId()).getReadAt());
    }

    @Test
    void markReadIsIdempotent() throws Exception {
        FormSubmission one = submit(weblog, "one");
        assertEquals(1, manager().markRead(TestUtils.getManagedWebsite(weblog), List.of(one.getId())));
        TestUtils.endSession(true);
        java.time.Instant first = manager().get(one.getId()).getReadAt();
        assertNotNull(first);
        Thread.sleep(5);

        assertEquals(0, manager().markRead(TestUtils.getManagedWebsite(weblog), List.of(one.getId())));
        TestUtils.endSession(true);

        assertEquals(first, manager().get(one.getId()).getReadAt());
    }

    @Test
    void markReadWithNoIdsChangesNothing() throws Exception {
        submit(weblog, "one");
        assertEquals(0, manager().markRead(TestUtils.getManagedWebsite(weblog), List.of()));
        assertEquals(1, manager().getUnreadCount(TestUtils.getManagedWebsite(weblog)));
    }
}
