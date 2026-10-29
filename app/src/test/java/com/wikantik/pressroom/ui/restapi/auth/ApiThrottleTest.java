package com.wikantik.pressroom.ui.restapi.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The mint endpoint takes a password, so it is the one place on this API
 * where guessing pays. It is throttled by client address; every other
 * endpoint is throttled by token, so one noisy agent cannot starve another.
 *
 * Sizing stays startup-scoped (api.throttle.threshold / .interval /
 * .maxentries) because it dimensions a fixed cache that cannot be resized
 * under live callers -- the same reason the contact and newsletter throttles
 * are startup-scoped. Only the on/off switch is runtime-settable.
 */
class ApiThrottleTest {

    @Test
    void aCallerIsRefusedOnceItPassesTheThreshold() {
        ApiThrottle throttle = ApiThrottle.forTesting(3, 60);

        assertFalse(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-a"));
        assertTrue(throttle.isThrottled("agent-a"), "the fourth call exceeds a threshold of 3");
    }

    @Test
    void oneCallerCannotStarveAnother() {
        ApiThrottle throttle = ApiThrottle.forTesting(1, 60);

        assertFalse(throttle.isThrottled("agent-a"));
        assertTrue(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-b"), "buckets are per key, not global");
    }

    @Test
    void disablingTheThrottleLetsEverythingThrough() {
        ApiThrottle throttle = ApiThrottle.disabled();
        for (int i = 0; i < 100; i++) {
            assertFalse(throttle.isThrottled("agent-a"));
        }
    }
}
