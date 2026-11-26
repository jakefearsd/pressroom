package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The body checks a preview adds: which of the weblog's own images a plain {@code img} should have used [image] for. */
class SeoBodiesTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final Weblog weblog = new Weblog();
    private final WeblogEntry entry = new WeblogEntry();

    @BeforeEach
    void setUp() throws Exception {
        weblog.setId("w1");
        weblog.setHandle("myblog");
        entry.setAnchor("post");
        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getMediaFileURL(eq(weblog), eq(""), eq(false))).thenReturn("/myblog/mediaresource/");
        when(urls.getMediaFileURL(eq(weblog), eq(""), eq(true))).thenReturn("https://blog.example.com/myblog/mediaresource/");
        when(urls.getWeblogURL(any(), any(), anyBoolean())).thenReturn("https://blog.example.com/myblog/");
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getMediaFileManager()).thenReturn(media);
        when(weblogger.getWeblogEntryManager()).thenReturn(mock(WeblogEntryManager.class));
    }

    private List<String> warningsFor(int width, String src) throws Exception {
        MediaFile file = new MediaFile();
        file.setId("m1");
        file.setName("pier.jpg");
        file.setContentType("image/jpeg");
        file.setWidth(width);
        file.setWeblog(weblog);
        when(media.getMediaFile("m1")).thenReturn(file);
        return SeoBodies.warnings(weblogger, weblog, entry, "<p><img src=\"" + src + "\" alt=\"pier\"></p>");
    }

    /** A file no wider than the narrowest rung (480) has no resized copy to offer, so nothing is said. */
    @Test
    void aPlainImgOfAFileWiderThanTheNarrowestRungIsToldToUseTheImageShortcode() throws Exception {
        List<String> narrow = warningsFor(480, "/myblog/mediaresource/m1");
        List<String> wide = warningsFor(481, "/myblog/mediaresource/m1");

        assertEquals(narrow.size() + 1, wide.size(), narrow + " vs " + wide);
    }

    @Test
    void anImageIdWithAPathOrNothingAfterThePrefixIsNotOneOfTheWeblogsFiles() throws Exception {
        assertEquals(warningsFor(480, "/elsewhere/m1"), warningsFor(2000, "/elsewhere/m1"));
        assertEquals(warningsFor(480, "/myblog/mediaresource/"), warningsFor(2000, "/myblog/mediaresource/"));
        assertEquals(warningsFor(480, "/myblog/mediaresource/m1/extra"), warningsFor(2000, "/myblog/mediaresource/m1/extra"));
    }
}
