package com.wikantik.pressroom.ui.restapi.dto;

import java.sql.Timestamp;
import java.time.Instant;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The trash page's "Trashed" column reads the entry's own trashedAt. */
class EntryViewTrashedAtTest {

    @Test
    void aTrashedEntryViewSaysWhenItWasTrashed() {
        WeblogEntry entry = new WeblogEntry();
        Instant when = Instant.parse("2026-03-01T09:30:00Z");
        entry.setTrashedAt(Timestamp.from(when));

        assertEquals(when, EntryDtos.toView(entry, "https://x/p").trashedAt());
    }

    @Test
    void anEntryThatIsNotInTheTrashHasNone() {
        assertNull(EntryDtos.toView(new WeblogEntry(), "https://x/p").trashedAt());
    }
}
