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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import jakarta.persistence.EntityTransaction;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Registers blogs with the analytics service and remembers which are
 * registered.
 *
 * <p><b>Registration.</b> {@link #reconcile()} builds a {@link SiteSpec} for
 * every weblog, inactive ones included since they are still served, and for
 * the site space, whose website carries the front page and site pages
 * (ruling R9): website ID = the stored one, else the weblog ID; name = the
 * blog's name; domain = {@link #domainFor}. It calls
 * {@link AnalyticsService#ensureSite} only for a blog that is not registered
 * yet or whose name or domain differs from what was last confirmed. Each
 * outcome is recorded in {@code analytics_site}, one commit per blog, so a
 * failure costs only that blog and is retried on the next run. A run with
 * nothing to send does not contact the service at all, not even to ping it.
 * Deleted blogs are never sent anywhere.
 *
 * <p><b>Rendered pages.</b> Whether a blog is registered decides whether its
 * pages carry the tracker, and rendered pages expire only through
 * {@code weblog.lastModified}. So when a blog's registration flips, the new
 * answer is published to memory first and the blog's {@code lastModified} is
 * advanced (through the weblog manager) after it: a page rendered in between
 * is dated before the bump and expires with it. The save's own hook would ask
 * for another sync; {@link #requestSync()} drops a request made from inside a
 * sync on the same thread, so the bump does not feed itself (ruling R1).
 *
 * <p><b>Triggering.</b> {@link #requestSync()} hands the work to the tier's
 * background pool and returns: at most one sync is in flight and one more
 * queued behind it; further requests while one is queued are dropped, since
 * the queued run has not started and will see their change. Once a blog's
 * creation or save has committed, the weblog manager calls
 * {@link #weblogSaved}, which requests a sync only when that blog is
 * unregistered or its name or domain moved; {@link #initialize()} requests
 * the first one, which is the
 * backfill. When the service is not configured nothing is scheduled and
 * nothing is called.
 *
 * <p><b>Answers.</b> The registrations are an immutable map, loaded at
 * startup and swapped by reference after each change, so a page asking
 * {@link #isRegistered} takes no lock and makes no query.
 *
 * <p><b>Reading.</b> After the reconcile, {@link #sync()} reads every
 * registered blog for each of {@link #periodKeys()}, in the site time zone
 * (the site space's, ruling R12), and replaces each (blog, period) snapshot
 * whole, one transaction each ({@link TrafficCache}). A blog whose read fails
 * keeps its previous snapshots and is STALE until a read succeeds; the others
 * carry on. When the first failure of a run comes before any answer, one ping
 * decides whether the service is down, and if it is the rest of the run is
 * not attempted, so a hung service costs a few timeouts, not one per blog
 * and period. A database failure stops the read (fail closed). The run's
 * outcome is the status's {@code reachable}, {@code lastRead} and
 * {@code lastReadError}. The readers answer from the snapshots alone.
 */
public class JPAAnalyticsManagerImpl implements AnalyticsManager {

    private static final Logger log = LoggerFactory.getLogger(JPAAnalyticsManagerImpl.class);

    /**
     * Umami 3.3.0's website-domain rule (docs/dev/umami-api.md, "Registering a
     * website"), minus the {@code localhost:<port>} form, since ports are
     * stripped first. Written without the original's nested quantifier
     * ({@code [x]+(-[x]+)*} matches exactly what {@code [x]+} does when the
     * class contains {@code -}, and the optional {@code xn--} prefix is
     * likewise already inside it), so it cannot backtrack badly.
     */
    private static final Pattern UMAMI_DOMAIN =
            Pattern.compile("localhost|([a-z0-9_-]{1,63}\\.)+[a-z0-9_-]{2,63}");

    /** A dotted-numeric host: an IPv4 literal. Umami rejects 127.0.0.1; all of them are treated alike. */
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");

    /** Not a DNS label character, as far as Umami's rule goes. */
    private static final Pattern NOT_LABEL = Pattern.compile("[^a-z0-9_-]");

    private static final int MAX_LABEL = 63;

    /**
     * Appended to a blog's handle to make the domain of a blog whose real host
     * Umami would reject (an IP literal, an IPv6 host, no site URL yet).
     * {@code .invalid} is reserved (RFC 2606), so it names no real host. The
     * domain is only Umami's label for the website; the tracker matches the
     * page's real {@code location.hostname} on its own.
     */
    static final String PLACEHOLDER_SUFFIX = ".localhost.invalid";

    /** Recorded against each pending blog when the service does not answer the run's ping. */
    static final String UNREACHABLE = "the analytics service did not answer, or refused Pressroom's account";

    /** Recorded as the read error when the local snapshot tables could not be written. */
    static final String CACHE_FAILED = "the local traffic cache could not be written; the read stopped";

    /** The periods every blog is read for; the discovery window joins them when it is not one. */
    private static final List<String> STANDARD_PERIODS = List.of("24h", "7d", "30d", "90d");

    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int QUEUED = 2;

    private final Weblogger weblogger;
    private final JPAPersistenceStrategy strategy;
    private final AnalyticsService service;
    private final Executor background;
    private final Clock clock;
    private final Supplier<String> siteUrl;
    private final IntSupplier windowDays;

    /** IDLE, RUNNING (a sync in flight) or QUEUED (in flight, and one more to follow). */
    private final AtomicInteger runState = new AtomicInteger(IDLE);

    /** Serialises syncs: the request-sync executor's and the scheduled task's. */
    private final Object syncLock = new Object();

    /** The thread inside a sync right now; a request it makes is its own bump's echo, and dropped. */
    private volatile Thread syncing;

    /**
     * What Umami last confirmed for each registered blog. Immutable; replaced whole.
     *
     * @param websiteId the website the blog is registered under
     * @param name      the name last sent
     * @param domain    the domain last sent
     */
    private record Registration(String websiteId, String name, String domain) {
    }

    /** Weblog ID to its registration, for registered blogs only. Immutable; replaced whole. */
    private volatile Map<String, Registration> registered = Map.of();

    private volatile Boolean reachable;
    private volatile AnalyticsStatus status = new AnalyticsStatus(true, null, 0, 0, List.of(), null, null);

    /** When a read last succeeded for some blog, and why the last run's reads did not all succeed. */
    private volatile Instant lastRead;
    private volatile String lastReadError;

    /** Weblog ID to why its last read failed; immutable, replaced whole. A blog in here is STALE. */
    private volatile Map<String, String> readFailures = Map.of();

    private final TrafficCache cache;

    public JPAAnalyticsManagerImpl(Weblogger weblogger, JPAPersistenceStrategy strategy, AnalyticsService service) {
        this(weblogger, strategy, service, inBackgroundOf(weblogger), Clock.systemUTC(),
                WebloggerRuntimeConfig::getAbsoluteContextURL, DiscoverySettings::windowDays);
    }

    JPAAnalyticsManagerImpl(Weblogger weblogger, JPAPersistenceStrategy strategy, AnalyticsService service,
                            Executor background, Clock clock, Supplier<String> siteUrl, IntSupplier windowDays) {
        this.weblogger = weblogger;
        this.strategy = strategy;
        this.service = service;
        this.background = background;
        this.clock = clock;
        this.siteUrl = siteUrl;
        this.windowDays = windowDays;
        this.cache = new TrafficCache(strategy);
    }

    /** The tier's background pool; a scheduling failure surfaces as a rejection. */
    private static Executor inBackgroundOf(Weblogger weblogger) {
        return task -> {
            try {
                weblogger.getThreadManager().executeInBackground(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while scheduling", e);
            }
        };
    }

    // ------------------------------------------------------------ startup

    /**
     * Loads what is registered, so pages are tagged correctly from the first
     * request, then requests the backfill sync. Display path: registrations
     * that cannot be loaded leave every blog untagged until the first sync,
     * which publishes them and expires the pages it changes.
     */
    @Override
    public void initialize() {
        if (!service.isConfigured()) {
            return;
        }
        synchronized (syncLock) {
            try {
                Map<String, AnalyticsSite> rows = loadRows();
                List<Weblog> weblogs = allWeblogs();
                registered = registrationsIn(rows);
                status = statusOf(rows, weblogs);
            } catch (WebloggerException | RuntimeException e) {
                log.error("Analytics could not load its registrations; blogs render untagged until the first sync",
                        e);
            }
        }
        requestSync();
    }

    // ------------------------------------------------------------ triggering

    /**
     * Requests a sync only when the saved blog is unregistered, or its name or
     * derived domain differs from what Umami last confirmed (ruling R10):
     * most saves (an entry, a comment's expiry, a template) change neither,
     * and must not cost a sync. Answered from memory, with no query.
     */
    @Override
    public void weblogSaved(Weblog weblog) {
        if (!service.isConfigured() || weblog == null || weblog.getId() == null) {
            return;
        }
        Registration registration = registered.get(weblog.getId());
        if (registration == null
                || !Objects.equals(registration.name(), nameOf(weblog))
                || !Objects.equals(registration.domain(), domainFor(weblog, siteUrl.get()))) {
            requestSync();
        }
    }

    @Override
    public void requestSync() {
        if (!service.isConfigured() || Thread.currentThread().equals(syncing)) {
            return;
        }
        while (true) {
            int state = runState.get();
            if (state == QUEUED) {
                return;
            }
            if (state == RUNNING) {
                if (runState.compareAndSet(RUNNING, QUEUED)) {
                    return;
                }
            } else if (runState.compareAndSet(IDLE, RUNNING)) {
                schedule();
                return;
            }
        }
    }

    /**
     * Hands a drain to the pool. Display path: a sync that cannot be
     * scheduled waits for the scheduled task, and nothing the caller does
     * depends on it.
     */
    private void schedule() {
        try {
            background.execute(this::drain);
        } catch (RuntimeException e) {
            runState.set(IDLE);
            log.warn("Analytics could not schedule a sync; the scheduled one will run it", e);
        }
    }

    /**
     * Runs syncs until no request is queued. Only this leaves QUEUED. A sync
     * that throws an Error ends the drain with the state reset to IDLE, which
     * drops a request queued behind it; the next request or the scheduled
     * sync picks its change up.
     */
    private void drain() {
        boolean drained = false;
        try {
            do {
                runOnce();
            } while (anotherRun());
            drained = true;
        } finally {
            if (!drained) {
                runState.set(IDLE);
            }
        }
    }

    private boolean anotherRun() {
        if (runState.compareAndSet(RUNNING, IDLE)) {
            return false;
        }
        // QUEUED: a request came in during the run; it becomes the run in flight
        runState.set(RUNNING);
        return true;
    }

    /**
     * One background sync, on its own unit of work. A sync records its own
     * failures; this only keeps a surprise from stopping the drain.
     */
    private void runOnce() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("Analytics sync failed", e);
        } finally {
            weblogger.release();
        }
    }

    // ------------------------------------------------------------ sync

    @Override
    public void sync() {
        guarded(() -> {
            reconcileNow();
            readTraffic();
        });
    }

    @Override
    public void reconcile() {
        guarded(this::reconcileNow);
    }

    /** Runs {@code work} as the one sync in progress, marking this thread as the syncing one. */
    // PMD sees the field written and then restored with no read between; the
    // read is requestSync's, on this thread, from inside work.run().
    @SuppressWarnings("PMD.UnusedAssignment")
    private void guarded(Runnable work) {
        if (!service.isConfigured()) {
            return;
        }
        synchronized (syncLock) {
            Thread previous = syncing;
            syncing = Thread.currentThread();
            try {
                work.run();
            } finally {
                syncing = previous;
            }
        }
    }

    /**
     * Reads every registered blog into the local snapshots, after the
     * reconcile so that only registered blogs are read (see the class
     * javadoc, "Reading"). Decision path: a blog is recorded as read only
     * when its snapshot committed; a read or write that could not run leaves
     * it failed, never fresh.
     */
    private void readTraffic() {
        Map<String, Registration> toRead = registered;
        if (toRead.isEmpty()) {
            return;
        }
        Map<String, String> failures = new HashMap<>();
        readFailures.forEach((id, error) -> {
            if (toRead.containsKey(id)) {
                failures.put(id, error);
            }
        });
        // a read starts from a clean transaction, whatever the reconcile left
        rollback();
        ReadRun run = new ReadRun();
        String current = null;
        try {
            List<Weblog> weblogs = allWeblogs();
            ZoneId zone = siteZone(weblogs);
            List<String> keys = periodKeys();
            URLStrategy urls = weblogger.getUrlStrategy();
            String context = contextPath(WebloggerRuntimeConfig.getRelativeContextURL(), siteUrl.get());
            for (Weblog weblog : weblogs) {
                Registration registration = toRead.get(weblog.getId());
                if (registration == null) {
                    continue;
                }
                current = weblog.getId();
                String error = run.open
                        ? readOne(weblog, registration.websiteId(), keys, zone, EntryPaths.forWeblog(weblog, urls,
                        context), run)
                        : UNREACHABLE;
                run.record(weblog.getHandle(), error);
                if (error == null) {
                    failures.remove(current);
                } else {
                    failures.put(current, error);
                    if (!run.answered && run.up == null) {
                        // nothing has answered yet: is the service there at all?
                        run.up = service.ping();
                        run.open = run.up;
                    }
                }
            }
        } catch (WebloggerException | RuntimeException e) {
            rollback();
            log.error("Analytics read stopped: the local traffic cache could not be read or written", e);
            if (current != null) {
                failures.put(current, CACHE_FAILED);
            }
            readFailures = Map.copyOf(failures);
            lastReadError = CACHE_FAILED;
            publishRead(run.answered ? Boolean.TRUE : reachable);
            return;
        }
        readFailures = Map.copyOf(failures);
        if (run.succeeded > 0) {
            lastRead = clock.instant();
        }
        lastReadError = run.failureSummary();
        publishRead(run.answered ? Boolean.TRUE : run.up == null ? reachable : run.up);
    }

    /**
     * Reads one blog for each period, stopping at its first failure, and
     * writes each period read, one transaction each. Returns null when every
     * period was read and written, else why the first one failed.
     */
    private String readOne(Weblog weblog, String websiteId, List<String> keys, ZoneId zone, EntryPaths paths,
                           ReadRun run) throws WebloggerException {
        Map<String, AnalyticsReport> reports = new LinkedHashMap<>();
        String error = null;
        for (String key : keys) {
            try {
                reports.put(key, service.read(websiteId, Period.of(key), zone));
            } catch (AnalyticsException e) {
                error = describe(e);
                log.warn("Analytics could not read weblog {} for {}", weblog.getHandle(), key, e);
                break;
            } catch (RuntimeException e) {
                // the message of an unexpected exception is not ours to show
                error = e.getClass().getSimpleName();
                log.warn("Analytics could not read weblog {} for {}", weblog.getHandle(), key, e);
                break;
            }
        }
        if (reports.isEmpty()) {
            return error;
        }
        run.answered = true;
        Set<String> recorded = new LinkedHashSet<>();
        for (AnalyticsReport report : reports.values()) {
            for (AnalyticsReport.PathCount path : report.paths() == null
                    ? List.<AnalyticsReport.PathCount>of() : report.paths()) {
                recorded.add(path.path());
            }
        }
        recorded.remove(null);
        Map<String, String> entryIds = paths.entryIds(recorded,
                anchors -> cache.entryIdsByAnchor(weblog.getId(), anchors));
        for (Map.Entry<String, AnalyticsReport> read : reports.entrySet()) {
            AnalyticsReport report = read.getValue();
            Instant asOf = report.asOf() == null ? clock.instant() : report.asOf();
            cache.replace(weblog.getId(), read.getKey(), report, asOf, entryIds);
        }
        return error;
    }

    /** One run's tally of reads. */
    private static final class ReadRun {
        /** Whether the service answered any read this run. */
        boolean answered;
        /** The run's ping, when one was needed; null when not. */
        Boolean up;
        /** Whether further blogs are attempted; false once a ping found the service down. */
        boolean open = true;
        int succeeded;
        int failed;
        String firstFailure;

        void record(String handle, String error) {
            if (error == null) {
                succeeded++;
            } else {
                failed++;
                if (firstFailure == null) {
                    firstFailure = handle + ": " + error;
                }
            }
        }

        /** Null when every blog was read; else the first failure, and how many more there were. */
        String failureSummary() {
            if (failed == 0) {
                return null;
            }
            return failed == 1 ? firstFailure : firstFailure + " (and " + (failed - 1) + " more)";
        }
    }

    /** Undoes whatever the failed snapshot left uncommitted. Display path: the caller has already stopped. */
    private void rollback() {
        try {
            EntityTransaction transaction = strategy.getEntityManager(false).getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
        } catch (RuntimeException e) {
            log.warn("Analytics could not roll back a failed snapshot; the caller's release will", e);
        }
    }

    /** The status as it stands, with this read's outcome. */
    private void publishRead(Boolean nowReachable) {
        reachable = nowReachable;
        AnalyticsStatus was = status;
        status = new AnalyticsStatus(was.configured(), nowReachable, was.registered(), was.total(), was.failures(),
                lastRead, lastReadError);
    }

    /**
     * The context path recorded paths start with: the one the first request
     * latched, else the path of the configured site URL, which carries the
     * context path by convention ({@code ""} for the root). Without the
     * fallback, every read before the first request (the startup backfill,
     * the immediate scheduled run) would map no entry under a prefix.
     */
    static String contextPath(String latched, String siteUrl) {
        if (latched != null) {
            return latched;
        }
        String path = null;
        if (StringUtils.isNotBlank(siteUrl)) {
            try {
                path = URI.create(siteUrl.trim()).getPath();
            } catch (IllegalArgumentException e) {
                log.debug("The site URL {} is not a URI; reading paths as at the root", siteUrl, e);
            }
        }
        return StringUtils.stripEnd(StringUtils.defaultString(path), "/");
    }

    /** The site time zone (R12): the site space's, else the system's. */
    private static ZoneId siteZone(List<Weblog> weblogs) {
        for (Weblog weblog : weblogs) {
            if (weblog.isSiteSpace() && StringUtils.isNotBlank(weblog.getTimeZone())) {
                try {
                    return ZoneId.of(weblog.getTimeZone());
                } catch (DateTimeException e) {
                    log.warn("The site space's time zone {} is not one; reading in the system's", weblog.getTimeZone());
                }
            }
        }
        return ZoneId.systemDefault();
    }

    /**
     * Decision path: registrations are published, and pages expired, only for
     * what was committed. A reconcile that cannot read the blogs, or stops on
     * a database failure, changes nothing further and is not recorded as a
     * success; the blogs not yet reached are retried on the next run.
     */
    private void reconcileNow() {
        Map<String, AnalyticsSite> rows;
        List<Weblog> weblogs;
        try {
            rows = loadRows();
            weblogs = allWeblogs();
        } catch (WebloggerException | RuntimeException e) {
            log.error("Analytics reconcile could not read the blogs; nothing was sent", e);
            return;
        }
        String site = siteUrl.get();
        // Umami is asked anything, a ping included, only when a blog needs a call (R10)
        boolean answered = false;
        if (anyPending(weblogs, rows, site)) {
            answered = service.ping();
            reachable = answered;
        }
        try {
            for (Weblog weblog : weblogs) {
                reconcileOne(weblog, rows, answered, site);
            }
        } catch (WebloggerException | RuntimeException e) {
            log.error("Analytics reconcile stopped; what it committed stands and the rest is retried", e);
            // what the failed blog left uncommitted must not ride on the read's first commit
            rollback();
            return;
        }
        registered = registrationsIn(rows);
        status = statusOf(rows, weblogs);
    }

    private static boolean anyPending(List<Weblog> weblogs, Map<String, AnalyticsSite> rows, String site) {
        for (Weblog weblog : weblogs) {
            AnalyticsSite row = rows.get(weblog.getId());
            if (needsCall(row, specFor(weblog, row, site))) {
                return true;
            }
        }
        return false;
    }

    private void reconcileOne(Weblog weblog, Map<String, AnalyticsSite> rows, boolean answered, String site)
            throws WebloggerException {
        String id = weblog.getId();
        AnalyticsSite row = rows.get(id);
        boolean wasRegistered = isRegistered(row);
        SiteSpec spec = specFor(weblog, row, site);
        boolean attempted = needsCall(row, spec);
        if (attempted) {
            row = attempt(row, id, weblog.getHandle(), spec, answered);
            rows.put(id, row);
        }
        Registration now = registrationOf(row);
        Registration before = registered.get(id);
        String websiteId = now == null ? null : now.websiteId();
        boolean flipped = wasRegistered != (now != null)
                || !Objects.equals(before == null ? null : before.websiteId(), websiteId);
        if (flipped) {
            // memory first, then the bump: a page rendered between them is
            // dated before the bump, so the bump expires it
            publish(id, now);
            weblogger.getWeblogManager().saveWeblog(weblog);
        }
        if (attempted || flipped) {
            strategy.flush();
        }
    }

    private AnalyticsSite attempt(AnalyticsSite existing, String weblogId, String handle, SiteSpec spec,
                                  boolean answered) throws WebloggerException {
        AnalyticsSite row = existing;
        if (row == null) {
            row = new AnalyticsSite();
            row.setWeblogId(weblogId);
            row.setWebsiteId(spec.websiteId());
        }
        Instant now = clock.instant();
        row.setLastAttempt(now);
        if (answered) {
            try {
                String websiteId = service.ensureSite(spec);
                row.setWebsiteId(websiteId == null ? spec.websiteId() : websiteId);
                row.setSyncedName(spec.name());
                row.setSyncedDomain(spec.domain());
                if (row.getRegisteredAt() == null) {
                    row.setRegisteredAt(now);
                }
                row.setLastError(null);
            } catch (AnalyticsException | RuntimeException e) {
                // recorded, not swallowed: the blog stays unconfirmed and is retried
                row.setLastError(describe(e));
                log.warn("Analytics could not register weblog {}", handle, e);
            }
        } else {
            row.setLastError(UNREACHABLE);
        }
        strategy.store(row);
        return row;
    }

    private static boolean needsCall(AnalyticsSite row, SiteSpec spec) {
        return row == null || row.getRegisteredAt() == null
                || !Objects.equals(row.getSyncedName(), spec.name())
                || !Objects.equals(row.getSyncedDomain(), spec.domain());
    }

    private static boolean isRegistered(AnalyticsSite row) {
        return row != null && row.getRegisteredAt() != null;
    }

    private static SiteSpec specFor(Weblog weblog, AnalyticsSite row, String siteUrl) {
        String websiteId = row == null ? weblog.getId() : row.getWebsiteId();
        return new SiteSpec(websiteId, nameOf(weblog), domainFor(weblog, siteUrl));
    }

    private static String nameOf(Weblog weblog) {
        return StringUtils.defaultIfBlank(weblog.getName(), weblog.getHandle());
    }

    /**
     * The domain a blog registers under: its custom domain, else the host of
     * the site URL, lower-cased and without a port. A host Umami's domain rule
     * would reject (an IP literal, an IPv6 host, none at all) gives
     * {@code <handle>.localhost.invalid} instead; Umami accepts
     * {@code localhost} as it is (docs/dev/umami-api.md).
     */
    static String domainFor(Weblog weblog, String siteUrl) {
        String host = AnalyticsHost.of(weblog.getCustomDomain(), siteUrl);
        if (host != null && UMAMI_DOMAIN.matcher(host).matches() && !IPV4_LITERAL.matcher(host).matches()) {
            return host;
        }
        return placeholderFor(weblog.getHandle());
    }

    private static String placeholderFor(String handle) {
        String label = NOT_LABEL.matcher(StringUtils.defaultString(handle).toLowerCase(Locale.ROOT))
                .replaceAll("-");
        label = StringUtils.left(label, MAX_LABEL);
        return (label.isEmpty() ? "blog" : label) + PLACEHOLDER_SUFFIX;
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ------------------------------------------------------------ reading the table

    private Map<String, AnalyticsSite> loadRows() throws WebloggerException {
        Map<String, AnalyticsSite> rows = new HashMap<>();
        for (AnalyticsSite row : strategy.getDynamicQuery("SELECT s FROM AnalyticsSite s", AnalyticsSite.class)
                .getResultList()) {
            rows.put(row.getWeblogId(), row);
        }
        return rows;
    }

    /**
     * Every weblog, active or not (an inactive blog is still served), plus the
     * site space, under whose website the front page and site pages are
     * tracked (ruling R9).
     */
    private List<Weblog> allWeblogs() throws WebloggerException {
        List<Weblog> all = new ArrayList<>(weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, -1));
        Weblog siteSpace = weblogger.getWeblogManager().getSiteSpace();
        if (siteSpace != null) {
            all.add(siteSpace);
        }
        return all;
    }

    private static Registration registrationOf(AnalyticsSite row) {
        return isRegistered(row)
                ? new Registration(row.getWebsiteId(), row.getSyncedName(), row.getSyncedDomain())
                : null;
    }

    private static Map<String, Registration> registrationsIn(Map<String, AnalyticsSite> rows) {
        Map<String, Registration> found = new HashMap<>();
        for (AnalyticsSite row : rows.values()) {
            Registration registration = registrationOf(row);
            if (registration != null) {
                found.put(row.getWeblogId(), registration);
            }
        }
        return Map.copyOf(found);
    }

    private void publish(String weblogId, Registration registration) {
        Map<String, Registration> next = new HashMap<>(registered);
        if (registration == null) {
            next.remove(weblogId);
        } else {
            next.put(weblogId, registration);
        }
        registered = Map.copyOf(next);
    }

    private AnalyticsStatus statusOf(Map<String, AnalyticsSite> rows, List<Weblog> weblogs) {
        int confirmed = 0;
        List<AnalyticsStatus.SiteFailure> failures = new ArrayList<>();
        for (Weblog weblog : weblogs) {
            AnalyticsSite row = rows.get(weblog.getId());
            if (isRegistered(row)) {
                confirmed++;
            }
            if (row != null && row.getLastError() != null) {
                failures.add(new AnalyticsStatus.SiteFailure(weblog.getHandle(), row.getLastError(),
                        row.getLastAttempt()));
            }
        }
        return new AnalyticsStatus(true, reachable, confirmed, weblogs.size(), failures, lastRead, lastReadError);
    }

    // ------------------------------------------------------------ answers

    @Override
    public boolean isRegistered(String weblogId) {
        return weblogId != null && registered.containsKey(weblogId);
    }

    @Override
    public String websiteIdFor(String weblogId) {
        Registration registration = weblogId == null ? null : registered.get(weblogId);
        return registration == null ? null : registration.websiteId();
    }

    @Override
    public AnalyticsStatus getStatus() {
        return service.isConfigured() ? status : AnalyticsStatus.notConfigured();
    }

    // ------------------------------------------------------------ traffic, from the snapshots

    @Override
    public List<String> periodKeys() {
        List<String> keys = new ArrayList<>(STANDARD_PERIODS);
        String window = windowDays.getAsInt() + "d";
        if (!keys.contains(window)) {
            keys.add(window);
        }
        return List.copyOf(keys);
    }

    @Override
    public BlogTraffic getBlogTraffic(String weblogId, String periodKey) {
        Period period = Period.of(periodKey);
        if (!service.isConfigured()) {
            return new BlogTraffic(TrafficState.NOT_CONFIGURED, null, null, null, null, null, null, null);
        }
        String failure = readFailures.get(weblogId);
        AnalyticsSite site = cache.site(weblogId);
        String error = failure != null || site == null ? failure : site.getLastError();
        AnalyticsSummary summary = cache.summary(weblogId, period.key());
        if (summary == null) {
            return new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, error);
        }
        TrafficState state;
        if (failure != null) {
            state = TrafficState.STALE;
        } else if (summary.getViews() == 0 && site != null && site.getRegisteredAt() != null
                && site.getRegisteredAt().isAfter(clock.instant().minus(TrafficCache.NO_DATA_GRACE))) {
            state = TrafficState.NO_DATA;
        } else {
            state = TrafficState.OK;
        }
        return new BlogTraffic(state, summary.getAsOf(), TrafficCache.current(summary),
                TrafficCache.previous(summary), cache.series(weblogId, period.key()),
                cache.posts(weblogId, period, longerThan(period)), cache.breakdowns(weblogId, period.key()), error);
    }

    @Override
    public SiteTraffic getSiteTraffic(String periodKey) {
        Period period = Period.of(periodKey);
        if (!service.isConfigured()) {
            return new SiteTraffic(TrafficState.NOT_CONFIGURED, null, null, 0, 0, null, null);
        }
        List<AnalyticsSummary> summaries = cache.summaries(period.key());
        if (summaries.isEmpty()) {
            return new SiteTraffic(TrafficState.NO_DATA, null, null, 0, 0, null, null);
        }
        Map<String, String> failures = readFailures;
        return cache.siteTraffic(summaries, period, longerThan(period), (Collection<String> ids) ->
                ids.stream().anyMatch(failures::containsKey) ? TrafficState.STALE : TrafficState.OK);
    }

    @Override
    public Map<String, Long> entryViews(String weblogId, String periodKey) {
        Period period = Period.of(periodKey);
        if (!service.isConfigured() || weblogId == null) {
            return Map.of();
        }
        return cache.entryViews(weblogId, period.key());
    }

    @Override
    public Map<String, Long> entryViewsAllBlogs(int windowDays) {
        Period period = Period.of(windowDays + "d");
        if (!service.isConfigured()) {
            return Map.of();
        }
        return cache.entryViews(null, period.key());
    }

    /** The shortest kept period longer than this one, for a post's trend; null when there is none. */
    private Period longerThan(Period period) {
        return periodKeys().stream()
                .map(Period::of)
                .filter(p -> p.length().compareTo(period.length()) > 0)
                .min(Comparator.comparing(Period::length))
                .orElse(null);
    }
}
