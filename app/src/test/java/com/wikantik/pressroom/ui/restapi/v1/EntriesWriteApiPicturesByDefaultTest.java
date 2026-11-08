package com.wikantik.pressroom.ui.restapi.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;

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
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;

/**
 * Spec 2026-11-07 (pictures by default): the first picture is the featured
 * image (criteria 2-5, 7) and outside images are imported unless the write
 * opts out (criteria 7-11).
 */
class EntriesWriteApiPicturesByDefaultTest {

    private static final String MINE = "https://blog.example.com/myblog/mediaresource/";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private EntriesWriteApiFixture fx;
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
    private MockedStatic<WebloggerRuntimeConfig> runtime;
    private MediaFileDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        fx.controller.imageFetcher = fetcher;
        when(fx.weblogger.getMediaFileManager()).thenReturn(media);
        directory = new MediaFileDirectory(fx.weblog, "default", null);
        when(media.getDefaultMediaFileDirectory(fx.weblog)).thenReturn(directory);
        when(fx.urls.getWeblogURL(fx.weblog, null, true)).thenReturn("https://blog.example.com/myblog/");
        when(fx.urls.getMediaFileURL(eq(fx.weblog), any(String.class), eq(true)))
                .thenAnswer(inv -> MINE + inv.getArgument(1));
        runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(true);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        runtime.when(WebloggerRuntimeConfig::getAbsoluteContextURL).thenReturn("https://blog.example.com");

        Weblog other = new Weblog();
        other.setId("other-id");
        MediaFileDirectory hidden = new MediaFileDirectory(fx.weblog, "hidden", null);
        hidden.setPrivate(true);
        image("m1", directory);
        image("m2", directory);
        image("private", hidden);
        image("foreign", new MediaFileDirectory(other, "default", null));
    }

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    private void image(String id, MediaFileDirectory in) throws Exception {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setDirectory(in);
        file.setWeblog(in.getWeblog());
        file.setContentType("image/jpeg");
        when(media.getMediaFile(id)).thenReturn(file);
    }

    private void serves(String url) throws Exception {
        byte[] bytes = Arrays.copyOf(JPEG, JPEG.length + 1);
        bytes[bytes.length - 1] = (byte) url.hashCode();
        when(fetcher.fetch(eq(url), anyLong(), any())).thenReturn(new FetchedImage("a.jpg", ImageKind.JPEG, bytes));
    }

    private JsonNode create(String fields) throws Exception {
        return json(fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\"," + fields + "}"))
                .andExpect(status().isCreated()));
    }

    private WeblogEntry stored(String featured, String text) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(fx.weblog);
        entry.setTitle("Harbour");
        entry.setText(text);
        entry.setFeaturedImageId(featured);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(entry);
        return entry;
    }

    // ---- the first picture is the featured image ----

    @Test
    void criterion2TheFirstPictureBecomesTheFeaturedImage() throws Exception {
        JsonNode view = create("\"text\":\"Intro\\n\\n![Pier](" + MINE + "m1)\\n\\n[image id=\\\"m2\\\"]\"");

        assertEquals("m1", view.get("featuredImageId").asString());
    }

    @Test
    void criterion3AFeaturedImageSetExplicitlyWins() throws Exception {
        JsonNode view = create("\"text\":\"![Pier](" + MINE + "m1)\",\"featuredImageId\":\"m2\"");

        assertEquals("m2", view.get("featuredImageId").asString());
    }

    @Test
    void criterion4AnUpdateThatClearsTheFeaturedImageIsLeftWithNone() throws Exception {
        WeblogEntry entry = stored("m2", "![Pier](" + MINE + "m1)");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"clear\":[\"featuredImageId\"]}"))
                .andExpect(status().isOk());

        assertEquals(null, entry.getFeaturedImageId());
    }

    @Test
    void anUpdateOfAPostWithNoFeaturedImageGivesItOne() throws Exception {
        WeblogEntry entry = stored(null, "![Pier](" + MINE + "m1)");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"summary\":\"new\"}")).andExpect(status().isOk());

        assertEquals("m1", entry.getFeaturedImageId());
    }

    @Test
    void criterion5APictureNoReaderCouldLoadIsSkippedForTheNext() throws Exception {
        JsonNode view = create("\"text\":\"[image id=\\\"foreign\\\"] [image id=\\\"private\\\"] "
                + "[image id=\\\"missing\\\"] [image id=\\\"m2\\\"]\"");

        assertEquals("m2", view.get("featuredImageId").asString());
    }

    @Test
    void criterion5WithNoUsablePictureThereIsNoFeaturedImage() throws Exception {
        JsonNode view = create("\"text\":\"[image id=\\\"foreign\\\"] no pictures of ours\"");

        assertTrue(view.get("featuredImageId") == null || view.get("featuredImageId").isNull(), view.toString());
    }

    // ---- outside images are imported unless the write opts out ----

    @Test
    void criterion7AnOutsideImageIsImportedWithoutBeingAskedAndBecomesTheFeaturedImage() throws Exception {
        serves("https://raw.example.org/pier.jpg");

        JsonNode view = create("\"text\":\"Intro\\n\\n![Pier](https://raw.example.org/pier.jpg)\\n\\nMore\"");

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(media).createMediaFile(eq(fx.weblog), created.capture(), any());
        String copy = created.getValue().getId();
        assertFalse(view.get("text").asString().contains("raw.example.org"), view.get("text").asString());
        assertEquals(copy, view.get("featuredImageId").asString());
    }

    @Test
    void criterion8ImportExternalImagesFalseLeavesTheTextAsSent() throws Exception {
        JsonNode view = create("\"text\":\"![Pier](https://raw.example.org/pier.jpg)\",\"importExternalImages\":false");

        verify(fetcher, never()).fetch(any(), anyLong(), any());
        assertEquals("![Pier](https://raw.example.org/pier.jpg)", view.get("text").asString());
    }

    @Test
    void criterion9AnUpdateSendingNoTextImportsNothing() throws Exception {
        stored(null, "![Pier](https://raw.example.org/pier.jpg)");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"summary\":\"new\"}")).andExpect(status().isOk());

        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void anEditThatAddsAnOutsideImageImportsIt() throws Exception {
        serves("https://raw.example.org/pier.jpg");
        WeblogEntry entry = stored(null, "Intro PLACE");

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"edits\":[{\"find\":\"PLACE\",\"replace\":\"![Pier](https://raw.example.org/pier.jpg)\"}]}"))
                .andExpect(status().isOk());

        assertFalse(entry.getText().contains("raw.example.org"), entry.getText());
    }

    @Test
    void criterion10DefaultedWithUploadsOffTheWriteSavesAsSent() throws Exception {
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(false);

        JsonNode view = create("\"text\":\"![Pier](https://raw.example.org/pier.jpg)\"");

        verify(fetcher, never()).fetch(any(), anyLong(), any());
        assertEquals("![Pier](https://raw.example.org/pier.jpg)", view.get("text").asString());
    }

    @Test
    void criterion10AskedForWithUploadsOffIsStillA400() throws Exception {
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(false);

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"![Pier](https://raw.example.org/pier.jpg)\",\"importExternalImages\":true}"));

        assertEquals(List.of("importExternalImages"), fields(problem));
    }

    @Test
    void defaultedWithAPrivateDefaultDirectoryTheWriteSavesAsSent() throws Exception {
        directory.setPrivate(true);

        JsonNode view = create("\"text\":\"![Pier](https://raw.example.org/pier.jpg)\"");

        verify(fetcher, never()).fetch(any(), anyLong(), any());
        assertEquals("![Pier](https://raw.example.org/pier.jpg)", view.get("text").asString());
    }

    /**
     * Criterion 11: a default must not make a write fail that would have
     * saved before it existed. A copy that fails leaves that image linked
     * as it was; the rest are copied, and the write saves. (Asked for with
     * true, one failure still refuses the write: EntriesWriteApiImportsTest.)
     */
    @Test
    void criterion11DefaultedAFailingImageStaysLinkedAndTheWriteSaves() throws Exception {
        String failing = "https://maps.example.com/static.png";
        when(fetcher.fetch(eq(failing), anyLong(), any())).thenThrow(new FetchRefusal("403 Forbidden."));
        serves("https://raw.example.org/pier.jpg");

        JsonNode view = create("\"text\":\"![Map](" + failing + ")\\n\\n![Pier](https://raw.example.org/pier.jpg)\"");

        String text = view.get("text").asString();
        assertTrue(text.contains(failing), text);
        assertFalse(text.contains("raw.example.org"), text);
        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(media).createMediaFile(eq(fx.weblog), created.capture(), any());
        assertEquals(created.getValue().getId(), view.get("featuredImageId").asString());
    }

    @Test
    void defaultedAnExternalSrcsetOrMoreThanTenImagesAreNoRefusal() throws Exception {
        StringBuilder text = new StringBuilder("<img src=\\\"https://a.example.org/x.jpg\\\" "
                + "srcset=\\\"https://a.example.org/x.jpg 1x\\\">");
        for (int i = 0; i < 11; i++) {
            serves("https://raw.example.org/" + i + ".jpg");
            text.append(" ![p").append(i).append("](https://raw.example.org/").append(i).append(".jpg)");
        }
        serves("https://a.example.org/x.jpg");

        create("\"text\":\"" + text + "\"");

        verify(fetcher, org.mockito.Mockito.times(EntryImports.MAX_BODY_IMAGES)).fetch(any(), anyLong(), any());
    }
}
