package com.wikantik.pressroom.ui.rendering;

import static org.junit.jupiter.api.Assertions.fail;

import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import java.util.Set;
import java.util.TreeSet;

/**
 * Strict half of the rendering tests. Velocity prints an unresolved reference
 * as literal text and says nothing, so a rendering test calls
 * {@link InvalidReferenceRecorder#clear()} before its renders and
 * {@link #assertRecordedExactly} after them. The expected set is what the
 * shipped templates legitimately leave unresolved (optional model values tested
 * in {@code #if}, quiet {@code $!} references); anything else, or anything
 * missing, fails with the sorted difference both ways.
 */
public final class StrictReferences {

    private StrictReferences() {
    }

    public static void assertRecordedExactly(String label, Set<String> expected) {
        Set<String> actual = InvalidReferenceRecorder.recorded();
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            fail(label + ": invalid Velocity references differ from the expected set\n"
                    + "unexpected (recorded, not allowed):\n  " + String.join("\n  ", unexpected)
                    + "\nmissing (allowed, not recorded):\n  " + String.join("\n  ", missing));
        }
    }
}
