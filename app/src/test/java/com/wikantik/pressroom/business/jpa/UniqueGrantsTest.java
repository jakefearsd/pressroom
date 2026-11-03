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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.wikantik.pressroom.business.jpa.TagAggregateRows.connection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A role, a weblog permission and a category name are each stored once, even
 * when the same grant or create arrives twice: a double-clicked form, a
 * retried API call, or two grants in one transaction.
 *
 * <p>The managers checked and then inserted, and the insert happened at
 * commit, so two overlapping requests both saw nothing and both stored a
 * row; every later lookup ({@code getSingleResult}) then failed on the pair
 * with {@code NonUniqueResultException}. V045 adds the unique indexes; these
 * tests pin that the second grant is "already granted", not a failed commit.
 *
 * <p>The overlap is made deterministic as in {@code EntryAnchorUniquenessTest}:
 * the first worker does its work and waits, uncommitted, until the second has
 * started its own (and, with the fix, is waiting on the first) before it
 * commits.
 */
class UniqueGrantsTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("grantrace");
        weblog = TestUtils.setupWeblog("grantraceblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    @Timeout(60)
    void overlappingGrantsOfOneRoleBothSucceedAndStoreItOnce() throws Exception {
        assertNoFailures(overlapping(() -> users().grantRole("tester", managedUser())));

        assertEquals(1, count("SELECT count(*) FROM userrole WHERE username = ? AND rolename = 'tester'"));
        assertTrue(users().hasRole("tester", managedUser()), "the lookup answers rather than throwing");
    }

    @Test
    @Timeout(60)
    void grantingOneRoleTwiceInOneTransactionStoresItOnce() throws Exception {
        assertNoFailures(inOneTransaction(() -> {
            users().grantRole("tester", managedUser());
            users().grantRole("tester", managedUser());
        }));

        assertEquals(1, count("SELECT count(*) FROM userrole WHERE username = ? AND rolename = 'tester'"));
    }

    /** A role revoked and granted again in one transaction is held afterwards. */
    @Test
    @Timeout(60)
    void aRoleRevokedThenGrantedInOneTransactionIsHeld() throws Exception {
        assertNoFailures(inOneTransaction(() -> {
            users().revokeRole("editor", managedUser());
            users().grantRole("editor", managedUser());
        }));

        assertEquals(1, count("SELECT count(*) FROM userrole WHERE username = ? AND rolename = 'editor'"));
    }

    @Test
    @Timeout(60)
    void overlappingGrantsOfOneWeblogPermissionStoreOneRowWithBothActions() throws Exception {
        User member = TestUtils.setupUser("grantracemember");
        try {
            List<Throwable> failures = overlapping(
                    () -> users().grantWeblogPermission(managedWeblog(), managed(member),
                            List.of(WeblogPermission.EDIT_DRAFT)),
                    () -> users().grantWeblogPermission(managedWeblog(), managed(member),
                            List.of(WeblogPermission.POST)));
            assertNoFailures(failures);

            assertEquals(1, count("SELECT count(*) FROM pressroom_permission WHERE username = ?",
                    member.getUserName()));
            WeblogPermission held = users().getWeblogPermission(managedWeblog(), managed(member));
            assertEquals(List.of(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST), held.getActionsAsList());
        } finally {
            TestUtils.teardownUser(member.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    @Timeout(60)
    void grantingOneWeblogPermissionTwiceInOneTransactionStoresOneRow() throws Exception {
        User member = TestUtils.setupUser("grantracetwice");
        try {
            assertNoFailures(inOneTransaction(() -> {
                users().grantWeblogPermission(managedWeblog(), managed(member), List.of(WeblogPermission.EDIT_DRAFT));
                users().grantWeblogPermission(managedWeblog(), managed(member), List.of(WeblogPermission.POST));
            }));

            assertEquals(1, count("SELECT count(*) FROM pressroom_permission WHERE username = ?",
                    member.getUserName()));
            WeblogPermission held = users().getWeblogPermission(managedWeblog(), managed(member));
            assertEquals(List.of(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST), held.getActionsAsList());
        } finally {
            TestUtils.teardownUser(member.getUserName());
            TestUtils.endSession(true);
        }
    }

    /**
     * Two overlapping creates of one category name: the second waits for the
     * first and is then refused as a duplicate at its save, which the
     * category screen and the API already answer -- not stored beside it.
     */
    @Test
    @Timeout(60)
    void overlappingCreatesOfOneCategoryNameStoreItOnce() throws Exception {
        List<Throwable> failures = overlapping(() -> TestUtils.weblogger().getWeblogEntryManager()
                .saveWeblogCategory(new WeblogCategory(managedWeblog(), "Twice", null, null)));

        assertEquals(1, count("SELECT count(*) FROM weblogcategory WHERE websiteid = ? AND name = 'Twice'",
                weblog.getId()));
        assertEquals(1, failures.size(), "the second create is refused: " + failures);
        assertTrue(failures.get(0).getMessage().contains("Duplicate category name"),
                "refused at the save, not by a failed commit: " + failures.get(0));
    }

    // ------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static UserManager users() {
        return TestUtils.weblogger().getUserManager();
    }

    private User managedUser() throws Exception {
        return managed(user);
    }

    private static User managed(User someone) throws Exception {
        return users().getUserByUserName(someone.getUserName(), null);
    }

    private Weblog managedWeblog() throws Exception {
        return TestUtils.weblogger().getWeblogManager().getWeblog(weblog.getId());
    }

    private int count(String sql) throws Exception {
        return count(sql, user.getUserName());
    }

    private static int count(String sql, String param) throws Exception {
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Throwable> inOneTransaction(Work work) throws Exception {
        return run(List.of(work));
    }

    private static List<Throwable> overlapping(Work both) throws Exception {
        return overlapping(both, both);
    }

    /**
     * {@code first} runs and holds its transaction open until {@code second}
     * has started and is waiting on a lock (or three seconds have passed),
     * then commits; {@code second} commits whenever it gets there.
     */
    private static List<Throwable> overlapping(Work first, Work second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        return run(List.of(
                () -> {
                    first.run();
                    firstDone.countDown();
                    secondStarted.await(30, TimeUnit.SECONDS);
                    awaitASessionWaitingOnALock(Duration.ofSeconds(3));
                },
                () -> {
                    firstDone.await(30, TimeUnit.SECONDS);
                    secondStarted.countDown();
                    second.run();
                }));
    }

    /** Each piece of work on its own thread, so in its own transaction, committed at its end. */
    private static List<Throwable> run(List<Work> works) throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(works.size());
        try {
            List<Future<?>> done = new ArrayList<>();
            for (Work work : works) {
                done.add(pool.submit(() -> {
                    Weblogger tier = TestUtils.weblogger();
                    try {
                        work.run();
                        tier.flush();
                    } catch (Exception | AssertionError e) {
                        failures.add(e);
                    } finally {
                        tier.release();
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    private static void awaitASessionWaitingOnALock(Duration atMost) throws Exception {
        long deadline = System.nanoTime() + atMost.toNanos();
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
    }

    private static void assertNoFailures(List<Throwable> failures) {
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " failed; first: " + failures.get(0), failures.get(0));
        }
    }
}
