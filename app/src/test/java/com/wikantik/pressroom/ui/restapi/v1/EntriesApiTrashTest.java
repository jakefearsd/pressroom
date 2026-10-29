package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Trash is a fifth PubStatus, not a deleted_at column, so every status-naming
 * query excludes it by construction. These tests pin the two invariants the
 * API must not break: a restore never republishes, and a trashed entry leaves
 * the search index.
 */
class EntriesApiTrashTest {

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apitrashuser");
        weblog = TestUtils.setupWeblog("apitrashblog", user);
        entry = TestUtils.setupWeblogEntry("api-trash-entry", weblog, user);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void trashingHidesTheEntryFromAnOrdinaryListing() throws Exception {
        WeblogEntryManager wem = TestUtils.weblogger().getWeblogEntryManager();
        wem.trashWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(TestUtils.getManagedWebsite(weblog));
        assertTrue(wem.getWeblogEntries(criteria).isEmpty(),
                "includeTrashed defaults false -- a caller that thinks about nothing is safe");
    }

    /**
     * An undelete that silently republishes to feeds, the sitemap and every
     * subscriber is worse than one extra click. No column remembers the
     * pre-trash status precisely so this cannot regress.
     */
    @Test
    void restoreAlwaysLandsOnDraftEvenForAPreviouslyPublishedEntry() throws Exception {
        WeblogEntryManager wem = TestUtils.weblogger().getWeblogEntryManager();
        wem.trashWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        wem.restoreWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        assertEquals(WeblogEntry.PubStatus.DRAFT,
                wem.getWeblogEntry(entry.getId()).getStatus());
    }
}
