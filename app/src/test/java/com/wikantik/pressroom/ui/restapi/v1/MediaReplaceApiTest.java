package com.wikantik.pressroom.ui.restapi.v1;

import java.io.InputStream;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /v1/weblogs/{handle}/media/{id}/content: the media edit screen's "Replace file", which the JSP did by
 * posting a new file with the form. The quota and size checks are the upload's (canReplace counts the bytes
 * the new file overwrites).
 */
class MediaReplaceApiTest {

    private static final String CONTENT = "/v1/weblogs/myblog/media/{id}/content";

    private Weblogger weblogger;
    private MediaFileManager files;
    private FileContentManager content;
    private Weblog weblog;
    private MediaFile file;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        content = mock(FileContentManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        when(weblogger.getFileContentManager()).thenReturn(content);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> "https://example.invalid/media/" + inv.getArgument(1));
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        file = new MediaFile();
        file.setId("hawk-id");
        file.setName("hawk.jpg");
        file.setDirectory(new MediaFileDirectory(weblog, "default", null));
        file.setContentType("image/jpeg");
        file.setLength(10);
        when(files.getMediaFile("hawk-id")).thenReturn(file);
        when(content.canReplace(any(), any(), any(), any(), anyLong(), any())).thenReturn(true);
        MediaApi controller = new MediaApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private ResultActions replace(String id, MockMultipartFile... parts) throws Exception {
        var request = multipart(HttpMethod.PUT, CONTENT, id).requestAttr("actionWeblog", weblog);
        for (MockMultipartFile part : parts) {
            request.file(part);
        }
        return mvc.perform(request);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockMultipartFile upload(String name, String type, String bytes) {
        return new MockMultipartFile("file", name, type, bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void theNewBytesReplaceTheOldAndTheFileIsAnswered() throws Exception {
        JsonNode view = json(replace("hawk-id", upload("hawk-2.png", "image/png", "12345678")).andExpect(status().isOk()));

        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        verify(files).updateMediaFile(eq(weblog), eq(file), stream.capture());
        assertEquals("12345678", new String(stream.getValue().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        verify(weblogger).flush();
        assertEquals(8, file.getLength());
        assertEquals("image/png", file.getContentType());
        assertEquals("hawk.jpg", file.getName(), "the file keeps its name: only its content is replaced");
        assertEquals("hawk-id", view.get("id").asString());
        assertEquals(8, view.get("length").asInt());
    }

    @Test
    void aGenericTypeTakesTheOneTheFileNameImplies() throws Exception {
        replace("hawk-id", upload("x.bin", "application/octet-stream", "abc")).andExpect(status().isOk());

        assertEquals("image/jpeg", file.getContentType(), "hawk.jpg implies image/jpeg");
    }

    @Test
    void noFileOrAnEmptyOneIsAFieldRequiredProblem() throws Exception {
        oneField(json(replace("hawk-id").andExpect(status().isBadRequest())), "file", "media.fileRequired",
                "At least one file is required.");
        oneField(json(replace("hawk-id", upload("e.png", "image/png", "")).andExpect(status().isBadRequest())),
                "file", "media.fileRequired", "At least one file is required.");

        verify(files, never()).updateMediaFile(any(), any(), any(InputStream.class));
    }

    @Test
    void aRefusalOfTheUploadChecksIsAProblemWithTheReasonAndNothingChanges() throws Exception {
        doAnswer(inv -> {
            PressroomMessages messages = inv.getArgument(5);
            messages.addError("error.upload.filemax", new String[] {"hawk.jpg", "1"});
            return false;
        }).when(content).canReplace(any(), any(), any(), any(), anyLong(), any());

        JsonNode body = json(replace("hawk-id", upload("big.png", "image/png", "12345678"))
                .andExpect(status().isBadRequest()));

        problem(body, "media.replaceRefused",
                "The file was not replaced. This file is larger than the 1 MB per-file limit.");
        verify(files, never()).updateMediaFile(any(), any(), any(InputStream.class));
        assertEquals(10, file.getLength(), "the stored length is not touched by a refusal");
    }

    @Test
    void anUnknownFileIsA404() throws Exception {
        problem(json(replace("nope", upload("a.png", "image/png", "x")).andExpect(status().isNotFound())),
                "media.notFound", "No such media file.");
    }
}
