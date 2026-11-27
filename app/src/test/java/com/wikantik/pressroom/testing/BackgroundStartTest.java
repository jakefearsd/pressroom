package com.wikantik.pressroom.testing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A shared resource started once, in the background when asked early, and waited for on use. */
class BackgroundStartTest {

    @Test
    void itStartsOnceAndEveryCallerGetsTheSameInstance() {
        AtomicInteger starts = new AtomicInteger();
        BackgroundStart<Object> start = new BackgroundStart<>("thing", () -> {
            starts.incrementAndGet();
            return new Object();
        });
        start.begin();
        start.begin();
        Object first = start.get();
        assertSame(first, start.get());
        assertEquals(1, starts.get());
    }

    @Test
    void anEarlyBeginRunsOffTheCallersThreadAndGetWaitsForIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String caller = Thread.currentThread().getName();
        String[] ranOn = new String[1];
        BackgroundStart<String> start = new BackgroundStart<>("thing", () -> {
            ranOn[0] = Thread.currentThread().getName();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ready";
        });
        start.begin();
        release.countDown();
        assertEquals("ready", start.get());
        assertTrue(!caller.equals(ranOn[0]), "the start ran in the background, not on " + caller);
    }

    @Test
    void getWithoutBeginStartsIt() {
        BackgroundStart<String> start = new BackgroundStart<>("thing", () -> "ready");
        assertEquals("ready", start.get());
    }

    @Test
    void aFailedStartFailsEveryGetWithItsCause() {
        IllegalStateException cause = new IllegalStateException("docker is not running");
        BackgroundStart<String> start = new BackgroundStart<>("thing", () -> {
            throw cause;
        });
        start.begin();
        assertSame(cause, assertThrows(IllegalStateException.class, start::get));
        assertSame(cause, assertThrows(IllegalStateException.class, start::get));
    }
}
