package com.wikantik.pressroom.ui.controllers;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * Deleting a weblog, for the Remove Weblog page and the session-only {@code DELETE /v1/weblogs/{handle}}
 * (SA-18): irreversible and total (there is no trash), so asking for confirmation is the surface's
 * business and the permission is the weblog's {@code ADMIN}, enforced before this runs.
 */
public final class WeblogRemoval {

    private WeblogRemoval() {
    }

    /** Removes {@code weblog} with everything that hangs off it, flushes, and drops its cached pages. */
    public static void remove(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        weblogger.getWeblogManager().removeWeblog(weblog);
        weblogger.flush();
        CacheManager.invalidate(weblog);
    }
}
