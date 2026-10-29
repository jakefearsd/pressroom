package com.wikantik.pressroom.ui.rendering.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.Set;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
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

    @Test
    void recordsEachDistinctEntryOnce() {
        evalSample();
        Set<String> first = InvalidReferenceRecorder.recorded();
        evalSample();
        assertEquals(first, InvalidReferenceRecorder.recorded());
    }
}
