package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Folder delete and the folder's private flag: the API twins of the Media Files screen's two folder actions. */
class MediaFoldersApiTest {

    private static final String DIRS = "/v1/weblogs/myblog/media/directories";

    private MediaFileManager files;
    private WeblogManager weblogs;
    private Weblogger weblogger;
    private Weblog weblog;
    private MediaFileDirectory photos;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        weblogs = mock(WeblogManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setMediaFileDirectories(new ArrayList<>());
        photos = new MediaFileDirectory(weblog, "photos", null);
        photos.setId("photos-id");
        MediaFileDirectory def = new MediaFileDirectory(weblog, "default", null);
        def.setId("default-id");
        when(files.getMediaFileDirectory("photos-id")).thenReturn(photos);
        when(files.getMediaFileDirectory("default-id")).thenReturn(def);
        Weblog other = new Weblog();
        other.setId("other-id");
        MediaFileDirectory foreign = new MediaFileDirectory(other, "theirs", null);
        foreign.setId("foreign-id");
        when(files.getMediaFileDirectory("foreign-id")).thenReturn(foreign);
        MediaApi api = new MediaApi();
        api.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : new ObjectMapper().readTree(body);
    }

    private JsonNode patchJson(ResultMatcher status, String id, String body) throws Exception {
        return answer(status, patch(DIRS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anEmptyFolderIsDeletedAndTheWeblogSavedSoItsPagesExpire() throws Exception {
        answer(status().isNoContent(), delete(DIRS + "/photos-id"));

        var order = inOrder(files, weblogs, weblogger);
        order.verify(files).removeMediaFileDirectory(photos);
        order.verify(weblogs).saveWeblog(weblog);
        order.verify(weblogger).flush();
    }

    @Test
    void theDefaultFolderCannotBeDeleted() throws Exception {
        problem(answer(status().isConflict(), delete(DIRS + "/default-id")), "directory.isDefault",
                "The default directory cannot be deleted.");
        verify(files, never()).removeMediaFileDirectory(any());
    }

    @Test
    void aFolderHoldingFilesCannotBeDeleted() throws Exception {
        photos.setMediaFiles(new java.util.LinkedHashSet<>(List.of(new MediaFile())));

        problem(answer(status().isConflict(), delete(DIRS + "/photos-id")), "directory.notEmpty",
                "The directory still holds files; move or delete them first.");
        verify(files, never()).removeMediaFileDirectory(any());
    }

    @Test
    void anUnknownOrForeignFolderIsNotFound() throws Exception {
        problem(answer(status().isNotFound(), delete(DIRS + "/nope")), "directory.notFound", "No such directory.");
        problem(answer(status().isNotFound(), delete(DIRS + "/foreign-id")), "directory.notFound",
                "No such directory.");
        problem(patchJson(status().isNotFound(), "foreign-id", "{\"isPrivate\":true}"), "directory.notFound",
                "No such directory.");
        verify(files, never()).removeMediaFileDirectory(any());
    }

    @Test
    void theFolderCanBeMadePrivateAndPublicAgain() throws Exception {
        JsonNode view = patchJson(status().isOk(), "photos-id", "{\"isPrivate\":true}");
        assertTrue(view.get("isPrivate").asBoolean());
        assertTrue(photos.isPrivate());
        verify(weblogs).saveWeblog(weblog);

        view = patchJson(status().isOk(), "photos-id", "{\"isPrivate\":false}");
        assertFalse(view.get("isPrivate").asBoolean());
        assertFalse(photos.isPrivate());
    }

    @Test
    void aPatchNamingNoFlagIsRequired() throws Exception {
        oneField(patchJson(status().isBadRequest(), "photos-id", "{}"), "isPrivate", "field.required",
                "isPrivate is required.");
        verify(weblogs, never()).saveWeblog(any());
    }
}
