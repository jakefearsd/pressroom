package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.util.GenericThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps {@link GenericThrottle} for the automation API, following the same
 * shape {@code ContactController} uses for its own throttle.
 *
 * <p>Sizing (threshold/interval/maxentries) is read once, at construction --
 * it dimensions a fixed tracking cache that cannot be resized under live
 * callers, the same reason the contact and newsletter throttles size
 * themselves at startup. The on/off switch, {@code api.throttle.enabled}, is
 * read fresh on every call instead, through {@link WebloggerRuntimeConfig},
 * so it can be flipped from Admin Settings without a restart.
 */
public final class ApiThrottle {

    private static final Logger log = LoggerFactory.getLogger(ApiThrottle.class);

    private final GenericThrottle throttle;

    /**
     * Non-null only for the test/disabled fixtures below, where there is no
     * running application (and so no runtime config) to consult.
     */
    private final Boolean forcedEnabled;

    /** The window length, which is also the longest a throttled caller can have left to wait. */
    private final int intervalSeconds;

    private ApiThrottle(GenericThrottle throttle, Boolean forcedEnabled, int intervalSeconds) {
        this.throttle = throttle;
        this.forcedEnabled = forcedEnabled;
        this.intervalSeconds = intervalSeconds;
    }

    /** The production throttle, sized from {@code roller.properties}. */
    public static ApiThrottle create() {
        int threshold = intProperty("api.throttle.threshold", 120);
        int interval = intProperty("api.throttle.interval", 60);
        // 500 tracked keys is a memory bound: every distinct bad token or source address takes a slot, so
        // a flood of random tokens can evict a real caller's counter. That only makes the throttle more
        // lenient for that caller; it never locks anyone out.
        int maxEntries = intProperty("api.throttle.maxentries", 500);
        return new ApiThrottle(
                new GenericThrottle(threshold, interval * PressroomConstants.SEC_IN_MS, maxEntries), null, interval);
    }

    /** A throttle sized directly, bypassing runtime config entirely. */
    static ApiThrottle forTesting(int threshold, int intervalSeconds) {
        return new ApiThrottle(
                new GenericThrottle(threshold, intervalSeconds * PressroomConstants.SEC_IN_MS, 1000),
                Boolean.TRUE, intervalSeconds);
    }

    /** A throttle that never throttles, for {@code api.throttle.enabled=false}. */
    static ApiThrottle disabled() {
        return new ApiThrottle(null, Boolean.FALSE, 0);
    }

    /**
     * Records a hit for {@code key} and reports whether it has now passed
     * the threshold.
     */
    public boolean isThrottled(String key) {
        boolean enabled = forcedEnabled != null
                ? forcedEnabled
                : WebloggerRuntimeConfig.getBooleanProperty("api.throttle.enabled");
        return enabled && throttle.processHit(key);
    }

    /**
     * The {@code Retry-After} a throttled caller is told, in seconds. A window
     * opens at a caller's first request and lasts the interval, so the whole
     * interval is never too short a wait; {@code GenericThrottle} does not
     * expose when the window opened, which would make it exact.
     */
    public int retryAfterSeconds() {
        return intervalSeconds;
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(WebloggerConfig.getProperty(name));
        } catch (NumberFormatException e) {
            // A bad value falls back to the default rather than stopping startup; say so,
            // or a typo in roller-custom.properties silently leaves the stock limits in force.
            log.warn("{} is not a number; using {}", name, fallback);
            return fallback;
        }
    }
}
