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
package com.wikantik.pressroom.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * {@link WeblogEntryManager#getBlogStanding}: the blog line's count and date.
 * It counts published posts whose time has come and nothing else, unlike
 * {@link WeblogEntryManager#getEntryCount(Weblog)}, which counts every row
 * with status PUBLISHED, a post due in the next minute included.
 */
class BlogStandingTest {

    private static final long DAY = 86_400_000L;

    private User user;
    private Weblog blog;
    private Weblog other;
    private final List<Weblog> weblogs = new ArrayList<>();
    private final long now = System.currentTimeMillis();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        String suffix = Long.toString(System.nanoTime(), 36);
        user = TestUtils.setupUser("standinguser" + suffix);
        blog = TestUtils.setupWeblog("standingblog" + suffix, user);
        other = TestUtils.setupWeblog("standingother" + suffix, user);
        weblogs.add(blog);
        weblogs.add(other);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Weblog weblog : weblogs) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private long post(Weblog weblog, String anchor, PubStatus status, long pubTime) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user), status);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(new Timestamp(pubTime));
        managed.setUpdateTime(new Timestamp(pubTime));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return pubTime;
    }

    private BlogStanding standing(Weblog weblog, long asOf) throws Exception {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getBlogStanding(TestUtils.getManagedWebsite(weblog), new Date(asOf));
    }

    @Test
    void countsPublishedPostsAndFindsTheNewestOnly() throws Exception {
        post(blog, "old", PubStatus.PUBLISHED, now - 30 * DAY);
        long newest = post(blog, "new", PubStatus.PUBLISHED, now - 2 * DAY);
        post(blog, "a-draft", PubStatus.DRAFT, now - DAY);
        post(blog, "pending", PubStatus.PENDING, now - DAY);
        post(blog, "trashed", PubStatus.TRASHED, now - DAY);
        post(blog, "scheduled", PubStatus.SCHEDULED, now + 10 * DAY);
        post(other, "elsewhere", PubStatus.PUBLISHED, now - DAY);

        BlogStanding standing = standing(blog, now);

        assertEquals(2, standing.getArticles());
        assertEquals(newest, standing.getLatest().getTime(),
                "the newest date is a published post's, not a draft's or a trashed one's");
    }

    /** Published but due in the next minute: the save keeps it PUBLISHED, and it is not yet live. */
    @Test
    void aPublishedPostNotYetDueIsNotCountedUntilItIs() throws Exception {
        long visible = post(blog, "visible", PubStatus.PUBLISHED, now - DAY);
        long due = post(blog, "due-soon", PubStatus.PUBLISHED, now + 30_000L);

        assertEquals(2, TestUtils.weblogger().getWeblogEntryManager()
                .getEntryCount(TestUtils.getManagedWebsite(blog)),
                "getEntryCount counts the not-yet-due row as well, which is why it is not used");
        BlogStanding before = standing(blog, now);
        assertEquals(1, before.getArticles());
        assertEquals(visible, before.getLatest().getTime());

        BlogStanding after = standing(blog, now + 60_000L);
        assertEquals(2, after.getArticles());
        assertEquals(due, after.getLatest().getTime());
    }

    @Test
    void aBlogWithNothingPublishedHasNoLatest() throws Exception {
        post(blog, "only-draft", PubStatus.DRAFT, now - DAY);

        BlogStanding standing = standing(blog, now);

        assertEquals(0, standing.getArticles());
        assertNull(standing.getLatest());
    }
}
