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
package com.wikantik.pressroom.business;

import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormSubmissionManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("formsubuser");
        weblog = TestUtils.setupWeblog("formsubblog", user);
        otherWeblog = TestUtils.setupWeblog("otherformsubblog", user);
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

    private FormSubmission submit(Weblog target, String name, String email, String message)
            throws Exception {
        FormSubmission submission = new FormSubmission();
        submission.setWeblog(TestUtils.getManagedWebsite(target));
        submission.setName(name);
        submission.setEmail(email);
        submission.setSubject("a question");
        submission.setMessage(message);
        manager().save(submission);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return submission;
    }

    @Test
    void aSavedSubmissionComesBackWithAllFieldsAndAStampedCreated() throws Exception {
        FormSubmission saved = submit(weblog, "Alice", "alice@example.com", "Hello there");

        FormSubmission fetched = manager().get(saved.getId());

        assertNotNull(fetched, "the submission must be retrievable by id");
        assertEquals("Alice", fetched.getName());
        assertEquals("alice@example.com", fetched.getEmail());
        assertEquals("a question", fetched.getSubject());
        assertEquals("Hello there", fetched.getMessage());
        assertNotNull(fetched.getCreated(), "created must be stamped when not set by the caller");
    }

    @Test
    void listingIsScopedToItsWeblog() throws Exception {
        submit(weblog, "Alice", "alice@example.com", "for this weblog");

        assertTrue(manager().getSubmissions(TestUtils.getManagedWebsite(otherWeblog), 0, 10).isEmpty(),
                "another weblog's submissions must not answer this weblog's query");
    }

    @Test
    void newestSubmissionsComeFirstAndOffsetAndMaxAreHonoured() throws Exception {
        submit(weblog, "Alice", "alice@example.com", "first");
        Thread.sleep(5);
        submit(weblog, "Bob", "bob@example.com", "second");
        Thread.sleep(5);
        submit(weblog, "Carol", "carol@example.com", "third");

        List<FormSubmission> firstPage = manager().getSubmissions(TestUtils.getManagedWebsite(weblog), 0, 1);
        assertEquals(1, firstPage.size(), "max must cap the result");
        assertEquals("Carol", firstPage.get(0).getName(), "newest first");

        List<FormSubmission> secondPage = manager().getSubmissions(TestUtils.getManagedWebsite(weblog), 1, 1);
        assertEquals(1, secondPage.size());
        assertEquals("Bob", secondPage.get(0).getName(), "offset must skip the newer row");
    }

    @Test
    void getCountMatchesTheNumberOfSubmissions() throws Exception {
        submit(weblog, "Alice", "alice@example.com", "first");
        submit(weblog, "Bob", "bob@example.com", "second");

        assertEquals(2, manager().getCount(TestUtils.getManagedWebsite(weblog)));
        assertEquals(0, manager().getCount(TestUtils.getManagedWebsite(otherWeblog)));
    }

    @Test
    void removeDeletesOnlyTheNamedRow() throws Exception {
        FormSubmission first = submit(weblog, "Alice", "alice@example.com", "first");
        submit(weblog, "Bob", "bob@example.com", "second");

        manager().remove(manager().get(first.getId()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        List<FormSubmission> remaining = manager().getSubmissions(TestUtils.getManagedWebsite(weblog), 0, 10);
        assertEquals(1, remaining.size());
        assertEquals("Bob", remaining.get(0).getName());
    }

    @Test
    void removingAWeblogsSubmissionsLeavesAnothersAlone() throws Exception {
        submit(weblog, "Alice", "alice@example.com", "mine");
        submit(otherWeblog, "Bob", "bob@example.com", "theirs");

        manager().removeSubmissions(TestUtils.getManagedWebsite(weblog));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getSubmissions(TestUtils.getManagedWebsite(weblog), 0, 10).isEmpty());
        assertEquals(1, manager().getSubmissions(TestUtils.getManagedWebsite(otherWeblog), 0, 10).size());
    }

    @Test
    void anOverlongMessageIsRefusedAtTheManagerToo() {
        FormSubmission s = new FormSubmission();
        s.setWeblog(weblog);
        s.setName("n");
        s.setEmail("e@example.com");
        s.setMessage("x".repeat(FormSubmissionManager.MAX_MESSAGE + 1));
        assertThrows(WebloggerException.class,
                () -> manager().save(s),
                "length caps must hold even if a future caller forgets them");
    }
}
