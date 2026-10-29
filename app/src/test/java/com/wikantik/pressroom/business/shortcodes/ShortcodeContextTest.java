package com.wikantik.pressroom.business.shortcodes;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WeblogEntry satisfies ShortcodeContext by delegating to the accessors it
 * already had. This is what lets expand(entry, text) keep compiling at every
 * existing call site while handlers stop depending on WeblogEntry.
 */
class ShortcodeContextTest {

    @Test
    void aWeblogEntryIsAShortcodeContext() {
        Weblog weblog = new Weblog();
        weblog.setHandle("travelblog");

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hiking-in-spain");
        entry.setText("the pyrenees are extraordinary");

        ShortcodeContext context = entry;

        assertEquals(weblog, context.getWeblog(),
                "getWeblog must be the entry's own weblog");
        assertEquals("hiking-in-spain", context.getSlug(),
                "an entry's slug is its anchor");
        assertEquals("the pyrenees are extraordinary", context.getRawText(),
                "raw text is the pre-expansion source MapPins and FaqBlocks re-parse");
    }

    @Test
    void anEntryWithNothingSetReportsNullsRatherThanThrowing() {
        ShortcodeContext context = new WeblogEntry();

        assertNull(context.getWeblog());
        assertNull(context.getSlug());
        assertNull(context.getRawText());
    }
}
