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
 */
public record EntryChanged(WeblogEntry entry, PubStatus previousStatus, PubStatus status, boolean removed) {

    /** The entry is a live, published post after the write. */
    public boolean isPublished() {
        return !removed && status == PubStatus.PUBLISHED;
    }

    /** The entry was published before the write and is not now: anything that holds it must let go. */
    public boolean leftPublished() {
        return previousStatus == PubStatus.PUBLISHED && !isPublished();
    }
}
