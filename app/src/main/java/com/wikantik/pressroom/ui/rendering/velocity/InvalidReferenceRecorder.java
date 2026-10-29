package com.wikantik.pressroom.ui.rendering.velocity;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.velocity.app.event.InvalidReferenceEventHandler;
import org.apache.velocity.context.Context;
import org.apache.velocity.util.introspection.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every reference Velocity could not resolve, as
 * {@code templateName + ": " + reference}. Velocity is lenient: an unresolved
 * reference prints as literal text and logs nothing, so a renamed context name
 * would go unnoticed. The rendering tests clear this recorder, render, and
 * assert the recorded set is exactly the expected one.
 *
 * <p>Observation only: every callback returns {@code null}/{@code false}, so
 * rendering is unchanged. The set stops growing at {@link #CAP} entries so a
 * long-running server cannot leak memory through it.
 */
public final class InvalidReferenceRecorder implements InvalidReferenceEventHandler {

    static final int CAP = 1000;

    private static final Logger log = LoggerFactory.getLogger(InvalidReferenceRecorder.class);
    private static final Set<String> RECORDED = new ConcurrentSkipListSet<>();

    /** A copy of everything recorded so far. */
    public static SortedSet<String> recorded() {
        return new TreeSet<>(RECORDED);
    }

    public static void clear() {
        RECORDED.clear();
    }

    private static void record(String reference, Info info) {
        if (RECORDED.size() >= CAP) {
            return;
        }
        String entry = (info == null ? "?" : info.getTemplateName()) + ": " + reference;
        if (RECORDED.add(entry)) {
            log.debug("Unresolved Velocity reference {}", entry);
        }
    }

    @Override
    public Object invalidGetMethod(Context context, String reference, Object object,
            String property, Info info) {
        record(reference, info);
        return null;
    }

    @Override
    public boolean invalidSetMethod(Context context, String leftReference,
            String rightReference, Info info) {
        record(leftReference, info);
        return false;
    }

    @Override
    public Object invalidMethod(Context context, String reference, Object object,
            String method, Info info) {
        record(reference == null ? object + "." + method : reference, info);
        return null;
    }
}
