package org.apache.roller.weblogger.ui.controllers;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterisation test: pins behaviour that already existed in
 * {@code BaseController}'s four {@code lookupX} methods before Task 7 moved
 * their bodies into {@link WeblogOwnership}. It is not a specification of new
 * behaviour -- the permission interceptor vouches for the ACTION weblog only,
 * so every by-id lookup is a global lookup, and without this check any editor
 * could read or rewrite any other weblog's rows by guessing an id.
 */
class WeblogOwnershipTest {

    private User user;
    private Weblog mine;
    private Weblog theirs;
    private WeblogEntry entry;
    private Weblogger weblogger;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        weblogger = TestUtils.weblogger();
        user = TestUtils.setupUser("ownershiptestuser");
        mine = TestUtils.setupWeblog("ownershipmine", user);
        theirs = TestUtils.setupWeblog("ownershiptheirs", user);
        entry = TestUtils.setupWeblogEntry("ownership-entry", mine, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(mine.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anEntryOfTheActionWeblogIsFound() {
        assertNotNull(WeblogOwnership.entry(weblogger, entry.getId(), mine));
    }

    @Test
    void anEntryOfAnotherWeblogIsNotFound() {
        assertNull(WeblogOwnership.entry(weblogger, entry.getId(), theirs),
                "a foreign id must read as absent, not as someone else's entry");
    }

    @Test
    void aBlankIdIsAbsentRatherThanSomethingToLookUp() {
        assertNull(WeblogOwnership.entry(weblogger, null, mine));
        assertNull(WeblogOwnership.entry(weblogger, "", mine));
        assertNull(WeblogOwnership.entry(weblogger, "   ", mine));
    }

    @Test
    void anUnknownIdIsAbsent() {
        assertNull(WeblogOwnership.entry(weblogger, "no-such-id", mine));
    }

    // The redirect member of the family follows the same contract as the
    // other four: found for the owner, absent for everyone and everything
    // else -- a foreign id, a blank id, an unknown id, and a store failure
    // all read identically.

    @Test
    void aRedirectOfTheActionWeblogIsFound() throws Exception {
        org.apache.roller.weblogger.pojos.WeblogRedirect rule =
                new org.apache.roller.weblogger.pojos.WeblogRedirect();
        rule.setWeblog(TestUtils.getManagedWebsite(mine));
        rule.setSourcePath("/ownership-old");
        rule.setTargetPath("/ownership-new");
        weblogger.getWeblogRedirectManager().saveRedirect(rule);
        weblogger.flush();
        TestUtils.endSession(true);

        assertNotNull(WeblogOwnership.redirect(weblogger, rule.getId(), mine));
        assertNull(WeblogOwnership.redirect(weblogger, rule.getId(), theirs),
                "a foreign id must read as absent, not as someone else's rule");
    }

    @Test
    void aBlankOrUnknownRedirectIdIsAbsent() {
        assertNull(WeblogOwnership.redirect(weblogger, null, mine));
        assertNull(WeblogOwnership.redirect(weblogger, "  ", mine));
        assertNull(WeblogOwnership.redirect(weblogger, "no-such-id", mine));
    }

    /** A store failure reads as absent -- 404, never a 500 leaking the error. */
    @Test
    void aRedirectStoreFailureReadsAsAbsent() throws Exception {
        org.apache.roller.weblogger.business.MockWeblogger mock =
                org.apache.roller.weblogger.business.MockWeblogger.create();
        org.mockito.Mockito.when(mock.weblogRedirectManager().getRedirect("boom"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("store down"));

        assertNull(WeblogOwnership.redirect(mock.weblogger(), "boom", mine));
    }

    /**
     * M7: an id is a request parameter, so a CR/LF in it could forge a line of
     * its own in roller.log. A failed lookup of any kind logs the weblog, not the
     * raw id.
     */
    @Test
    void aFailedLookupNeverLogsTheRawId() throws Exception {
        org.apache.roller.weblogger.business.MockWeblogger mock =
                org.apache.roller.weblogger.business.MockWeblogger.create();
        org.apache.roller.weblogger.WebloggerException down =
                new org.apache.roller.weblogger.WebloggerException("store down");
        String forged = "boom\r\n2026-10-12 ERROR FORGEDLINE";
        org.mockito.Mockito.when(mock.weblogEntryManager().getWeblogEntry(forged)).thenThrow(down);
        org.mockito.Mockito.when(mock.weblogEntryManager().getWeblogCategory(forged)).thenThrow(down);
        org.mockito.Mockito.when(mock.weblogManager().getTemplate(forged)).thenThrow(down);
        org.mockito.Mockito.when(mock.weblogRedirectManager().getRedirect(forged)).thenThrow(down);
        org.mockito.Mockito.when(mock.weblogPageManager().getPage(forged)).thenThrow(down);
        org.mockito.Mockito.when(mock.commentManager().getComment(forged)).thenThrow(down);

        java.util.List<String> lines;
        try (org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture capture =
                org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture.everyLogger()) {
            assertNull(WeblogOwnership.entry(mock.weblogger(), forged, mine));
            assertNull(WeblogOwnership.category(mock.weblogger(), forged, mine));
            assertNull(WeblogOwnership.template(mock.weblogger(), forged, mine));
            assertNull(WeblogOwnership.redirect(mock.weblogger(), forged, mine));
            assertNull(WeblogOwnership.page(mock.weblogger(), forged, mine));
            assertNull(WeblogOwnership.comment(mock.weblogger(), forged, mine));
            lines = capture.everything();
        }
        assertTrue(lines.stream().filter(l -> l.contains(mine.getHandle())).count() >= 6,
                "each failure is logged, with the weblog: " + lines);
        for (String line : lines) {
            org.junit.jupiter.api.Assertions.assertFalse(line.contains("FORGED"), "a raw id was logged: " + line);
        }
    }

    @Test
    void aMediaFileIsFoundOnlyThroughItsOwnWeblog() throws Exception {
        weblogger.getPropertiesManager().getProperties().get("uploads.enabled").setValue("true");
        org.apache.roller.weblogger.business.MediaFileManager mfm = weblogger.getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(mine);
        org.apache.roller.weblogger.pojos.MediaFile file = new org.apache.roller.weblogger.pojos.MediaFile();
        file.setName("own.jpg");
        file.setDirectory(mfm.getDefaultMediaFileDirectory(managed));
        file.setWeblog(managed);
        file.setContentType("image/jpeg");
        file.setInputStream(getClass().getResourceAsStream("/hawk.jpg"));
        mfm.createMediaFile(managed, file, new org.apache.roller.weblogger.util.RollerMessages());
        TestUtils.endSession(true);

        assertNotNull(WeblogOwnership.mediaFile(weblogger, file.getId(), TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, file.getId(), TestUtils.getManagedWebsite(theirs)));
        assertNull(WeblogOwnership.mediaFile(weblogger, "no-such-id", TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, " ", TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, null, TestUtils.getManagedWebsite(mine)));
    }
}
