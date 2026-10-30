package com.wikantik.pressroom.ui.rendering.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.util.introspection.Info;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The recorder sees unresolved, quiet, tested and missing-member references on a bare engine.
 * Velocity 2.4.1 reports a quiet reference without its bang ({@code $quiet}, not {@code $!quiet}).
 */
class InvalidReferenceRecorderTest {

    private VelocityEngine engine;

    @BeforeEach
    void setUp() {
        InvalidReferenceRecorder.clear();
        engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES,
                InvalidReferenceRecorder.class.getName());
        engine.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_QUIET, "true");
        engine.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_NULL, "true");
        engine.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_TESTED, "true");
        engine.init();
    }

    @AfterEach
    void tearDown() {
        InvalidReferenceRecorder.clear();
    }

    private String eval(String name, String template) {
        VelocityContext ctx = new VelocityContext();
        ctx.put("obj", new Object());
        StringWriter out = new StringWriter();
        engine.evaluate(ctx, out, name, template);
        return out.toString();
    }

    private void evalSample() {
        eval("t.vm", "$nope #if($tested)x#end $!quiet $obj.missing");
    }

    @Test
    void recordsLiteralQuietTestedAndMissingMemberReferences() {
        evalSample();
        Set<String> got = InvalidReferenceRecorder.recorded();
        assertTrue(got.contains("t.vm: $nope"), got.toString());
        assertTrue(got.contains("t.vm: $tested"), got.toString());
        assertTrue(got.contains("t.vm: $quiet"), got.toString());
        assertTrue(got.contains("t.vm: $obj.missing"), got.toString());
    }

    @Test
    void clearEmpties() {
        evalSample();
        InvalidReferenceRecorder.clear();
        assertTrue(InvalidReferenceRecorder.recorded().isEmpty());
    }

    @Test
    void stopsGrowingAtTheCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < InvalidReferenceRecorder.CAP + 1; i++) {
            sb.append("$undefined").append(i).append(' ');
        }
        eval("many.vm", sb.toString());
        assertEquals(InvalidReferenceRecorder.CAP, InvalidReferenceRecorder.recorded().size());
    }

    /**
     * Characterisation test, written for the O(1) full check (finding M-1) and
     * expected to pass against the size()-based one too: once full, later
     * renders, new references and repeats alike, add nothing.
     */
    @Test
    void addsNothingOnceFull() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < InvalidReferenceRecorder.CAP; i++) {
            sb.append("$undefined").append(i).append(' ');
        }
        eval("many.vm", sb.toString());
        Set<String> full = InvalidReferenceRecorder.recorded();

        evalSample();
        eval("many.vm", sb.toString());

        assertEquals(full, InvalidReferenceRecorder.recorded());
    }

    /**
     * Clearing a full recorder lets it record again. Characterisation test like
     * the one above; it bites a counter that clear() forgets to reset.
     */
    @Test
    void recordsAgainAfterAClear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < InvalidReferenceRecorder.CAP; i++) {
            sb.append("$undefined").append(i).append(' ');
        }
        eval("many.vm", sb.toString());
        InvalidReferenceRecorder.clear();

        evalSample();

        assertTrue(InvalidReferenceRecorder.recorded().contains("t.vm: $nope"));
    }

    /**
     * Renders run on many threads at once, so the cap must hold when they race
     * for its last places. Characterisation test: the size()-based check
     * passed it too (the race is too narrow to hit on demand); it pins that
     * the counter is taken after the add, never before.
     */
    @Test
    void neverPassesTheCapUnderConcurrentRecording() throws Exception {
        int threads = 16;
        int each = 400;
        InvalidReferenceRecorder recorder = new InvalidReferenceRecorder();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        recorder.invalidGetMethod(null, "$r" + thread + "x" + i, null, "p", new Info("race.vm", 1, 1));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(InvalidReferenceRecorder.CAP, InvalidReferenceRecorder.recorded().size());
    }

    @Test
    void recordsEachDistinctEntryOnce() {
        evalSample();
        Set<String> first = InvalidReferenceRecorder.recorded();
        evalSample();
        assertEquals(first, InvalidReferenceRecorder.recorded());
    }
}
