package com.wikantik.pressroom.business;

import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What the search index does for each kind of committed entry write. */
class EntryIndexerTest {

    private final IndexManager index = mock(IndexManager.class);
    private final Weblogger weblogger = mock(Weblogger.class);
    private final EntryIndexer indexer = new EntryIndexer(weblogger);
    private final WeblogEntry entry = new WeblogEntry();

    EntryIndexerTest() {
        when(weblogger.getIndexManager()).thenReturn(index);
    }

    @Test
    void aPublishedEntryIsReindexed() throws Exception {
        indexer.entryChanged(new EntryChanged(entry, PubStatus.DRAFT, PubStatus.PUBLISHED, false));
        verify(index).addEntryReIndexOperation(entry);
        verify(index, never()).removeEntryIndexOperation(any());
    }

    @Test
    void anEntryThatLeftPublishedIsRemoved() throws Exception {
        indexer.entryChanged(new EntryChanged(entry, PubStatus.PUBLISHED, PubStatus.TRASHED, false));
        verify(index).removeEntryIndexOperation(entry);
        verify(index, never()).addEntryReIndexOperation(any());
    }

    @Test
    void aRemovedPublishedEntryIsRemoved() throws Exception {
        indexer.entryChanged(new EntryChanged(entry, PubStatus.PUBLISHED, null, true));
        verify(index).removeEntryIndexOperation(entry);
    }

    /** The weblog's own index removal takes the entry out with all the others, once its delete commits. */
    @Test
    void anEntryRemovedWithItsWeblogIsLeftToTheWeblogsIndexRemoval() throws Exception {
        indexer.entryChanged(new EntryChanged(entry, PubStatus.PUBLISHED, null, true, true));
        verifyNoInteractions(index);
    }

    @Test
    void aWriteThatNeverTouchedAPublishedEntryLeavesTheIndexAlone() throws Exception {
        indexer.entryChanged(new EntryChanged(entry, PubStatus.DRAFT, PubStatus.PENDING, false));
        indexer.entryChanged(new EntryChanged(entry, null, PubStatus.DRAFT, false));
        indexer.entryChanged(new EntryChanged(entry, PubStatus.TRASHED, null, true));
        verify(index, never()).addEntryReIndexOperation(any());
        verify(index, never()).removeEntryIndexOperation(any());
    }

    @Test
    void theCacheInvalidatorTouchesNoIndex() throws Exception {
        entry.setAnchor("x");
        new EntryCacheInvalidator().entryChanged(new EntryChanged(entry, null, PubStatus.DRAFT, false));
        verifyNoInteractions(index);
    }
}
