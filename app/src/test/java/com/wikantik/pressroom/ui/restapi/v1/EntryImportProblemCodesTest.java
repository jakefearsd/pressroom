package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.ImageKind;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.field;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.fieldCodes;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The refusals of an entry write's import step carry their own codes, with
 * the English they always had (spec 2026-11-20-api-contract-design, Task 2).
 * The set-up is {@code EntriesWriteApiImportsTest}'s.
 */
class EntryImportProblemCodesTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final String A = "https://img.example.com/a.jpg";
    private static final String B = "https://img.example.com/b.jpg";

    private EntriesWriteApiFixture fx;
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
    private final Clock clock = new Clock();
    private MockedStatic<WebloggerRuntimeConfig> runtime;
    private MediaFileDirectory directory;

    /** A clock a test moves by hand. */
    static final class Clock extends java.time.Clock {
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
        when(fetcher.fetch(eq(url), anyLong(), any())).thenReturn(new FetchedImage("x.jpg", ImageKind.JPEG, JPEG.clone()));
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

    private JsonNode create(String body) throws Exception {
        return fx.refused(post("/v1/weblogs/myblog/entries").content(body));
    }

    @Test
    void withUploadsOffAnImportIsUploadDisabledOnItsField() throws Exception {
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(false);

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"importExternalImages\":true}"),
                "importExternalImages", "upload.disabled", "File upload is disabled for this site.");
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"featuredImageUrl\":\"" + A + "\"}"),
                "featuredImageUrl", "upload.disabled", "File upload is disabled for this site.");
    }

    @Test
    void anAltWithNoUrlIsImageAltWithoutUrl() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"featuredImageAlt\":\"x\"}"), "featuredImageAlt",
                "image.altWithoutUrl", "featuredImageAlt applies only with featuredImageUrl.");
    }

    @Test
    void aBlankUrlWithAnIdIsFieldBlankAndImageUrlAndId() throws Exception {
        JsonNode problem = create("{\"title\":\"t\",\"text\":\"x\",\"featuredImageUrl\":\"\","
                + "\"featuredImageId\":\"m1\"}");

        assertEquals(List.of("featuredImageId=image.notPublic", "featuredImageUrl=field.blank",
                "featuredImageUrl=image.urlAndId"), fieldCodes(problem));
        assertEquals("request.invalidFields", problem.get("code").asString());
        field(problem, "featuredImageUrl", "field.blank", "featuredImageUrl cannot be blank.");
    }

    @Test
    void aUrlWithTheIdClearedIsImageUrlAndClear() throws Exception {
        stored("x");

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1")
                .content("{\"featuredImageUrl\":\"" + A + "\",\"clear\":[\"featuredImageId\"]}"));

        oneField(problem, "featuredImageUrl", "image.urlAndClear",
                "featuredImageUrl sets the featured image; do not also clear featuredImageId.");
    }

    @Test
    void elevenImagesAreImageTooManyBodyImages() throws Exception {
        StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            eleven.append("![x](https://img.example.com/").append(i).append(".jpg) ");
        }

        oneField(create("{\"title\":\"t\",\"text\":\"" + eleven + "\",\"importExternalImages\":true}"), "text",
                "image.tooManyBodyImages",
                "The text loads 11 images from other sites; at most 10 are imported in one write.");
    }

    @Test
    void anExternalSrcsetIsImageScanRefused() throws Exception {
        String tag = "<img src=\\\"https://a.example/x.jpg\\\" srcset=\\\"https://a.example/x2.jpg 2x\\\">";

        JsonNode problem = create("{\"title\":\"t\",\"text\":\"" + tag + "\",\"importExternalImages\":true}");

        oneField(problem, "text", "image.scanRefused", "srcset is not imported; use one src, or [image id=...] "
                + "for a responsive image: <img src=\"https://a.example/x.jpg\" srcset=\"https://a.example/x2.jpg 2x\">");
    }

    @Test
    void aPrivateDefaultDirectoryIsImageDirectoryPrivate() throws Exception {
        directory.setPrivate(true);
        serves(A);

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"featuredImageUrl\":\"" + A + "\"}"), "featuredImageUrl",
                "image.directoryPrivate",
                "The weblog's default media directory is private, so readers could not see imported images.");
    }

    @Test
    void anImageTheImportRefusesIsImageImportFailedWithItsMessage() throws Exception {
        stored("![a](" + A + ") ![b](" + B + ")");
        serves(A);
        when(fetcher.fetch(eq(B), anyLong(), any())).thenThrow(new FetchRefusal("Not a JPEG, PNG, GIF or WebP image."));

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1")
                .content("{\"importExternalImages\":true}"));

        oneField(problem, "text", "image.importFailed", "Image " + B + ": Not a JPEG, PNG, GIF or WebP image.");
    }

    @Test
    void anImageTheBudgetRanOutBeforeIsImageImportBudgetExhausted() throws Exception {
        stored("![a](" + A + ") ![b](" + B + ")");
        when(fetcher.fetch(eq(A), anyLong(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(61));
            return new FetchedImage("a.jpg", ImageKind.JPEG, JPEG.clone());
        });

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1")
                .content("{\"importExternalImages\":true}"));

        field(problem, "text", "image.importBudgetExhausted",
                "Image " + B + " was not fetched: the import step's 60-second budget ran out.");
    }
}
