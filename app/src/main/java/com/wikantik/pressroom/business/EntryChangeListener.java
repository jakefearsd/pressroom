package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;

/**
 * Subscribes to committed entry writes (save, trash, restore, restore of a
 * revision, removal). The entry manager is the only publisher, so a derived
 * store (search index, rendered-page caches) is kept in step in one place and
 * not by each caller that happens to remember. Registered as a Spring bean;
 * the manager takes every one.
 *
 * <p>A listener that throws is logged and skipped: the write has committed and
 * the other listeners still run.
 */
@FunctionalInterface
public interface EntryChangeListener {

    void entryChanged(EntryChanged change) throws WebloggerException;
}
