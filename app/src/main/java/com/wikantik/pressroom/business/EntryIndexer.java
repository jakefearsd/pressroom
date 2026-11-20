package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.search.IndexManager;

/**
 * Keeps the search index in step with committed entry writes: a published
 * entry is re-indexed, one that was published and no longer is (unpublished,
 * trashed, re-scheduled, removed) is taken out. A write that never touched a
 * published entry does nothing.
 */
public final class EntryIndexer implements EntryChangeListener {

    private final Weblogger weblogger;

    /** @param weblogger the business tier, lazily: the index manager is asked per event */
    public EntryIndexer(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public void entryChanged(EntryChanged change) throws WebloggerException {
        IndexManager index = weblogger.getIndexManager();
        if (change.isPublished()) {
            index.addEntryReIndexOperation(change.entry());
        } else if (change.leftPublished()) {
            index.removeEntryIndexOperation(change.entry());
        }
    }
}
