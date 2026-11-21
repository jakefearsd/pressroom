package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the media routes carries its own code, with the English it
 * always had (spec 2026-11-20-api-contract-design, Task 3). The English half
 * passed before Task 3 (characterisation); the code half is what it adds.
 */
class MediaProblemCodesTest {

    private static final String MEDIA = "/v1/weblogs/myblog/media";

    private MediaFileManager files;
    private RemoteImageFetcher fetcher;
    private Weblog weblog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setMediaFileDirectories(new ArrayList<>());
        MediaFileDirectory images = new MediaFileDirectory(weblog, "images", null);
        weblog.getMediaFileDirectories().add(images);
        when(files.getDefaultMediaFileDirectory(weblog)).thenReturn(new MediaFileDirectory(weblog, "default", null));
        fetcher = mock(RemoteImageFetcher.class);
        MediaApi controller = new MediaApi();
        controller.weblogger = weblogger;
        controller.imageFetcher = fetcher;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private JsonNode json(ResultMatcher status, MockHttpServletRequestBuilder request, String body) throws Exception {
        return answer(status, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockedStatic<WebloggerRuntimeConfig> uploads(boolean enabled) {
        MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(enabled);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        return runtime;
    }

    @Test
    void anUploadWithNoFileIsMediaFileRequired() throws Exception {
        String body = mvc.perform(multipart(MEDIA).requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        oneField(new ObjectMapper().readTree(body), "file", "media.fileRequired", "At least one file is required.");
    }

    @Test
    void theImportRefusalsHaveTheirCodes() throws Exception {
        oneField(json(status().isBadRequest(), post(MEDIA + "/imports"), "{}"),
                "url", "field.required", "url is required.");
        try (MockedStatic<WebloggerRuntimeConfig> _ = uploads(false)) {
            problem(json(status().isBadRequest(), post(MEDIA + "/imports"), "{\"url\":\"https://x.example/a.jpg\"}"),
                    "upload.disabled", "File upload is disabled for this site.");
        }
    }

    /** The import step's own refusal is passed through whole: the business tier's text is not a finite set. */
    @Test
    void aRefusedImportIsMediaImportRefused() throws Exception {
        when(fetcher.fetch(any(), anyLong(), any()))
                .thenThrow(new FetchRefusal("That address is not on the public internet."));
        try (MockedStatic<WebloggerRuntimeConfig> _ = uploads(true)) {
            oneField(json(status().isBadRequest(), post(MEDIA + "/imports"), "{\"url\":\"http://10.0.0.1/x.png\"}"),
                    "url", "media.importRefused", "That address is not on the public internet.");
        }
    }

    @Test
    void theDirectoryCreateRefusalsHaveTheirCodes() throws Exception {
        oneField(json(status().isBadRequest(), post(MEDIA + "/directories"), "{}"),
                "name", "field.required", "name is required.");
        oneField(json(status().isBadRequest(), post(MEDIA + "/directories"), "{\"name\":\"/\"}"),
                "name", "field.required", "name is required.");
        oneField(json(status().isBadRequest(), post(MEDIA + "/directories"), "{\"name\":\"default\"}"),
                "name", "directory.nameReserved", "'default' is a reserved directory name.");
        oneField(json(status().isConflict(), post(MEDIA + "/directories"), "{\"name\":\"images\"}"),
                "name", "directory.nameInUse", "A directory named 'images' already exists.");
    }

    @Test
    void anUnknownFileOrDirectoryHasItsCode() throws Exception {
        problem(answer(status().isNotFound(), get(MEDIA + "/nope")), "media.notFound", "No such media file.");
        problem(answer(status().isNotFound(), get(MEDIA).param("directoryId", "nope")),
                "directory.notFound", "No such directory.");
    }

    @Test
    void theFocalPointAndNameRefusalsOfAPatchHaveTheirCodes() throws Exception {
        MediaFile file = new MediaFile();
        file.setId("f1");
        file.setName("dog.jpg");
        file.setDirectory(new MediaFileDirectory(weblog, "default", null));
        when(files.getMediaFile("f1")).thenReturn(file);

        oneField(json(status().isBadRequest(), patch(MEDIA + "/f1"), "{\"focalX\":2.0,\"focalY\":0.5}"),
                "focalX", "focal.outOfRange", "focalX must be between 0 and 1.");
        oneField(json(status().isBadRequest(), patch(MEDIA + "/f1"), "{\"focalX\":0.5}"),
                "focalX", "focal.pairOnly", "focalX and focalY are set together; this file has no focal point yet.");
        oneField(json(status().isBadRequest(), patch(MEDIA + "/f1"), "{\"name\":\" \"}"),
                "name", "field.blank", "name cannot be blank.");
    }

    /**
     * A move alone (no new name) is judged on the name the file keeps: a folder
     * already holding that name refuses it, or two files would share one public
     * address. Nothing is updated or moved. Intended change (0.16.0): before,
     * a PATCH carrying only directoryId was never checked.
     */
    @Test
    void aMoveIntoAFolderHoldingTheFilesNameIsMediaNameInUse() throws Exception {
        MediaFile file = new MediaFile();
        file.setId("f1");
        file.setName("dog.jpg");
        file.setDirectory(new MediaFileDirectory(weblog, "default", null));
        when(files.getMediaFile("f1")).thenReturn(file);
        MediaFileDirectory images = weblog.getMediaFileDirectories().get(0);
        images.setId("images-id");
        MediaFile holder = new MediaFile();
        holder.setId("f2");
        holder.setName("dog.jpg");
        images.setMediaFiles(new HashSet<>(Set.of(holder)));
        when(files.getMediaFileDirectory("images-id")).thenReturn(images);

        oneField(json(status().isConflict(), patch(MEDIA + "/f1"), "{\"directoryId\":\"images-id\"}"),
                "name", "media.nameInUse", "A file named 'dog.jpg' already exists in that directory.");
        verify(files, never()).updateMediaFile(any(), any());
        verify(files, never()).moveMediaFile(any(), any());
    }
}
