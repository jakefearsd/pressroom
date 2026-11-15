package com.wikantik.pressroom.business.search.lucene;

import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link EntryRenderer} stand-in for index tests that run on a mocked
 * tier: the "rendered" text is the entry's (or page's) source, so the words a
 * test puts in an entry are the words the index sees. The tier also gets a
 * page manager with no pages, which a test stubs when it wants some: every
 * rebuild asks it for each blog's published pages.
 */
final class PassthroughRenderer {

    private PassthroughRenderer() {
    }

    /** Gives the mocked tier a passthrough renderer and an empty page manager. */
    static void install(Weblogger pressroom) {
        EntryRenderer renderer = create();
        when(pressroom.getEntryRenderer()).thenReturn(renderer);
        when(pressroom.getWeblogPageManager()).thenReturn(mock(WeblogPageManager.class));
    }

    private static EntryRenderer create() {
        EntryRenderer renderer = mock(EntryRenderer.class);
        when(renderer.transformedText(any(WeblogEntry.class))).thenAnswer(inv -> {
            WeblogEntry entry = inv.getArgument(0);
            return entry.getText();
        });
        when(renderer.pageContent(any(WeblogPage.class))).thenAnswer(inv -> {
            WeblogPage page = inv.getArgument(0);
            return page.getContent();
        });
        return renderer;
    }
}
