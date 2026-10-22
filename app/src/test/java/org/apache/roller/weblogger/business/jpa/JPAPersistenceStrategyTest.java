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
package org.apache.roller.weblogger.business.jpa;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

import org.apache.roller.testing.RollerDatabaseExtension;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link JPAPersistenceStrategy}.
 */
class JPAPersistenceStrategyTest {

    @Test
    void shutdownToleratesBeingCalledMoreThanOnce() throws Exception {
        // The Weblogger bean's destroyMethod="shutdown" and a caller invoking
        // Weblogger.shutdown() directly can both reach here for the same
        // instance; EclipseLink's EntityManagerFactory.close() throws
        // IllegalStateException on a second close, so shutdown() has to guard
        // against that itself.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());

        strategy.shutdown();
        assertDoesNotThrow(strategy::shutdown,
                "a second shutdown() on the same strategy must be a no-op, not throw");
    }

    @Test
    void releaseToleratesBeingCalledAfterShutdown() throws Exception {
        // JPAWebloggerImpl.shutdown() calls release() and then shutdown() on
        // this strategy on every pass, and (per shutdownToleratesBeingCalled-
        // MoreThanOnce above) that whole sequence can itself run twice for
        // the same instance. On the second pass, release() is reached against
        // an already-closed EntityManagerFactory; before this guard, that
        // threw IllegalStateException out of EntityManagerFactory.createEntityManager(),
        // caught and logged at ERROR rather than propagated, on every
        // graceful stop.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());

        strategy.shutdown();
        assertDoesNotThrow(strategy::release,
                "release() after shutdown() must be a no-op, not throw");
        assertDoesNotThrow(strategy::shutdown,
                "shutdown() after release()-after-shutdown() must still be a no-op, not throw");
    }

    @Test
    void handBuiltPersistenceUnitInfoBuildsAWorkingEmfAndAWeblogManagerQueryRoundTrips() throws Exception {
        // Task 2b: the constructor now builds the EntityManagerFactory via
        // PersistenceProvider#createContainerEntityManagerFactory with a
        // hand-built PersistenceUnitInfo instead of the classpath-scanning
        // Persistence.createEntityManagerFactory("RollerPU", props). This
        // proves that path actually produces a working EMF against a real
        // (Testcontainers) database: store a Weblog directly through the
        // strategy, then read it back through a JPA-backed WeblogManager
        // named query, exactly as the web tier would.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            Weblog weblog = new Weblog();
            weblog.setName("Persistence Strategy Test Weblog");
            weblog.setHandle("jpapersistencestrategytest");
            weblog.setEmailAddress("jpapersistencestrategytest@dev.null");
            weblog.setEditorTheme("journal");
            weblog.setLocale("en_US");
            weblog.setTimeZone("America/Los_Angeles");
            weblog.setDateCreated(new Date());
            weblog.setCreatorUserName("nobody");

            strategy.store(weblog);
            strategy.flush();

            WeblogManager weblogManager = new JPAWeblogManagerImpl(null, strategy, mock(VirtualHostRegistry.class));
            Weblog reloaded = weblogManager.getWeblogByHandle("jpapersistencestrategytest");

            assertNotNull(reloaded,
                    "a WeblogManager named query through the hand-built PersistenceUnitInfo "
                            + "path must find the weblog stored via the same EMF");
            assertEquals(weblog.getId(), reloaded.getId());

            strategy.remove(reloaded);
            strategy.flush();
        } finally {
            strategy.shutdown();
        }
    }

    /**
     * I5's underlying mechanism, tested directly against
     * {@code JPAPersistenceStrategy} rather than through {@code
     * VirtualHostRegistry}: a {@code runAfterCommit} callback must not run
     * until {@code flush()}'s {@code commit()} has actually succeeded, and
     * must run exactly once even though nothing re-queues it on a later
     * {@code flush()} call.
     */
    @Test
    void runAfterCommitActionsRunOnlyOnceAndOnlyAfterCommit() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            Weblog weblog = new Weblog();
            weblog.setName("Run After Commit Test Weblog");
            weblog.setHandle("runaftercommittest");
            weblog.setEmailAddress("runaftercommittest@dev.null");
            weblog.setEditorTheme("journal");
            weblog.setLocale("en_US");
            weblog.setTimeZone("America/Los_Angeles");
            weblog.setDateCreated(new Date());
            weblog.setCreatorUserName("nobody");

            strategy.store(weblog);

            AtomicInteger runs = new AtomicInteger();
            strategy.runAfterCommit(runs::incrementAndGet);

            assertEquals(0, runs.get(), "must not run before the commit actually happens");

            strategy.flush();
            assertEquals(1, runs.get(), "must run exactly once, once the commit has succeeded");

            WeblogManager weblogManager = new JPAWeblogManagerImpl(null, strategy, mock(VirtualHostRegistry.class));
            Weblog reloaded = weblogManager.getWeblogByHandle("runaftercommittest");
            strategy.remove(reloaded);
            strategy.flush();

            assertEquals(1, runs.get(),
                    "a later flush() with nothing newly queued must not re-run a stale action");
        } finally {
            strategy.shutdown();
        }
    }

    /**
     * A row-write listener runs inside the commit that writes the row, so an
     * action it queues with {@code runAfterCommit} lands after flush() took
     * its snapshot of the queue; it must still run once that commit has
     * succeeded -- for an insert, an update and a delete alike. (A delete
     * may raise more than one event; what matters is that it raises one.)
     */
    @Test
    void actionsQueuedByARowWriteListenerRunOnceTheCommitSucceeds() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            AtomicInteger written = new AtomicInteger();
            AtomicInteger afterCommit = new AtomicInteger();
            strategy.onRowWrite(Weblog.class, () -> {
                written.incrementAndGet();
                strategy.runAfterCommit(afterCommit::incrementAndGet);
            });

            Weblog weblog = new Weblog();
            weblog.setName("Row Write Test Weblog");
            weblog.setHandle("rowwritetest");
            weblog.setEmailAddress("rowwritetest@dev.null");
            weblog.setEditorTheme("journal");
            weblog.setLocale("en_US");
            weblog.setTimeZone("America/Los_Angeles");
            weblog.setDateCreated(new Date());
            weblog.setCreatorUserName("nobody");
            strategy.store(weblog);
            assertEquals(0, written.get(), "nothing is written before the commit");

            strategy.flush();
            assertEquals(1, written.get(), "the insert");
            assertEquals(1, afterCommit.get(), "queued inside the commit, run after it");

            weblog.setName("Row Write Test Weblog, renamed");
            strategy.getEntityManager(true);
            strategy.flush();
            assertEquals(2, afterCommit.get(), "the update");

            strategy.remove(weblog);
            strategy.flush();
            assertTrue(afterCommit.get() > 2, "the delete");
        } finally {
            strategy.shutdown();
        }
    }

    /**
     * After-commit actions are independent: one that throws is logged and
     * the rest still run, those queued by a row-write listener during the
     * commit included -- a lost invalidation would leave a cache stale with
     * nothing to correct it. The commit itself succeeded, so flush() does not
     * report a failure.
     */
    @Test
    void aThrowingAfterCommitActionDoesNotStopTheOthers() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            AtomicInteger queuedBefore = new AtomicInteger();
            AtomicInteger queuedByTheCommit = new AtomicInteger();
            strategy.onRowWrite(Weblog.class,
                    () -> strategy.runAfterCommit(queuedByTheCommit::incrementAndGet));

            Weblog weblog = throwawayWeblog("afterthrowtest");
            strategy.store(weblog);
            strategy.runAfterCommit(() -> {
                throw new IllegalStateException("an after-commit action failed");
            });
            strategy.runAfterCommit(queuedBefore::incrementAndGet);

            assertDoesNotThrow(strategy::flush);
            assertEquals(1, queuedBefore.get(), "queued before the commit, after the throw");
            assertEquals(1, queuedByTheCommit.get(), "queued by the commit");

            strategy.remove(weblog);
            strategy.flush();
        } finally {
            strategy.shutdown();
        }
    }

    /**
     * What a row-write listener queued inside a commit that then failed must
     * not wait in the thread for a later, unrelated commit to run it.
     */
    @Test
    void aFailedCommitDropsWhatItsListenersQueued() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            AtomicInteger ran = new AtomicInteger();
            strategy.onRowWrite(Weblog.class, () -> strategy.runAfterCommit(ran::incrementAndGet));

            // The weblog row is written (the listener queues), then the page
            // without a weblog violates NOT NULL and the commit fails.
            strategy.store(throwawayWeblog("failedcommitqueue"));
            strategy.store(new WeblogPage());
            assertThrows(WebloggerException.class, strategy::flush);

            strategy.getEntityManager(false).clear();
            strategy.getEntityManager(true);
            strategy.flush(); // an unrelated, successful commit

            assertEquals(0, ran.get(), "nothing from the failed commit runs after a later one");
        } finally {
            strategy.shutdown();
        }
    }

    private static Weblog throwawayWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setName("Throwaway " + handle);
        weblog.setHandle(handle);
        weblog.setEmailAddress(handle + "@dev.null");
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/Los_Angeles");
        weblog.setDateCreated(new Date());
        weblog.setCreatorUserName("nobody");
        return weblog;
    }

    @Test
    void refreshOfAnEntityTheEntityManagerHasNeverSeenIsANoOpRatherThanThrowing() throws Exception {
        // EntityManager.refresh() throws for an entity that is not managed by
        // this persistence context. refresh() exists to be called
        // speculatively; the caller re-reads through the EntityManager
        // either way, so that failure must be swallowed, not propagated.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            Weblog neverPersisted = new Weblog();
            neverPersisted.setName("Never Persisted");
            neverPersisted.setHandle("neverpersisted");

            assertDoesNotThrow(() -> strategy.refresh(neverPersisted),
                    "refresh() of an entity the EntityManager has never seen must not throw");
        } finally {
            strategy.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Characterisation tests (written against the existing behaviour and
    // expected to pass immediately): commit failure, the by-id and bulk
    // removes, and the untyped named query's no-flush contract.
    // ------------------------------------------------------------------

    private static JPAPersistenceStrategy freshStrategy() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }
        return new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setName("Strategy Test " + handle);
        weblog.setHandle(handle);
        weblog.setEmailAddress(handle + "@dev.null");
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/Los_Angeles");
        weblog.setDateCreated(new Date());
        weblog.setCreatorUserName("nobody");
        return weblog;
    }

    private static List<?> byHandle(JPAPersistenceStrategy strategy, String handle) throws Exception {
        Query q = strategy.getNamedQuery("Weblog.getByHandle");
        q.setParameter(1, handle);
        return q.getResultList();
    }

    @Test
    void aCommitThatViolatesAConstraintSurfacesAsWebloggerExceptionAndStoresNothing() throws Exception {
        JPAPersistenceStrategy strategy = freshStrategy();
        try {
            strategy.store(weblog("strategydupehandle"));
            strategy.store(weblog("strategydupehandle"));

            WebloggerException thrown = assertThrows(WebloggerException.class, strategy::flush);
            assertInstanceOf(PersistenceException.class, thrown.getCause());
            strategy.release();

            assertTrue(byHandle(strategy, "strategydupehandle").isEmpty(),
                    "the failed commit must not have stored either row");
        } finally {
            strategy.release();
            strategy.shutdown();
        }
    }

    @Test
    void removeByClassAndIdAndRemoveAllDeleteTheRows() throws Exception {
        JPAPersistenceStrategy strategy = freshStrategy();
        try {
            Weblog single = weblog("strategyremovebyid");
            Weblog first = weblog("strategyremoveall1");
            Weblog second = weblog("strategyremoveall2");
            strategy.store(single);
            strategy.store(first);
            strategy.store(second);
            strategy.flush();
            strategy.release();

            strategy.remove(Weblog.class, single.getId());
            strategy.removeAll(List.of(strategy.load(Weblog.class, first.getId()),
                    strategy.load(Weblog.class, second.getId())));
            strategy.flush();
            strategy.release();

            assertNull(strategy.load(Weblog.class, single.getId()));
            assertNull(strategy.load(Weblog.class, first.getId()));
            assertNull(strategy.load(Weblog.class, second.getId()));
        } finally {
            strategy.release();
            strategy.shutdown();
        }
    }

    @Test
    void theUntypedNamedQueryNeverFlushesPendingWrites() throws Exception {
        JPAPersistenceStrategy strategy = freshStrategy();
        try {
            Weblog pending = weblog("strategynamedquery");
            strategy.store(pending);

            Query q = strategy.getNamedQuery("Weblog.getByHandle");
            assertEquals(FlushModeType.COMMIT, q.getFlushMode());
            q.setParameter(1, "strategynamedquery");
            assertTrue(q.getResultList().isEmpty(),
                    "FlushModeType.COMMIT: an unflushed insert is invisible to the query");

            strategy.flush();
            assertEquals(1, byHandle(strategy, "strategynamedquery").size());

            strategy.remove(Weblog.class, pending.getId());
            strategy.flush();
        } finally {
            strategy.release();
            strategy.shutdown();
        }
    }
}
