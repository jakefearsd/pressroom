package com.wikantik.pressroom.pojos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/** The entry's own state-transition rules, with no database. */
class WeblogEntryTransitionsTest {

    private static final Timestamp NOW = new Timestamp(1_000_000_000L);

    @Test
    void publishStampsNowWhenThereIsNoPubTime() {
        WeblogEntry e = new WeblogEntry();
        e.publish(NOW);
        assertEquals(PubStatus.PUBLISHED, e.getStatus());
        assertSame(NOW, e.getPubTime());
    }

    @Test
    void publishKeepsAnExistingPubTime() {
        WeblogEntry e = new WeblogEntry();
        Timestamp earlier = new Timestamp(5L);
        e.setPubTime(earlier);
        e.publish(NOW);
        assertSame(earlier, e.getPubTime());
    }

    @Test
    void stampPubTimeOnlyAffectsAPublishedEntryWithoutOne() {
        WeblogEntry draft = new WeblogEntry();
        draft.setStatus(PubStatus.DRAFT);
        draft.stampPubTimeIfPublished(NOW);
        assertNull(draft.getPubTime());

        WeblogEntry live = new WeblogEntry();
        live.setStatus(PubStatus.PUBLISHED);
        live.stampPubTimeIfPublished(NOW);
        assertSame(NOW, live.getPubTime());
    }

    @Test
    void scheduledWithoutPubTimeIsFlagged() {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(PubStatus.SCHEDULED);
        assertTrue(e.lacksScheduleTime());
        e.setPubTime(NOW);
        assertFalse(e.lacksScheduleTime());
        e.setPubTime(null);
        e.setStatus(PubStatus.DRAFT);
        assertFalse(e.lacksScheduleTime());
    }

    @Test
    void publishedMoreThanAMinuteAheadBecomesScheduled() {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(PubStatus.PUBLISHED);
        e.setPubTime(new Timestamp(NOW.getTime() + 61_000L));
        e.scheduleIfFuture(NOW);
        assertEquals(PubStatus.SCHEDULED, e.getStatus());
    }

    @Test
    void publishedWithinAMinuteStaysPublished() {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(PubStatus.PUBLISHED);
        e.setPubTime(new Timestamp(NOW.getTime() + 59_000L));
        e.scheduleIfFuture(NOW);
        assertEquals(PubStatus.PUBLISHED, e.getStatus());
    }

    @Test
    void aDraftInTheFutureStaysADraft() {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(PubStatus.DRAFT);
        e.setPubTime(new Timestamp(NOW.getTime() + 3_600_000L));
        e.scheduleIfFuture(NOW);
        assertEquals(PubStatus.DRAFT, e.getStatus());
    }

    @Test
    void trashAndRestoreStampAndClear() {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(PubStatus.PUBLISHED);
        e.moveToTrash(NOW);
        assertEquals(PubStatus.TRASHED, e.getStatus());
        assertSame(NOW, e.getTrashedAt());
        e.restoreFromTrash();
        assertEquals(PubStatus.DRAFT, e.getStatus());
        assertNull(e.getTrashedAt());
    }
}
