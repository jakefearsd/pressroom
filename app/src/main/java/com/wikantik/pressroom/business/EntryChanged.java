package com.wikantik.pressroom.business;

import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * What a committed entry write did, handed to every {@link EntryChangeListener}
 * once the unit of work that made it has committed. A save that fails or rolls
 * back publishes nothing.
 *
 * @param entry          the entry as saved (for a removal, as it was)
 * @param previousStatus the status the entry held before the write; null for a new entry
 * @param status         the status it holds now; null when it was removed
 * @param removed        the row was deleted
 * @param weblogRemoved  the row was deleted as part of its weblog's deletion, whose
 *                       own after-commit work (the weblog's index removal) covers it
 */
public record EntryChanged(WeblogEntry entry, PubStatus previousStatus, PubStatus status, boolean removed,
                           boolean weblogRemoved) {

    /** A write to the entry alone: not part of its weblog's deletion. */
    public EntryChanged(WeblogEntry entry, PubStatus previousStatus, PubStatus status, boolean removed) {
        this(entry, previousStatus, status, removed, false);
    }

    /** The entry is a live, published post after the write. */
    public boolean isPublished() {
        return !removed && status == PubStatus.PUBLISHED;
    }

    /** The entry was published before the write and is not now: anything that holds it must let go. */
    public boolean leftPublished() {
        return previousStatus == PubStatus.PUBLISHED && !isPublished();
    }
}
