package org.apache.roller.weblogger.ui.core.security.sso;

import org.apache.roller.weblogger.business.WebloggerProvider;

/**
 * Releases the request's persistence session from inside the security chain.
 *
 * <p>The security chain runs at order 40 and {@code PersistenceSessionFilter},
 * which otherwise releases it, at order 60. A sign-in step that answers the
 * request itself never reaches order 60, so it releases here, exactly once,
 * or the EntityManager stays bound to the worker thread for whatever request
 * runs on it next (spec criterion 32; {@code ApiTokenAuthFilter} has the same
 * duty for the same reason).
 */
final class PersistenceSession {

    private PersistenceSession() {
    }

    /** Releases, unless the tier is not up yet, in which case nothing is bound. */
    static void release(WebloggerProvider webloggerProvider) {
        if (webloggerProvider.isBootstrapped()) {
            webloggerProvider.getWeblogger().release();
        }
    }
}
