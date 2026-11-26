package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.CropRefusedException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.fieldCodes;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /v1/weblogs/{handle}/media/{id}/crop: the editor's crop, for an agent. */
class MediaCropApiTest {

    private static final String CROP = "/v1/weblogs/myblog/media/{id}/crop";

    private Weblogger weblogger;
    private MediaFileManager files;
    private Weblog weblog;
    private MediaFile file;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> "https://example.invalid/media/" + inv.getArgument(1));
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        MediaFileDirectory dir = new MediaFileDirectory(weblog, "default", null);
        file = new MediaFile();
        file.setId("hawk-id");
        file.setName("hawk.jpg");
        file.setDirectory(dir);
        file.setContentType("image/jpeg");
        file.setWidth(500);
        file.setHeight(373);
        when(files.getMediaFile("hawk-id")).thenReturn(file);
        MediaApi controller = new MediaApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private ResultActions crop(String id, String body) throws Exception {
        return mvc.perform(post(CROP, id).requestAttr("actionWeblog", weblog)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void neverCropped() throws Exception {
        verify(files, never()).cropMediaFile(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /** The manager's crop rewrites the entity's size, as the real one does. */
    private void managerCropsTo(int width, int height) throws Exception {
        doAnswer(inv -> {
            MediaFile target = inv.getArgument(1);
            target.setWidth(width);
            target.setHeight(height);
            target.setBlurhash("cropped-blurhash");
            return null;
        }).when(files).cropMediaFile(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void aCropAsksTheManagerForTheRectangleAndAnswersTheFileAfterwards() throws Exception {
        managerCropsTo(488, 300);

        JsonNode view = json(crop("hawk-id", "{\"x\":5,\"y\":5,\"width\":488,\"height\":300}")
                .andExpect(status().isOk()));

        verify(files).cropMediaFile(weblog, file, 5, 5, 488, 300);
        verify(weblogger).flush();
        assertEquals(488, view.get("width").asInt());
        assertEquals(300, view.get("height").asInt());
        assertEquals("cropped-blurhash", view.get("blurhash").asString());
        assertEquals("hawk-id", view.get("id").asString());
    }

    @Test
    void expectedSizesThatMatchLetTheCropRun() throws Exception {
        managerCropsTo(100, 100);
        crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":100,\"height\":100,\"expectedWidth\":500,\"expectedHeight\":373}")
                .andExpect(status().isOk());
        verify(files).cropMediaFile(weblog, file, 0, 0, 100, 100);
    }

    @Test
    void anExpectedSizeThatDoesNotMatchIsAConflictThatGivesTheCurrentSizeAndNothingChanges() throws Exception {
        JsonNode body = json(crop("hawk-id",
                "{\"x\":0,\"y\":0,\"width\":100,\"height\":100,\"expectedWidth\":900,\"expectedHeight\":373}")
                .andExpect(status().isConflict()));
        problem(body, "media.cropStale",
                "This image is now 500 by 373 pixels, not the size you sent; read it again before cropping.");
        neverCropped();
        verify(weblogger, never()).flush();
    }

    @Test
    void anExpectedHeightAloneIsCheckedToo() throws Exception {
        crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":100,\"height\":100,\"expectedHeight\":10}")
                .andExpect(status().isConflict());
        neverCropped();
    }

    @Test
    void missingRectangleFieldsAreNamedTogetherAndNothingReachesTheManager() throws Exception {
        JsonNode body = json(crop("hawk-id", "{}").andExpect(status().isBadRequest()));
        assertEquals(List.of("x=field.required", "y=field.required", "width=field.required",
                "height=field.required"), fieldCodes(body));
        neverCropped();
    }

    @Test
    void aWidthOrHeightBelowOneIsRefusedOnThatField() throws Exception {
        JsonNode width = json(crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":0,\"height\":10}")
                .andExpect(status().isBadRequest()));
        oneField(width, "width", "media.cropSizeInvalid", "width must be at least 1.");
        JsonNode height = json(crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":10,\"height\":-4}")
                .andExpect(status().isBadRequest()));
        oneField(height, "height", "media.cropSizeInvalid", "height must be at least 1.");
        neverCropped();
    }

    @Test
    void aFileThatCannotBeCroppedIsAConflictNamingItsTypeAndTheTypesThatCan() throws Exception {
        file.setContentType("image/gif");
        doThrow(new CropRefusedException("no re-encode path", CropRefusedException.Reason.NOT_CROPPABLE, 0, 0, null))
                .when(files).cropMediaFile(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());

        JsonNode body = json(crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":10,\"height\":10}")
                .andExpect(status().isConflict()));

        problem(body, "media.notCroppable", "Only JPEG and PNG images can be cropped; this file is image/gif.");
        verify(weblogger, never()).flush();
    }

    @Test
    void aRectangleOffTheImageIsABadRequestNamingTheRectangleAndTheImageSize() throws Exception {
        doThrow(new CropRefusedException("off", CropRefusedException.Reason.OUTSIDE_IMAGE, 500, 373, null))
                .when(files).cropMediaFile(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());

        JsonNode body = json(crop("hawk-id", "{\"x\":600,\"y\":400,\"width\":100,\"height\":50}")
                .andExpect(status().isBadRequest()));

        problem(body, "media.cropOutside",
                "The crop rectangle (100 by 50 at 600, 400) does not overlap the 500 by 373 image.");
    }

    @Test
    void aStorageFailureIsTheOpaqueInternalErrorNeverA400OrA409() throws Exception {
        doThrow(new WebloggerException("Error cropping media file /var/data/secret", new java.io.IOException("disk")))
                .when(files).cropMediaFile(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());

        JsonNode body = json(crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":10,\"height\":10}")
                .andExpect(status().isInternalServerError()));

        assertEquals("server.internalError", body.get("code").asString());
        assertFalse(body.toString().contains("secret"), body.toString());
    }

    @Test
    void anotherWeblogsFileAndAnUnknownIdAreTheSame404AndTheManagerIsNeverAsked() throws Exception {
        Weblog other = new Weblog();
        other.setId("other-id");
        other.setHandle("other");
        MediaFile foreign = new MediaFile();
        foreign.setId("foreign-id");
        foreign.setDirectory(new MediaFileDirectory(other, "default", null));
        when(files.getMediaFile("foreign-id")).thenReturn(foreign);
        String rectangle = "{\"x\":0,\"y\":0,\"width\":10,\"height\":10}";

        JsonNode foreignBody = json(crop("foreign-id", rectangle).andExpect(status().isNotFound()));
        JsonNode unknownBody = json(crop("nope", rectangle).andExpect(status().isNotFound()));

        problem(foreignBody, "media.notFound", "No such media file.");
        problem(unknownBody, "media.notFound", "No such media file.");
        neverCropped();
    }

    @Test
    void aOneByOneRectangleIsTheSmallestCropAndIsNotRefusedAsTooSmall() throws Exception {
        managerCropsTo(1, 1);

        crop("hawk-id", "{\"x\":0,\"y\":0,\"width\":1,\"height\":1}").andExpect(status().isOk());

        verify(files).cropMediaFile(weblog, file, 0, 0, 1, 1);
    }
}
