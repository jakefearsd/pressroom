package com.wikantik.pressroom.business.jpa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.wikantik.pressroom.business.jpa.TagAggregateRows.connection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WeblogEntryManager.lockEntryText}, against the real PostgreSQL: the
 * one guard an API write that names the text it read
 * ({@code expectedContentHash}, {@code edits}) has against a concurrent save
 * of the same entry. Each side runs in its own transaction, as two requests
 * do. Without the lock, two agents sending the same hash both passed the
 * unlocked check and the second save silently discarded the first.
 */
class EntryRowLockTest {

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("rowlock");
        weblog = TestUtils.setupWeblog("rowlockblog", user);
        WeblogEntry e = new WeblogEntry();
        e.setWebsite(TestUtils.weblogger().getWeblogManager().getWeblog(weblog.getId()));
        e.setTitle("Locked");
        e.setText("as read");
        e.setCreatorUserName(user.getUserName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        e.setPubTime(now);
        e.setUpdateTime(now);
        e.setStatus(PubStatus.DRAFT);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(e);
        TestUtils.endSession(true);
        entry = e;
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * A save of the entry in flight (its row written, not yet committed) makes
     * the lock wait, and the text returned is what that save committed, not
     * the text this transaction read before it.
     */
    @Test
    @Timeout(60)
    void theLockWaitsForAnUncommittedSaveAndReturnsWhatItCommitted() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = connection()) {
            other.setAutoCommit(false);
            try (PreparedStatement write = other.prepareStatement("UPDATE weblogentry SET text = ? WHERE id = ?")) {
                write.setString(1, "theirs");
                write.setString(2, entry.getId());
                write.executeUpdate();
            }
            Future<String> locked = pool.submit(() -> inATransaction(() -> lock(entry)));

            assertTrue(awaitASessionWaitingOnALock(Duration.ofSeconds(10)), "the lock waits for the other save");
            assertFalse(locked.isDone());
            other.commit();

            assertEquals("theirs", locked.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Held until this transaction commits: another writer of the row waits for it. */
    @Test
    @Timeout(60)
    void theLockIsHeldUntilCommit() throws Exception {
        try {
            Weblogger tier = TestUtils.weblogger();
            assertEquals("as read", lock(entry));
            try (Connection other = connection(); Statement st = other.createStatement()) {
                st.execute("SET lock_timeout = '1s'");
                SQLException refused = assertThrows(SQLException.class,
                        () -> st.executeUpdate("UPDATE weblogentry SET text = 'theirs' WHERE id = '"
                                + entry.getId() + "'"));
                assertEquals("55P03", refused.getSQLState(), refused.getMessage());
                tier.flush();
                assertEquals(1, st.executeUpdate("UPDATE weblogentry SET text = 'theirs' WHERE id = '"
                        + entry.getId() + "'"), "released by the commit");
            }
        } finally {
            TestUtils.endSession(false);
        }
    }

    @Test
    void anEntryWithNoRowIsNull() throws Exception {
        WeblogEntry gone = new WeblogEntry();
        gone.setId("no-such-entry");

        assertNull(lock(gone));
        TestUtils.endSession(false);
    }

    private static String lock(WeblogEntry e) throws Exception {
        return TestUtils.weblogger().getWeblogEntryManager().lockEntryText(e);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> T inATransaction(Work<T> work) throws Exception {
        Weblogger tier = TestUtils.weblogger();
        try {
            T result = work.run();
            tier.flush();
            return result;
        } finally {
            tier.release();
        }
    }

    private static boolean awaitASessionWaitingOnALock(Duration atMost) throws Exception {
        long deadline = System.nanoTime() + atMost.toNanos();
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(20);
            }
        }
        return false;
    }
}
