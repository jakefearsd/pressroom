package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A media file's description, copyright text and tags: what the edit form carries, in the patch and the view. */
class MediaMetadataApiTest {

    private MediaFileManager files;
    private Weblog weblog;
    private MediaFile file;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), anyBoolean())).thenReturn("https://example.invalid/m/f1");
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        file = new MediaFile();
        file.setId("f1");
        file.setName("dog.jpg");
        file.setWeblog(weblog);
        file.setDirectory(new MediaFileDirectory(weblog, "default", null));
        when(files.getMediaFile("f1")).thenReturn(file);
        MediaApi api = new MediaApi();
        api.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode patchIt(ResultMatcher expected, String body) throws Exception {
        String out = mvc.perform(patch("/v1/weblogs/myblog/media/f1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(out);
    }

    @Test
    void theThreeFieldsAreSetAndShownInTheView() throws Exception {
        file.setDescription("old");
        JsonNode view = patchIt(status().isOk(),
                "{\"description\":\"A dog on a beach\",\"copyrightText\":\"(c) Maiia\",\"tags\":[\"dog\",\"beach\"]}");

        assertEquals("A dog on a beach", file.getDescription());
        assertEquals("(c) Maiia", file.getCopyrightText());
        verify(files).updateTags(file, List.of("dog", "beach"));
        assertEquals("A dog on a beach", view.get("description").asString());
        assertEquals("(c) Maiia", view.get("copyrightText").asString());
    }

    @Test
    void anAbsentFieldLeavesTheStoredValueAndNoTagsCallIsMade() throws Exception {
        file.setDescription("kept");
        file.setCopyrightText("kept too");

        patchIt(status().isOk(), "{\"altText\":\"x\"}");

        assertEquals("kept", file.getDescription());
        assertEquals("kept too", file.getCopyrightText());
        verify(files, never()).updateTags(any(), any());
    }

    @Test
    void anEmptyTagListRemovesEveryTagAndClearRemovesTheTexts() throws Exception {
        file.setDescription("d");
        file.setCopyrightText("c");

        patchIt(status().isOk(), "{\"tags\":[],\"clear\":[\"description\",\"copyrightText\"]}");

        verify(files).updateTags(file, List.of());
        assertNull(file.getDescription());
        assertNull(file.getCopyrightText());
    }

    @Test
    void tagsAreSplitOnSpacesAndCommasAsTheFormDoes() throws Exception {
        patchIt(status().isOk(), "{\"tags\":[\"dog, beach\",\"sun\"]}");

        verify(files).updateTags(file, List.of("dog", "beach", "sun"));
    }

    @Test
    void everyFieldOverItsColumnIsNamedInOne400AndNothingIsSaved() throws Exception {
        JsonNode problem = patchIt(status().isBadRequest(), "{\"description\":\"" + "d".repeat(256)
                + "\",\"copyrightText\":\"" + "c".repeat(1024) + "\",\"tags\":[\"" + "t".repeat(31) + "\"]}");

        field(problem, "description", "field.tooLong", "'description' must be 255 characters or fewer.");
        field(problem, "copyrightText", "field.tooLong", "'copyrightText' must be 1023 characters or fewer.");
        field(problem, "tags", "field.tooLong", "'tags' must be 30 characters or fewer.");
        verify(files, never()).updateMediaFile(any(), any());
        verify(files, never()).updateTags(any(), any());
    }

    @Test
    void theLimitsAreInclusive() throws Exception {
        patchIt(status().isOk(), "{\"description\":\"" + "d".repeat(255) + "\",\"copyrightText\":\""
                + "c".repeat(1023) + "\",\"tags\":[\"" + "t".repeat(30) + "\"]}");
    }
}
