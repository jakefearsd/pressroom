package com.wikantik.pressroom.business.search.lucene;

import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the index sees of a post: the words a reader sees, not the markup that produced them. */
class SearchableTextTest {

    private EntryRenderer renderer;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");
        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");

        PluginManager pluginManager = mock(PluginManager.class);
        MediaFileManager mediaFiles = mock(MediaFileManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);
        when(weblogger.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getWeblogEntryPlugins(weblog)).thenReturn(java.util.Map.of());
        try {
            MediaFile photo = new MediaFile();
            photo.setId("mf-1");
            photo.setWeblog(weblog);
            photo.setName("hawk.jpg");
            photo.setContentType("image/jpeg");
            photo.setWidth(1200);
            photo.setHeight(800);
            when(mediaFiles.getMediaFile("mf-1")).thenReturn(photo);
            URLStrategy urls = mock(URLStrategy.class);
            when(urls.getMediaFileURL(weblog, "mf-1", true)).thenReturn("http://example.com/f");
            when(weblogger.getUrlStrategy()).thenReturn(urls);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        renderer = new EntryRenderer(ShortcodeExpander.builtIn(weblogger, mediaFiles), pluginManager);
    }

    @Test
    void markdownAndShortcodeSourceAreNotInTheText() {
        entry.setText("## Heading\n\n**bold** [image id=mf-1]");
        String text = SearchableText.of(entry, renderer);
        assertTrue(text.contains("Heading"), text);
        assertTrue(text.contains("bold"), text);
        assertFalse(text.contains("#"), text);
        assertFalse(text.contains("*"), text);
        assertFalse(text.contains("[image"), text);
    }

    @Test
    void shortcodeOutputTextIsInTheText() {
        entry.setText("[faq]\n[q]Best month?[/q]\n[a]June, by far.[/a]\n[/faq]");
        String text = SearchableText.of(entry, renderer);
        assertTrue(text.contains("Best month?"), text);
        assertTrue(text.contains("June, by far."), text);
        assertFalse(text.contains("[q]"), text);
    }

    @Test
    void entitiesAreDecoded() {
        entry.setText("Fish &amp; chips");
        assertEquals("Fish & chips", SearchableText.of(entry, renderer));
    }

    @Test
    void postThatFailsToRenderIsStillIndexedByTitle() {
        EntryRenderer broken = mock(EntryRenderer.class);
        when(broken.transformedText(entry)).thenThrow(new IllegalStateException("boom"));
        assertEquals("", SearchableText.of(entry, broken));
    }

    @Test
    void nullTextGivesEmpty() {
        entry.setText(null);
        assertEquals("", SearchableText.of(entry, renderer));
    }
}
