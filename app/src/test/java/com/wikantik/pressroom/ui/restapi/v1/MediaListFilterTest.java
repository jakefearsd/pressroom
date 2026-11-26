package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.MediaFileType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The media list's search text, type filter and sort: the Media Files screen's own (a name prefix and a type
 * through {@code MediaFileManager.searchMediaFiles}, the sort keys name, date_uploaded and type), alongside
 * the directory and paging the list already had.
 */
class MediaListFilterTest {

    private MediaFileManager files;
    private Weblog weblog;
    private MediaFileDirectory photos;
    private MediaFileDirectory other;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        files = mock(MediaFileManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> "https://example.invalid/media/" + inv.getArgument(1));
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        photos = new MediaFileDirectory(weblog, "photos", null);
        photos.setId("photos-id");
        other = new MediaFileDirectory(weblog, "other", null);
        other.setId("other-id");
        when(files.getMediaFileDirectory("photos-id")).thenReturn(photos);
        MediaReadApi reads = new MediaReadApi();
        reads.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(reads).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MediaFile file(MediaFileDirectory dir, String name, String type, long uploadedEpochSecond) {
        MediaFile f = new MediaFile();
        f.setId(name + "-id");
        f.setName(name);
        f.setContentType(type);
        f.setDirectory(dir);
        f.setDateUploaded(java.sql.Timestamp.from(Instant.ofEpochSecond(uploadedEpochSecond)));
        return f;
    }

    private JsonNode list(String query) throws Exception {
        String body = mvc.perform(get("/v1/weblogs/myblog/media" + query).requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static List<String> names(JsonNode page) {
        return page.get("items").valueStream().map(n -> n.get("name").asString()).toList();
    }

    private MediaFileFilter searched() throws Exception {
        ArgumentCaptor<MediaFileFilter> filter = ArgumentCaptor.forClass(MediaFileFilter.class);
        verify(files).searchMediaFiles(eq(weblog), filter.capture());
        return filter.getValue();
    }

    @Test
    void searchTextAndTypeGoToTheManagersSearchAsTheScreenSendsThem() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(file(photos, "beach.jpg", "image/jpeg", 1)));

        assertEquals(List.of("beach.jpg"), names(list("?q=bea&type=image")));

        MediaFileFilter filter = searched();
        assertEquals("bea", filter.getName());
        assertEquals(MediaFileType.IMAGE, filter.getType());
        assertEquals(-1, filter.getStartIndex(), "the whole result set: paging is the API's");
    }

    @Test
    void theOtherTypeIsTheScreensOthers() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of());

        list("?type=other");

        assertEquals(MediaFileType.OTHERS, searched().getType());
    }

    @Test
    void aSearchWithADirectoryKeepsOnlyThatDirectorysFiles() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(
                file(photos, "beach.jpg", "image/jpeg", 1), file(other, "bean.jpg", "image/jpeg", 2)));

        assertEquals(List.of("beach.jpg"), names(list("?q=be&directoryId=photos-id")));
    }

    @Test
    void aBlankSearchAndNoTypeStayOnTheDirectoryPath() throws Exception {
        photos.setMediaFiles(new java.util.LinkedHashSet<>(List.of(file(photos, "b.jpg", "image/jpeg", 1))));

        assertEquals(List.of("b.jpg"), names(list("?q=&directoryId=photos-id")));
        verify(files, never()).searchMediaFiles(any(), any());
    }

    @Test
    void sortByDateUploadedIsNewestFirstAndByTypeIsByContentType() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(
                file(photos, "a.png", "image/png", 10), file(photos, "b.mp3", "audio/mpeg", 30),
                file(photos, "c.jpg", "image/jpeg", 20)));

        assertEquals(List.of("b.mp3", "c.jpg", "a.png"), names(list("?sort=date_uploaded")));
        assertEquals(List.of("b.mp3", "c.jpg", "a.png"), names(list("?sort=type")));
        assertEquals(List.of("a.png", "b.mp3", "c.jpg"), names(list("?sort=name")));
        assertEquals(List.of("a.png", "b.mp3", "c.jpg"), names(list("")), "name is the default");
    }

    @Test
    void anUnknownSortOrTypeIsA400NamingTheParameter() throws Exception {
        oneField(refused("?sort=size"), "sort", "parameter.invalidValue", "sort has an invalid value.");
        oneField(refused("?type=pdf"), "type", "parameter.invalidValue", "type has an invalid value.");
    }

    @Test
    void largerThanAndTagsGoToTheManagersSearchSoThePageHoldsTheMatches() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(file(photos, "beach.jpg", "image/jpeg", 1)));

        assertEquals(List.of("beach.jpg"), names(list("?largerThan=2048&tags=sea&tags=sun")));

        MediaFileFilter filter = searched();
        assertEquals(2048, filter.getSize());
        assertEquals(MediaFileFilter.SizeFilterType.GT, filter.getSizeFilterType(), "strictly larger, as the screen said");
        assertEquals(List.of("sea", "sun"), filter.getTags());
    }

    @Test
    void tagsMayBeCommaSeparatedAndBlankOnesAreDropped() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of());

        list("?tags=sea,sun&tags=");

        assertEquals(List.of("sea", "sun"), searched().getTags());
    }

    @Test
    void aSizeOrTagsAloneIsASearchEvenWithADirectory() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(
                file(photos, "beach.jpg", "image/jpeg", 1), file(other, "bean.jpg", "image/jpeg", 2)));

        assertEquals(List.of("beach.jpg"), names(list("?largerThan=1&directoryId=photos-id")));
    }

    @Test
    void aNegativeLargerThanIsA400NamingTheParameter() throws Exception {
        oneField(refused("?largerThan=-1"), "largerThan", "parameter.invalidValue", "largerThan has an invalid value.");
    }

    private JsonNode refused(String query) throws Exception {
        String body = mvc.perform(get("/v1/weblogs/myblog/media" + query).requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void aLargerThanOfZeroIsAcceptedAsNoMinimum() throws Exception {
        when(files.searchMediaFiles(eq(weblog), any())).thenReturn(List.of(
                file(photos, "beach.jpg", "image/jpeg", 1)));

        assertEquals(List.of("beach.jpg"), names(list("?largerThan=0")));
    }
}
