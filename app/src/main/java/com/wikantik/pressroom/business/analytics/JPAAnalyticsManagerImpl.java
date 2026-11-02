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
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Registers blogs with the analytics service and remembers which are
 * registered.
 *
 * <p><b>Registration.</b> {@link #reconcile()} builds a {@link SiteSpec} for
 * every active weblog (website ID = the stored one, else the weblog ID; name
 * = the blog's name; domain = {@link #domainFor}) and calls
 * {@link AnalyticsService#ensureSite} only for a blog that is not registered
 * yet or whose name or domain differs from what was last confirmed. Each
 * outcome is recorded in {@code analytics_site}, one commit per blog, so a
 * failure costs only that blog and is retried on the next run. Deleted blogs
 * are never sent anywhere.
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
 * the queued run has not started and will see their change. The weblog
 * manager requests a sync after a blog is created or saved, once that commit
 * has happened; {@link #initialize()} requests the first one, which is the
 * backfill. When the service is not configured nothing is scheduled and
 * nothing is called.
 *
 * <p><b>Answers.</b> The registrations are an immutable map, loaded at
 * startup and swapped by reference after each change, so a page asking
 * {@link #isRegistered} takes no lock and makes no query.
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

    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int QUEUED = 2;

    private final Weblogger weblogger;
    private final JPAPersistenceStrategy strategy;
    private final AnalyticsService service;
    private final Executor background;
    private final Clock clock;
    private final Supplier<String> siteUrl;

    /** IDLE, RUNNING (a sync in flight) or QUEUED (in flight, and one more to follow). */
    private final AtomicInteger runState = new AtomicInteger(IDLE);

    /** Serialises syncs: the request-sync executor's and the scheduled task's. */
    private final Object syncLock = new Object();

    /** The thread inside a sync right now; a request it makes is its own bump's echo, and dropped. */
    private volatile Thread syncing;

    /** Weblog ID to website ID, for registered blogs only. Immutable; replaced whole. */
    private volatile Map<String, String> registered = Map.of();

    private volatile Boolean reachable;
    private volatile AnalyticsStatus status = new AnalyticsStatus(true, null, 0, 0, List.of(), null, null);

    public JPAAnalyticsManagerImpl(Weblogger weblogger, JPAPersistenceStrategy strategy, AnalyticsService service) {
        this(weblogger, strategy, service, inBackgroundOf(weblogger), Clock.systemUTC(),
                WebloggerRuntimeConfig::getAbsoluteContextURL);
    }

    JPAAnalyticsManagerImpl(Weblogger weblogger, JPAPersistenceStrategy strategy, AnalyticsService service,
                            Executor background, Clock clock, Supplier<String> siteUrl) {
        this.weblogger = weblogger;
        this.strategy = strategy;
        this.service = service;
        this.background = background;
        this.clock = clock;
        this.siteUrl = siteUrl;
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
                List<Weblog> weblogs = activeWeblogs();
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

    /** Runs syncs until no request is queued; only this leaves QUEUED, so the state machine is exact. */
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
     * Reads traffic into the local cache, after the reconcile so that only
     * registered blogs are read. The batch read plugs in here; its outcome
     * becomes the status's {@code lastRead} / {@code lastReadError}, which
     * {@link #statusOf} passes as null until then.
     */
    private void readTraffic() {
        // the batch read (spec, "Numbers in") is not built yet
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
            weblogs = activeWeblogs();
        } catch (WebloggerException | RuntimeException e) {
            log.error("Analytics reconcile could not read the blogs; nothing was sent", e);
            return;
        }
        boolean answered = service.ping();
        reachable = answered;
        String site = siteUrl.get();
        try {
            for (Weblog weblog : weblogs) {
                reconcileOne(weblog, rows, answered, site);
            }
        } catch (WebloggerException | RuntimeException e) {
            log.error("Analytics reconcile stopped; what it committed stands and the rest is retried", e);
            return;
        }
        registered = registrationsIn(rows);
        status = statusOf(rows, weblogs);
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
        String websiteId = isRegistered(row) ? row.getWebsiteId() : null;
        boolean flipped = wasRegistered != (websiteId != null)
                || !Objects.equals(registered.get(id), websiteId);
        if (flipped) {
            // memory first, then the bump: a page rendered between them is
            // dated before the bump, so the bump expires it
            publish(id, websiteId);
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
        String name = StringUtils.defaultIfBlank(weblog.getName(), weblog.getHandle());
        return new SiteSpec(websiteId, name, domainFor(weblog, siteUrl));
    }

    /**
     * The domain a blog registers under: its custom domain, else the host of
     * the site URL, lower-cased and without a port. A host Umami's domain rule
     * would reject (an IP literal, an IPv6 host, none at all) gives
     * {@code <handle>.localhost.invalid} instead; Umami accepts
     * {@code localhost} as it is (docs/dev/umami-api.md).
     */
    static String domainFor(Weblog weblog, String siteUrl) {
        String host = StringUtils.trimToNull(weblog.getCustomDomain());
        if (host == null) {
            host = hostOf(siteUrl);
        }
        host = normalise(host);
        if (host != null && UMAMI_DOMAIN.matcher(host).matches() && !IPV4_LITERAL.matcher(host).matches()) {
            return host;
        }
        return placeholderFor(weblog.getHandle());
    }

    private static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a blog's own host for Umami's lower-case-only domain rule; the result is "
                    + "only Umami's label for the website, never used to decide access, and Umami validates it again.")
    private static String normalise(String host) {
        if (host == null) {
            return null;
        }
        String lower = StringUtils.stripEnd(host.trim().toLowerCase(Locale.ROOT), ".");
        if (lower.startsWith("[")) {
            // an IPv6 literal: its colons are not a port, and Umami rejects it anyway
            return lower;
        }
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(0, colon);
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

    private List<Weblog> activeWeblogs() throws WebloggerException {
        return weblogger.getWeblogManager().getWeblogs(null, Boolean.TRUE, null, null, 0, -1);
    }

    private static Map<String, String> registrationsIn(Map<String, AnalyticsSite> rows) {
        Map<String, String> found = new HashMap<>();
        for (AnalyticsSite row : rows.values()) {
            if (isRegistered(row)) {
                found.put(row.getWeblogId(), row.getWebsiteId());
            }
        }
        return Map.copyOf(found);
    }

    private void publish(String weblogId, String websiteId) {
        Map<String, String> next = new HashMap<>(registered);
        if (websiteId == null) {
            next.remove(weblogId);
        } else {
            next.put(weblogId, websiteId);
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
        // lastRead and lastReadError: no batch read yet (see readTraffic)
        return new AnalyticsStatus(true, reachable, confirmed, weblogs.size(), failures, null, null);
    }

    // ------------------------------------------------------------ answers

    @Override
    public boolean isRegistered(String weblogId) {
        return weblogId != null && registered.containsKey(weblogId);
    }

    @Override
    public String websiteIdFor(String weblogId) {
        return weblogId == null ? null : registered.get(weblogId);
    }

    @Override
    public AnalyticsStatus getStatus() {
        return service.isConfigured() ? status : AnalyticsStatus.notConfigured();
    }
}
