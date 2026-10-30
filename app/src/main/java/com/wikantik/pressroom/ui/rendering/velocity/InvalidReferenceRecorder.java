package com.wikantik.pressroom.ui.rendering.velocity;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

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
    /**
     * Entries added, so a full recorder answers in O(1): the skip list's
     * size() walks every entry. Taken after each add; an add that takes a
     * place past the cap is undone, so racing renders never pass it.
     */
    private static final AtomicInteger ADDED = new AtomicInteger();

    /** A copy of everything recorded so far. */
    public static SortedSet<String> recorded() {
        return new TreeSet<>(RECORDED);
    }

    public static void clear() {
        RECORDED.clear();
        ADDED.set(0);
    }

    private static void record(String reference, Info info) {
        if (ADDED.get() >= CAP) {
            return;
        }
        String entry = (info == null ? "?" : info.getTemplateName()) + ": " + reference;
        if (!RECORDED.add(entry)) {
            return;
        }
        if (ADDED.incrementAndGet() > CAP) {
            RECORDED.remove(entry);
            return;
        }
        if (log.isDebugEnabled()) {
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
        // reference is null only for a method call on a non-reference; never toString() an arbitrary object
        record(reference == null ? (object == null ? "null" : object.getClass().getSimpleName()) + "." + method + "()" : reference, info);
        return null;
    }
}
