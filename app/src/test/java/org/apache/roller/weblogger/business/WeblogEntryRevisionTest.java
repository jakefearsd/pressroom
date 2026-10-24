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

package org.apache.roller.weblogger.business;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import org.apache.roller.testing.RollerPostgresContainer;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.jpa.JPAWeblogEntryManagerImpl;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entry revisions against the real database.
 *
 * <p>What has to hold: a revision records the content a save DISPLACED (not
 * the content it wrote), a save that changes nothing records nothing, the
 * retention property is honoured in both directions, and neither deleting an
 * entry nor deleting a whole weblog trips over the foreign key.
 */
public class WeblogEntryRevisionTest {

    private static final String RETENTION = "entry.revisions.retention";

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("revisionTestUser");
        testWeblog = TestUtils.setupWeblog("revisionTestWeblog", testUser);
        TestUtils.endSession(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        setRetention("-1");
        if (testWeblog != null) {
            TestUtils.teardownWeblog(testWeblog.getId());
        }
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    public void aRevisionNamesTheUserWhoseSaveReplacedIt() throws Exception {
        String id = saveEntry("Title", "Body 1").getId();
        TestUtils.endSession(true);
        User other = TestUtils.setupUser("revisionOtherUser");
        try {
            TestUtils.endSession(true);
            WeblogEntry entry = entryManager().getWeblogEntry(id);
            entry.setText("Body 2");
            entryManager().saveWeblogEntry(entry, TestUtils.getManagedUser(other));
            TestUtils.endSession(true);

            List<WeblogEntryRevision> revisions = entryManager()
                    .getRevisions(entryManager().getWeblogEntry(id));
            assertEquals(1, revisions.size());
            assertEquals(other.getUserName(), revisions.get(0).getCreator());
        } finally {
            TestUtils.teardownWeblog(testWeblog.getId());
            testWeblog = null;
            TestUtils.teardownUser(other.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    public void aSaveWithNoNamedEditorRecordsTheEntrysCreator() throws Exception {
        String id = saveEntry("Title", "Body 1").getId();
        TestUtils.endSession(true);

        editText(id, "Body 2");

        assertEquals(testUser.getUserName(), entryManager()
                .getRevisions(entryManager().getWeblogEntry(id)).get(0).getCreator());
    }

    @Test
    public void aSaveRecordsTheContentItReplaced() throws Exception {
        String id = saveEntry("First title", "First body").getId();
        TestUtils.endSession(true);

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setTitle("Second title");
        entry.setText("Second body");
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(1, revisions.size());

        // The revision holds what was displaced, not what was written -- the
        // distinction that makes "restore" mean anything.
        assertEquals("First title", revisions.get(0).getTitle());
        assertEquals("First body", revisions.get(0).getText());
        assertNotNull(revisions.get(0).getCreated());
    }

    @Test
    public void threeContentChangingSavesLeaveTwoRevisions() throws Exception {
        String id = saveEntry("V1", "Body 1").getId();
        TestUtils.endSession(true);

        editText(id, "Body 2");
        editText(id, "Body 3");

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(2, revisions.size());
        // Newest first: the most recent save displaced "Body 2".
        assertEquals("Body 2", revisions.get(0).getText());
        assertEquals("Body 1", revisions.get(1).getText());
    }

    /**
     * The safeguard that matters most now that nothing is pruned by default:
     * re-saving to change only the publication date must not deposit a row
     * identical to the one before it.
     */
    @Test
    public void aSaveThatChangesNoContentRecordsNothing() throws Exception {
        String id = saveEntry("Unchanged", "Body").getId();
        TestUtils.endSession(true);

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setPubTime(new Timestamp(System.currentTimeMillis() - 86_400_000L));
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        assertTrue(entryManager().getRevisions(entryManager().getWeblogEntry(id)).isEmpty(),
                "changing only the publication date must not record a revision");
    }

    /** The shipped default: -1 keeps everything. */
    @Test
    public void theDefaultRetentionKeepsEveryRevision() throws Exception {
        setRetention("-1");
        String id = saveEntry("Kept", "Body 0").getId();
        TestUtils.endSession(true);

        for (int i = 1; i <= 25; i++) {
            editText(id, "Body " + i);
        }

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(25, revisions.size(),
                "the default must not prune; twenty-five edits leave twenty-five revisions");
    }

    /**
     * Under the default the prune loop started at index retention-1 = -2 of
     * an empty list, so every content-changing save threw inside
     * recordRevision and logged a WARN with a stack trace. The revision still
     * landed (it is stored before the loop), which is why the test above
     * never noticed; only the log shows it.
     */
    @Test
    public void theDefaultRetentionRecordsWithoutAWarning() throws Exception {
        setRetention("-1");
        String id = saveEntry("Quiet", "Body 0").getId();
        TestUtils.endSession(true);

        List<LogEvent> warnings = new ArrayList<>();
        Appender appender = captureWarnings(warnings);
        try {
            editText(id, "Body 1");
        } finally {
            stopCapturing(appender);
        }

        assertEquals(1, entryManager().getRevisions(entryManager().getWeblogEntry(id)).size());
        assertTrue(warnings.isEmpty(), "a save under the default retention logged: " + messages(warnings));
    }

    @Test
    public void aPositiveRetentionKeepsTheNewestAndDropsTheOldest() throws Exception {
        setRetention("3");
        String id = saveEntry("Capped", "Body 0").getId();
        TestUtils.endSession(true);

        for (int i = 1; i <= 6; i++) {
            editText(id, "Body " + i);
        }

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(3, revisions.size());
        assertEquals("Body 5", revisions.get(0).getText());
        assertEquals("Body 4", revisions.get(1).getText());
        assertEquals("Body 3", revisions.get(2).getText());
    }

    @Test
    public void aRetentionOfZeroRecordsNothingAtAll() throws Exception {
        setRetention("0");
        String id = saveEntry("Unrecorded", "Body 0").getId();
        TestUtils.endSession(true);

        editText(id, "Body 1");
        editText(id, "Body 2");

        assertTrue(entryManager().getRevisions(entryManager().getWeblogEntry(id)).isEmpty());
    }

    /**
     * The promise in {@code recordRevision}'s javadoc, held against a real
     * database refusal: a revision row PostgreSQL rejects (here a CHECK
     * constraint, SQLSTATE 23514) must cost the author nothing. The save
     * commits, the refused revision is simply not there, and the WARN says
     * so. Before the fix the revision INSERT ran inside the save's commit,
     * so the refusal rolled back the save with it.
     */
    @Test
    public void aRevisionTheDatabaseRefusesDoesNotCostTheSave() throws Exception {
        String refused = "Refused revision " + System.nanoTime();
        String id = saveEntry(refused, "Body 0").getId();
        TestUtils.endSession(true);

        List<LogEvent> warnings = new ArrayList<>();
        Appender appender = captureWarnings(warnings);
        sql("ALTER TABLE weblogentry_revision ADD CONSTRAINT " + REFUSING_CHECK
                + " CHECK (title IS DISTINCT FROM '" + refused + "') NOT VALID");
        try {
            WeblogEntry entry = entryManager().getWeblogEntry(id);
            entry.setTitle("Accepted title");
            entryManager().saveWeblogEntry(entry);
            TestUtils.endSession(true);
        } finally {
            sql("ALTER TABLE weblogentry_revision DROP CONSTRAINT IF EXISTS " + REFUSING_CHECK);
            stopCapturing(appender);
            TestUtils.weblogger().release();
        }

        WeblogEntry saved = entryManager().getWeblogEntry(id);
        assertEquals("Accepted title", saved.getTitle(), "the save itself must have committed");
        assertTrue(entryManager().getRevisions(saved).isEmpty(), "the refused revision is not stored");
        assertEquals(1, warnings.size(), "one WARN for the lost revision: " + messages(warnings));

        // and revisions work again once the database accepts them
        editText(id, "Body 1");
        assertEquals(1, entryManager().getRevisions(entryManager().getWeblogEntry(id)).size());
    }

    /** A save that never commits leaves no revision of what it would have replaced. */
    @Test
    public void aSaveThatRollsBackRecordsNoRevision() throws Exception {
        String id = saveEntry("Rolled back", "Body 0").getId();
        TestUtils.endSession(true);

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setText("Body never committed");
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(false);

        WeblogEntry reloaded = entryManager().getWeblogEntry(id);
        assertEquals("Body 0", reloaded.getText());
        assertTrue(entryManager().getRevisions(reloaded).isEmpty());
    }

    /**
     * An entry edited and then deleted in one transaction: the revision has
     * nothing left to hang off, so none is written, and that is not worth a
     * WARN either.
     */
    @Test
    public void anEntryEditedThenDeletedInOneTransactionLeavesNoRevisionAndNoWarning() throws Exception {
        String id = saveEntry("Edited then deleted", "Body 0").getId();
        TestUtils.endSession(true);

        List<LogEvent> warnings = new ArrayList<>();
        Appender appender = captureWarnings(warnings);
        try {
            WeblogEntry entry = entryManager().getWeblogEntry(id);
            entry.setText("Body 1");
            entryManager().saveWeblogEntry(entry);
            entryManager().removeWeblogEntry(entry);
            TestUtils.endSession(true);
        } finally {
            stopCapturing(appender);
        }

        assertTrue(entryManager().getWeblogEntry(id) == null);
        assertEquals(0, revisionRows(id));
        assertTrue(warnings.isEmpty(), "logged: " + messages(warnings));
    }

    /**
     * A revision's time is when the save that displaced the content
     * happened, not when the entry was loaded: the pruner keeps the newest
     * by this column, and the rail shows it. A session that loads first and
     * saves last must leave the newest revision, even though its snapshot is
     * the oldest.
     */
    @Test
    public void revisionsAreOrderedBySaveTimeNotLoadTime() throws Exception {
        String id = saveEntry("Raced", "Body 0").getId();
        TestUtils.endSession(true);

        // session A loads "Body 0" on its own thread and keeps it open
        ExecutorService sessionA = Executors.newSingleThreadExecutor();
        try {
            WeblogEntry loadedByA = sessionA.submit(() -> entryManager().getWeblogEntry(id))
                    .get(30, TimeUnit.SECONDS);
            Thread.sleep(5);

            // meanwhile two saves on this thread: revisions "Body 0", then "Body 1"
            editText(id, "Body 1");
            Thread.sleep(5);
            editText(id, "Body 2");
            Thread.sleep(5);

            // A saves last, displacing the "Body 0" it loaded
            sessionA.submit(() -> {
                try {
                    loadedByA.setText("Body 3");
                    entryManager().saveWeblogEntry(loadedByA);
                    TestUtils.weblogger().flush();
                } finally {
                    TestUtils.weblogger().release();
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
        } finally {
            sessionA.shutdownNow();
        }

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(List.of("Body 0", "Body 1", "Body 0"),
                revisions.stream().map(WeblogEntryRevision::getText).toList(),
                "newest first: A's save came last, so its revision is the newest");
    }

    /**
     * Revisions hold a foreign key to the entry, so deleting an entry that has
     * them must clear them first or the delete simply fails.
     */
    @Test
    public void deletingAnEntryTakesItsRevisionsWithIt() throws Exception {
        String id = saveEntry("Doomed", "Body 0").getId();
        TestUtils.endSession(true);
        editText(id, "Body 1");

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        assertEquals(1, entryManager().getRevisions(entry).size());

        entryManager().removeWeblogEntry(entry);
        TestUtils.endSession(true);

        assertTrue(entryManager().getWeblogEntry(id) == null);
    }

    /** The same foreign key, reached the other way: through weblog deletion. */
    @Test
    public void deletingAWeblogWithRevisionsSucceeds() throws Exception {
        String id = saveEntry("Doomed with the blog", "Body 0").getId();
        TestUtils.endSession(true);
        editText(id, "Body 1");

        String weblogId = testWeblog.getId();
        TestUtils.teardownWeblog(weblogId);
        TestUtils.endSession(true);
        testWeblog = null;   // tearDown must not try again

        assertTrue(TestUtils.weblogger().getWeblogManager()
                .getWeblog(weblogId) == null);
    }

    // ------------------------------------------------------------- fixtures

    private static final String REFUSING_CHECK = "wer_test_refuses_title";

    private static Appender captureWarnings(List<LogEvent> into) {
        Appender appender = new AbstractAppender("WeblogEntryRevisionTest-warnings", null, null,
                false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.WARN)
                        && JPAWeblogEntryManagerImpl.class.getName().equals(event.getLoggerName())) {
                    into.add(event.toImmutable());
                }
            }
        };
        appender.start();
        LoggerContext.getContext(false).getConfiguration()
                .getLoggerConfig(JPAWeblogEntryManagerImpl.class.getName()).addAppender(appender, null, null);
        return appender;
    }

    private static void stopCapturing(Appender appender) {
        LoggerContext.getContext(false).getConfiguration()
                .getLoggerConfig(JPAWeblogEntryManagerImpl.class.getName())
                .removeAppender(appender.getName());
        appender.stop();
    }

    private static List<String> messages(List<LogEvent> events) {
        return events.stream().map(e -> e.getMessage().getFormattedMessage()).toList();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
    }

    private static void sql(String statement) throws SQLException {
        try (Connection con = connection(); Statement st = con.createStatement()) {
            st.execute(statement);
        }
    }

    private static int revisionRows(String entryId) throws SQLException {
        try (Connection con = connection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT count(*) FROM weblogentry_revision WHERE entryid = ?")) {
            ps.setString(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private WeblogEntryManager entryManager() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /** Loads the entry, changes its text, saves, and closes the session. */
    private void editText(String id, String newText) throws Exception {
        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setText(newText);
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    private void setRetention(String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        RuntimeConfigProperty property = pmgr.getProperties().get(RETENTION);
        assertNotNull(property, RETENTION + " is not a declared runtime property");
        property.setValue(value);
        pmgr.saveProperties(pmgr.getProperties());
        TestUtils.endSession(true);
    }

    private WeblogEntry saveEntry(String title, String text) throws Exception {
        Weblog managedWeblog = TestUtils.getManagedWebsite(testWeblog);

        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(title);
        entry.setText(text);
        entry.setAnchor("revision-" + System.nanoTime());
        entry.setWebsite(managedWeblog);
        entry.setCreatorUserName(TestUtils.getManagedUser(testUser).getUserName());
        entry.setCategory(TestUtils.categoryNamed(managedWeblog, "General"));
        entry.setStatus(PubStatus.DRAFT);
        entry.setUpdateTime(new Timestamp(System.currentTimeMillis()));
        entryManager().saveWeblogEntry(entry);
        return entry;
    }
}
