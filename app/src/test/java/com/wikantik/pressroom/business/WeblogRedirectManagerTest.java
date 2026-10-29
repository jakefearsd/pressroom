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
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogRedirect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redirect store's contract: normalization applied identically at save
 * and match, per-weblog isolation, the open-redirect refusals, no chaining
 * from either end, and best-effort hit bookkeeping.
 */
class WeblogRedirectManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("redirectuser");
        weblog = TestUtils.setupWeblog("redirectblog", user);
        otherWeblog = TestUtils.setupWeblog("otherredirectblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogRedirectManager manager() {
        return TestUtils.weblogger().getWeblogRedirectManager();
    }

    private WeblogRedirect save(Weblog target, String source, String dest)
            throws Exception {
        WeblogRedirect redirect = new WeblogRedirect();
        redirect.setWeblog(TestUtils.getManagedWebsite(target));
        redirect.setSourcePath(source);
        redirect.setTargetPath(dest);
        redirect.setOrigin(WeblogRedirect.Origin.MANUAL);
        manager().saveRedirect(redirect);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return redirect;
    }

    private Weblog managed(Weblog w) throws Exception {
        return TestUtils.getManagedWebsite(w);
    }

    @Test
    void aSavedRedirectResolves() throws Exception {
        save(weblog, "/old-page", "/new-page");

        WeblogRedirect found = manager().resolve(managed(weblog), "/old-page");

        assertNotNull(found);
        assertEquals("/new-page", found.getTargetPath());
        assertEquals(WeblogRedirect.Origin.MANUAL, found.getOrigin());
        assertNotNull(found.getCreatedAt());
        assertEquals(0L, found.getHitCount());
        assertNull(found.getLastHitAt());
    }

    @Test
    void resolutionIsScopedToItsWeblog() throws Exception {
        save(weblog, "/old-page", "/new-page");

        assertNull(manager().resolve(managed(otherWeblog), "/old-page"),
                "a rule must never fire on another weblog's URLs");
    }

    @Test
    void anUnknownPathResolvesToNothing() throws Exception {
        assertNull(manager().resolve(managed(weblog), "/never-existed"));
    }

    /**
     * Migrated sites are inconsistent about trailing slashes, and a 404 over
     * a slash defeats the feature's purpose: one rule answers both spellings,
     * whichever spelling it was saved under.
     */
    @Test
    void trailingSlashesNormalizeToOneRule() throws Exception {
        save(weblog, "/old-section/", "/new-section");

        assertNotNull(manager().resolve(managed(weblog), "/old-section"));
        assertNotNull(manager().resolve(managed(weblog), "/old-section/"));
    }

    @Test
    void aSourceWithoutALeadingSlashIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "old-page", "/new-page"),
                "API input must be explicit about being weblog-relative");
    }

    /**
     * A redirect table that can point off-site is a phishing primitive. The
     * {@code //} prefix is the protocol-relative form that sneaks past a
     * naive starts-with-slash check.
     */
    @Test
    void aTargetPointingOffSiteIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/a", "https://evil.example/"));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/b", "//evil.example/"));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/c", "no-leading-slash"));
    }

    @Test
    void aMalformedTargetIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/a", "/new?utm=1"),
                "a target carrying its own query string would collide with the preserved request query");
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/b", "/new\\page"));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/c", "/new\npage"));
    }

    @Test
    void aTargetEqualToItsSourceIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "/same/", "/same"),
                "compared after normalization, not textually");
    }

    /**
     * One hop, enforced from both ends: no new rule may begin where an
     * existing one ends, nor end where an existing one begins.
     */
    @Test
    void aRuleMayNotExtendAChainFromEitherEnd() throws Exception {
        save(weblog, "/a", "/b");

        assertThrows(WebloggerException.class, () -> save(weblog, "/b", "/c"),
                "source equals an existing rule's target");
        assertThrows(WebloggerException.class, () -> save(weblog, "/c", "/a"),
                "target equals an existing rule's source");

        // ...but only within the weblog: the same shapes are fine elsewhere.
        save(otherWeblog, "/b", "/c");
    }

    @Test
    void aDuplicateSourceIsRefusedReadably() throws Exception {
        save(weblog, "/old-page", "/new-page");

        assertThrows(WebloggerException.class,
                () -> save(weblog, "/old-page/", "/elsewhere"),
                "a readable refusal, not a constraint-violation 500 -- and normalized first");
    }

    @Test
    void recordHitAdvancesCountAndTimestamp() throws Exception {
        WeblogRedirect saved = save(weblog, "/counted", "/target");

        manager().recordHit(manager().getRedirect(saved.getId()));
        TestUtils.endSession(true);
        manager().recordHit(manager().getRedirect(saved.getId()));
        TestUtils.endSession(true);

        WeblogRedirect after = manager().getRedirect(saved.getId());
        assertEquals(2L, after.getHitCount());
        assertNotNull(after.getLastHitAt());
    }

    /**
     * The hit is bookkeeping on top of a redirect already owed to the reader:
     * its failure is logged, never propagated.
     */
    @Test
    void recordHitOnADeletedRuleDoesNotThrow() throws Exception {
        WeblogRedirect saved = save(weblog, "/doomed", "/target");
        WeblogRedirect loaded = manager().getRedirect(saved.getId());
        manager().removeRedirect(loaded);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        manager().recordHit(loaded);
    }

    @Test
    void removeRedirectsEmptiesTheWeblogAndOnlyTheWeblog() throws Exception {
        save(weblog, "/one", "/1");
        save(weblog, "/two", "/2");
        save(otherWeblog, "/one", "/1");

        manager().removeRedirects(managed(weblog));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getRedirects(managed(weblog)).isEmpty());
        assertEquals(1, manager().getRedirects(managed(otherWeblog)).size());
    }

    @Test
    void aRedirectWithoutAWeblogIsRefused() {
        WeblogRedirect orphan = new WeblogRedirect();
        orphan.setSourcePath("/old");
        orphan.setTargetPath("/new");

        assertThrows(WebloggerException.class, () -> manager().saveRedirect(orphan));
    }

    @Test
    void aBlankSourceIsRefused() {
        assertThrows(WebloggerException.class, () -> save(weblog, "   ", "/new"));
    }

    /**
     * The serve path hands resolve whatever the request carried; anything
     * that is not an absolute path can never match a stored rule and must
     * come back null rather than reaching the query.
     */
    @Test
    void resolveIgnoresANonAbsolutePath() throws Exception {
        assertNull(manager().resolve(managed(weblog), "not-a-path"));
        assertNull(manager().resolve(managed(weblog), null));
        assertNull(WeblogRedirect.normalizePath(null),
                "normalizePath passes null through rather than throwing");
    }

    @Test
    void redirectsListNewestFirst() throws Exception {
        WeblogRedirect first = save(weblog, "/first", "/1");
        Thread.sleep(5);
        WeblogRedirect second = save(weblog, "/second", "/2");

        List<WeblogRedirect> listed = manager().getRedirects(managed(weblog));

        assertEquals(2, listed.size());
        assertEquals(second.getId(), listed.get(0).getId());
        assertEquals(first.getId(), listed.get(1).getId());
    }
}
