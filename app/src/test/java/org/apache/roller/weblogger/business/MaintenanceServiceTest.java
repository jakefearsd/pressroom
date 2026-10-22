package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The three Maintenance actions move out of the controller so the API is
 * their second caller rather than their second implementation. This is a
 * characterisation test for moved behaviour, not new behaviour -- the bodies
 * are the same ones {@code MaintenanceControllerTest}/
 * {@code MaintenanceControllerRequestBindingTest} already pin, moved
 * verbatim behind a service seam.
 */
class MaintenanceServiceTest {

    private User user;
    private Weblog weblog;
    private MaintenanceService service;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("maintuser");
        weblog = TestUtils.setupWeblog("maintblog", user);
        TestUtils.endSession(true);
        service = new MaintenanceService(TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void flushCacheCompletesForARealWeblog() throws Exception {
        // flushCache saves the weblog, and JPAPersistenceStrategy.store()
        // decides insert-vs-update from whether the entity manager already
        // contains the object -- true within one request/session (the
        // production shape: resolve, then save, in the same call), false
        // for the `weblog` field here, which crossed a TestUtils.endSession
        // in @BeforeEach and is now detached. Re-fetching mirrors what a
        // real request does: resolve the weblog fresh, then act on it.
        Weblog attached = TestUtils.weblogger().getWeblogManager().getWeblog(weblog.getId());
        assertDoesNotThrow(() -> service.flushCache(attached));
    }

    @Test
    void rebuildIndexCompletesForARealWeblog() {
        assertDoesNotThrow(() -> service.rebuildIndex(weblog));
    }

    @Test
    void regenerateRenditionsCompletesForAWeblogWithNoMedia() throws Exception {
        // It saves the weblog too (moving lastModified, see
        // WeblogLastModifiedTest), so, as for flushCache: an attached weblog.
        Weblog attached = TestUtils.weblogger().getWeblogManager().getWeblog(weblog.getId());
        assertDoesNotThrow(() -> service.regenerateRenditions(attached));
    }

    @Test
    void aNullWeblogIsRejectedRatherThanActedOnGlobally() {
        assertThrows(IllegalArgumentException.class, () -> service.flushCache(null));
        assertThrows(IllegalArgumentException.class, () -> service.rebuildIndex(null));
        assertThrows(IllegalArgumentException.class, () -> service.regenerateRenditions(null));
    }
}
