package com.wikantik.pressroom.business.search.lucene;

import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link EntryRenderer} stand-in for index tests that run on a mocked
 * tier: the "rendered" text is the entry's source, so the words a test puts
 * in an entry are the words the index sees.
 */
final class PassthroughRenderer {

    private PassthroughRenderer() {
    }

    /** Gives the mocked tier a passthrough renderer. */
    static void install(Weblogger pressroom) {
        EntryRenderer renderer = create();
        when(pressroom.getEntryRenderer()).thenReturn(renderer);
    }

    private static EntryRenderer create() {
        EntryRenderer renderer = mock(EntryRenderer.class);
        when(renderer.transformedText(any(WeblogEntry.class))).thenAnswer(inv -> {
            WeblogEntry entry = inv.getArgument(0);
            return entry.getText();
        });
        return renderer;
    }
}
