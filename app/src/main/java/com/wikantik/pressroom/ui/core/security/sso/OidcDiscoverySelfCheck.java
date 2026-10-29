package com.wikantik.pressroom.ui.core.security.sso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup probe of the OIDC discovery document, off the boot thread. It only
 * reports: the repository logs one WARN per failed attempt and the registration
 * is retried on demand, so a provider outage never blocks or breaks startup.
 */
public final class OidcDiscoverySelfCheck {

    private static final Logger log = LoggerFactory.getLogger("roller.sso");
    private static final int ATTEMPTS = 3;

    private final LazyOidcClientRegistrationRepository repo;
    private final long delayMillis;

    public OidcDiscoverySelfCheck(LazyOidcClientRegistrationRepository repo, long delayMillis) {
        this.repo = repo;
        this.delayMillis = delayMillis;
    }

    /** Starts the daemon probe thread and returns it. */
    public Thread start() {
        Thread t = new Thread(this::run, "roller-sso-selfcheck");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void run() {
        try {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (repo.tryResolveOnce()) {
                    log.info("OIDC discovery reachable");
                    return;
                }
                if (attempt < ATTEMPTS) {
                    Thread.sleep(delayMillis * attempt);
                }
            }
            // Each failed attempt already logged one WARN; no summary WARN (spec criterion 3).
            log.debug("OIDC discovery unreachable after {} attempts; sign-in will retry on demand", ATTEMPTS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.debug("OIDC discovery self-check stopped: {}", ex.getClass().getSimpleName());
        }
    }
}
