package com.wikantik.pressroom.business;

import com.wikantik.pressroom.util.cache.CacheManager;

/** Expires what the cache handlers hold for an entry once a write to it has committed. */
public final class EntryCacheInvalidator implements EntryChangeListener {

    @Override
    public void entryChanged(EntryChanged change) {
        CacheManager.invalidate(change.entry());
    }
}
