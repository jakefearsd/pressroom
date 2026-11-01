package com.wikantik.pressroom.ui.restapi.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.ImageKind;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;

/** The import step of an entry write (spec 2026-10-13, sections 6 and 7; criteria 14, 17-20). */
class EntriesWriteApiImportsTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private EntriesWriteApiFixture fx;
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
    private final MutableClock clock = new MutableClock();
    private MockedStatic<WebloggerRuntimeConfig> runtime;
    private MediaFileDirectory directory;

    /** A clock a test moves by hand. */
    static final class MutableClock extends java.time.Clock {
        private Instant now = Instant.parse("2026-10-13T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        fx.controller.imageFetcher = fetcher;
        fx.controller.clock = clock;

        when(fx.weblogger.getMediaFileManager()).thenReturn(media);
        directory = new MediaFileDirectory(fx.weblog, "default", null);
        when(media.getDefaultMediaFileDirectory(fx.weblog)).thenReturn(directory);
        when(fx.urls.getWeblogURL(fx.weblog, null, true)).thenReturn("https://blog.example.com/myblog/");
        when(fx.urls.getMediaFileURL(eq(fx.weblog), any(String.class), eq(true)))
                .thenAnswer(inv -> "https://blog.example.com/myblog/mediaresource/" + inv.getArgument(1));
        // createMediaFile stays a no-op mock: the imported id becomes featuredImageId after the
        // checks ran, so nothing looks it up again
        runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(true);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        runtime.when(WebloggerRuntimeConfig::getAbsoluteContextURL).thenReturn("https://blog.example.com");
    }

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    private void serves(String url) throws Exception {
        String name = url.substring(url.lastIndexOf('/') + 1);
        // distinct bytes per URL, so no two URLs match each other by hash
        byte[] bytes = Arrays.copyOf(JPEG, JPEG.length + 1);
        bytes[bytes.length - 1] = (byte) url.hashCode();
        when(fetcher.fetch(eq(url), anyLong(), any())).thenReturn(new FetchedImage(name, ImageKind.JPEG, bytes));
    }

    @Test
    void aFeaturedImageUrlIsImportedAndBecomesTheFeaturedImage() throws Exception {
        serves("https://img.example.com/pier.jpg");

        JsonNode view = json(fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/pier.jpg\",\"featuredImageAlt\":\"The pier\"}"))
                .andExpect(status().isCreated()));

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(media).createMediaFile(eq(fx.weblog), created.capture(), any());
        assertEquals(created.getValue().getId(), view.get("featuredImageId").asString());
        assertEquals("The pier", created.getValue().getAltText());
    }

    @Test
    void featuredImageUrlWithFeaturedImageIdOrAltAloneIsA400WithNoFetch() throws Exception {
        fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\",\"featuredImageId\":\"m1\"}"));
        JsonNode altAlone = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"featuredImageAlt\":\"x\"}"));

        assertEquals(List.of("featuredImageAlt"), fields(altAlone));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void bodyImagesAreImportedAndTheTextPointsAtTheCopies() throws Exception {
        serves("https://img.example.com/a.jpg");
        serves("https://img.example.com/b.jpg");

        JsonNode view = json(fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"<img src=\\\"https://img.example.com/a.jpg\\\" alt=\\\"A\\\"> ![B](https://img.example.com/b.jpg)\","
                + "\"importExternalImages\":true}")).andExpect(status().isCreated()));

        String text = view.get("text").asString();
        assertFalse(text.contains("img.example.com"), text);
        assertTrue(text.contains("<img src=\"https://blog.example.com/myblog/mediaresource/"), text);
        assertTrue(text.contains("![B](https://blog.example.com/myblog/mediaresource/"), text);
    }

    /** An image on its own line is placed the way [image] places it, with resized copies. */
    @Test
    void aBodyImageOnItsOwnLineBecomesAnImageShortcodeForTheImportedFile() throws Exception {
        serves("https://img.example.com/b.jpg");

        JsonNode view = json(fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"Intro\\n\\n![The pier](https://img.example.com/b.jpg)\\n\\nMore\","
                + "\"importExternalImages\":true}")).andExpect(status().isCreated()));

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(media).createMediaFile(eq(fx.weblog), created.capture(), any());
        assertEquals("Intro\n\n[image id=\"" + created.getValue().getId() + "\" alt=\"The pier\"]\n\nMore",
                view.get("text").asString());
    }

    /** Review Focus 5. */
    @Test
    void aUrlUsedTwiceAndAsTheFeaturedImageIsFetchedOnce() throws Exception {
        serves("https://img.example.com/a.jpg");

        fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"<img src=\\\"https://img.example.com/a.jpg\\\"> ![x](https://img.example.com/a.jpg)\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\",\"importExternalImages\":true}"))
                .andExpect(status().isCreated());

        verify(fetcher, times(1)).fetch(eq("https://img.example.com/a.jpg"), anyLong(), any());
    }

    /** Final review I1: a URL that failed as the featured image is not fetched again as a body image. */
    @Test
    void aFailingUrlUsedAsFeaturedAndInTheBodyIsFetchedAndReportedOnce() throws Exception {
        String url = "https://img.example.com/a.jpg";
        when(fetcher.fetch(eq(url), anyLong(), any())).thenThrow(new FetchRefusal("Not a JPEG, PNG, GIF or WebP image."));

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"![x](" + url + ")\",\"featuredImageUrl\":\"" + url + "\","
                + "\"importExternalImages\":true}"));

        verify(fetcher, times(1)).fetch(eq(url), anyLong(), any());
        assertEquals(List.of("featuredImageUrl"), fields(problem));
    }

    /** Final review M7: the check just before the save catches an anchor taken during the import. */
    @Test
    void anAnchorTakenDuringTheImportIsA409AndNothingIsSaved() throws Exception {
        serves("https://img.example.com/a.jpg");
        WeblogEntry holder = new WeblogEntry();
        holder.setId("holder");
        holder.setWebsite(fx.weblog);
        holder.setAnchor("torrey-pines");
        holder.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "torrey-pines")).thenReturn(null, holder);

        JsonNode problem = json(fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"![x](https://img.example.com/a.jpg)\",\"anchor\":\"torrey-pines\","
                + "\"importExternalImages\":true}")).andExpect(status().isConflict()));

        assertEquals(List.of("anchor"), fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
        verify(media, times(1)).createMediaFile(any(), any(), any());
    }

    @Test
    void elevenImagesOrAnExternalSrcsetAreRefusedBeforeAnyFetch() throws Exception {
        StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            eleven.append("![x](https://img.example.com/").append(i).append(".jpg) ");
        }
        JsonNode tooMany = fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\""
                + eleven + "\",\"importExternalImages\":true}"));
        JsonNode srcset = fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":"
                + "\"<img src=\\\"https://a.example/x.jpg\\\" srcset=\\\"https://a.example/x2.jpg 2x\\\">\","
                + "\"importExternalImages\":true}"));

        assertEquals(List.of("text"), fields(tooMany));
        assertEquals(List.of("text"), fields(srcset));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void withUploadsOffTheImportIsRefusedOnItsField() throws Exception {
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(false);

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"importExternalImages\":true}"));

        assertEquals(List.of("importExternalImages"), fields(problem));
    }

    private WeblogEntry stored(String text) throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Harbour");
        stored.setText(text);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);
        return stored;
    }

    @Test
    void oneFailingImageRefusesTheWriteNamingItAndKeepsTheOthersImported() throws Exception {
        String text = "![a](https://img.example.com/a.jpg) ![b](https://img.example.com/b.jpg) "
                + "![c](https://img.example.com/c.jpg)";
        WeblogEntry entry = stored(text);
        serves("https://img.example.com/a.jpg");
        when(fetcher.fetch(eq("https://img.example.com/b.jpg"), anyLong(), any()))
                .thenThrow(new FetchRefusal("Not a JPEG, PNG, GIF or WebP image."));
        serves("https://img.example.com/c.jpg");

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"summary\":\"new\",\"importExternalImages\":true}"));

        assertEquals(List.of("text"), fields(problem));
        assertTrue(problem.get("errors").get(0).get("message").asString().contains("b.jpg"));
        assertEquals(text, entry.getText());
        assertNull(entry.getSummary());
        verify(media, times(2)).createMediaFile(any(), any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }

    @Test
    void whenTheBudgetRunsOutTheRestAreReportedNotFetched() throws Exception {
        stored("![a](https://img.example.com/a.jpg) ![b](https://img.example.com/b.jpg)");
        byte[] bytes = JPEG.clone();
        when(fetcher.fetch(eq("https://img.example.com/a.jpg"), anyLong(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(61));
            return new FetchedImage("a.jpg", ImageKind.JPEG, bytes);
        });

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"importExternalImages\":true}"));

        assertTrue(problem.get("errors").get(0).get("message").asString().contains("budget"));
        verify(fetcher, never()).fetch(eq("https://img.example.com/b.jpg"), anyLong(), any());
    }

    @Test
    void aPatchWithoutTextImportsFromTheStoredText() throws Exception {
        WeblogEntry entry = stored("Intro ![a](https://img.example.com/a.jpg)");
        serves("https://img.example.com/a.jpg");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"importExternalImages\":true}"))
                .andExpect(status().isOk());

        assertTrue(entry.getText().startsWith("Intro ![a](https://blog.example.com/myblog/mediaresource/"),
                entry.getText());
        verify(fx.entries).saveWeblogEntry(org.mockito.ArgumentMatchers.eq(entry), any(), any());
    }

    // ---- edits and expectedContentHash (spec 2026-10-24, sections 3 and 4; criterion 4)

    private static final String EDIT_ADDING_AN_IMAGE = "\"edits\":[{\"find\":\"Intro.\","
            + "\"replace\":\"Intro. ![a](https://img.example.com/a.jpg)\"}],\"importExternalImages\":true";

    /** What edits make of the stored text is imported exactly as sent text would be. */
    @Test
    void anImageAnEditIntroducesIsImported() throws Exception {
        WeblogEntry entry = stored("Intro.");
        serves("https://img.example.com/a.jpg");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{" + EDIT_ADDING_AN_IMAGE + "}"))
                .andExpect(status().isOk());

        assertTrue(entry.getText().startsWith("Intro. ![a](https://blog.example.com/myblog/mediaresource/"),
                entry.getText());
        verify(fx.entries, times(1)).saveWeblogEntry(eq(entry), any(), any());
    }

    @Test
    void aStaleExpectedContentHashIsA409BeforeAnythingIsFetched() throws Exception {
        WeblogEntry entry = stored("Intro.");
        serves("https://img.example.com/a.jpg");

        JsonNode problem = json(fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{" + EDIT_ADDING_AN_IMAGE
                + ",\"expectedContentHash\":\"" + "0".repeat(64) + "\"}")).andExpect(status().isConflict()));

        assertTrue(problem.get("type").asString().endsWith("/entry-changed"), problem.toString());
        assertEquals("Intro.", entry.getText());
        verify(fetcher, never()).fetch(any(), anyLong(), any());
        verify(media, never()).createMediaFile(any(), any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }

    @Test
    void aFailingEditRefusesTheWriteBeforeAnythingIsFetched() throws Exception {
        stored("Intro.");
        serves("https://img.example.com/a.jpg");

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content("{" + EDIT_ADDING_AN_IMAGE
                .replace("Intro.\",", "Nowhere.\",") + "}"));

        assertEquals(List.of("edits[0]"), fields(problem));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void aPrivateDefaultDirectoryRefusesTheImport() throws Exception {
        directory.setPrivate(true);
        serves("https://img.example.com/a.jpg");

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\"}"));

        assertEquals(List.of("featuredImageUrl"), fields(problem));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }
}
