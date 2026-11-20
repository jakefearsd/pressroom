package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.dto.AuditDtos;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Entry titles are stored HTML-escaped but speak plain text over the API, in
 * both directions: every DTO that carries an entry title unescapes it, and a
 * write escapes it once. A GET followed by a PATCH of the same value must
 * leave the stored title byte-for-byte unchanged.
 */
class EntryTitlePlainTextTest {

    private static final String STORED = "Tom &amp; Jerry &lt;3 &quot;x&quot;";
    private static final String PLAIN = "Tom & Jerry <3 \"x\"";

    private static WeblogEntry entry(String storedTitle) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setAnchor("tom");
        entry.setTitle(storedTitle);
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);
        return entry;
    }

    @Test
    void theEntryViewIsPlainText() {
        assertEquals(PLAIN, EntryDtos.toView(entry(STORED), null).title());
    }

    @Test
    void aNullTitleStaysNull() {
        assertNull(EntryDtos.toView(entry(null), null).title());
    }

    @Test
    void aGetThenPatchOfTheSameTitleLeavesTheStoredTitleUnchanged() {
        Weblog weblog = new Weblog();
        weblog.setTimeZone("UTC");
        // An author who typed the literal text "&amp;" is stored as "&amp;amp;".
        for (String stored : List.of(STORED, "A &amp; B &lt; C &gt; D &quot;E&quot; F's", "Fish &amp;amp; Chips",
                "plain")) {
            WeblogEntry entry = entry(stored);
            String viewed = EntryDtos.toView(entry, null).title();

            EntryDtos.applyWrite(entry, EntryWrites.write().title(viewed).build(), weblog, Set.of());

            assertEquals(stored, entry.getTitle(), "round trip of " + stored);
        }
        assertEquals("Fish &amp; Chips", EntryDtos.toView(entry("Fish &amp;amp; Chips"), null).title());
    }

    @Test
    void anEntryRevisionSummaryAndViewArePlainText() {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setTitle(STORED);
        assertEquals(PLAIN, RevisionDtos.toSummary(revision).title());
        assertEquals(PLAIN, RevisionDtos.toView(revision, "").title());
    }

    @Test
    void theSeoRowsAreMarkedUpAsPlainText() {
        assertEquals(PLAIN, AuditDtos.toSeoGap(entry(STORED)).title());
        assertEquals(PLAIN, AuditDtos.toSeoEntryAudit(entry(STORED), List.of()).title());
    }

    @Test
    void aStrandPartIsPlainText() {
        PageDtos.PageView view = PageDtos.toView(new WeblogPage(), List.of(entry(STORED)));
        assertEquals(PLAIN, view.parts().get(0).title());
    }
}
