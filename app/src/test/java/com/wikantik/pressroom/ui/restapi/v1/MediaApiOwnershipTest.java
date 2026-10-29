package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which media file or directory the API admits to knowing about. A file is
 * owned by its directory's weblog, or by its own weblog column when it is not
 * filed anywhere; anything else is a 404, never a 403, so an id probe learns
 * nothing. Complements {@link MediaApiTest}.
 */
class MediaApiOwnershipTest {

    private static final String IMPORT_LOGGER = "roller.media.import";

    private final Weblogger weblogger = mock(Weblogger.class);
    private final MediaFileManager mfm = mock(MediaFileManager.class);
    private final Weblog weblog = weblog("myblog");

    MediaApiOwnershipTest() {
        when(weblogger.getMediaFileManager()).thenReturn(mfm);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), anyBoolean())).thenReturn("https://example.invalid/m");
    }

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private MediaApi controller() {
        MediaApi controller = new MediaApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private MockMvc mvc() {
        return mvc(controller());
    }

    private static MockMvc mvc(MediaApi controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MediaFile file(String name) {
        MediaFile f = new MediaFile();
        f.setId(name + "-id");
        f.setName(name);
        return f;
    }

    @Test
    void anUnfiledFileIsOwnedByItsOwnWeblogColumn() throws Exception {
        MediaFile unfiled = file("loose.jpg");
        unfiled.setWeblog(weblog);
        when(mfm.getMediaFile("f1")).thenReturn(unfiled);

        mvc().perform(get("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk());
    }

    @Test
    void anUnfiledFileOfAnotherWeblogIsNotFound() throws Exception {
        MediaFile unfiled = file("loose.jpg");
        unfiled.setWeblog(weblog("other"));
        when(mfm.getMediaFile("f1")).thenReturn(unfiled);

        mvc().perform(get("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFileWithNoOwnerAtAllIsNotFound() throws Exception {
        when(mfm.getMediaFile("f1")).thenReturn(file("orphan.jpg"));

        mvc().perform(get("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOwnerWithoutAnIdIsNeverAMatch() throws Exception {
        Weblog unsaved = new Weblog();
        unsaved.setId(null);
        MediaFile f = file("odd.jpg");
        f.setWeblog(unsaved);
        when(mfm.getMediaFile("f1")).thenReturn(f);

        mvc().perform(get("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", unsaved))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBlankIdIsNotFoundWithoutAskingTheManager() throws Exception {
        mvc().perform(delete("/v1/weblogs/myblog/media/{id}", " ").requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound());

        verify(mfm, never()).getMediaFile(any());
    }

    @Test
    void aDirectoryWithNoWeblogIsNotFoundOnList() throws Exception {
        MediaFileDirectory floating = mock(MediaFileDirectory.class);
        when(mfm.getMediaFileDirectory("d1")).thenReturn(floating);

        mvc().perform(get("/v1/weblogs/myblog/media").param("dir", "d1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBlankDirFilterListsTheWholeWeblog() throws Exception {
        when(mfm.searchMediaFiles(any(), any(MediaFileFilter.class))).thenReturn(List.of());

        mvc().perform(get("/v1/weblogs/myblog/media").param("dir", "  ").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk());

        verify(mfm).searchMediaFiles(any(), any(MediaFileFilter.class));
        verify(mfm, never()).getMediaFileDirectory(any());
    }

    /** Naming the directory a file is already in is not a move. */
    @Test
    void patchingTheDirectoryToTheCurrentOneMovesNothing() throws Exception {
        MediaFileDirectory dir = new MediaFileDirectory(weblog, "photos", null);
        MediaFile f = file("a.jpg");
        f.setDirectory(dir);
        when(mfm.getMediaFile("f1")).thenReturn(f);
        when(mfm.getMediaFileDirectory(dir.getId())).thenReturn(dir);

        mvc().perform(patch("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"directoryId\":\"" + dir.getId() + "\"}"))
                .andExpect(status().isOk());

        verify(mfm, never()).moveMediaFile(any(), any());
        verify(weblogger).flush();
    }

    @Test
    void anUnfiledFileCanBeMovedIntoAnOwnedDirectory() throws Exception {
        MediaFileDirectory dir = new MediaFileDirectory(weblog, "photos", null);
        MediaFile f = file("a.jpg");
        f.setWeblog(weblog);
        when(mfm.getMediaFile("f1")).thenReturn(f);
        when(mfm.getMediaFileDirectory(dir.getId())).thenReturn(dir);

        mvc().perform(patch("/v1/weblogs/myblog/media/{id}", "f1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"directoryId\":\"" + dir.getId() + "\"}"))
                .andExpect(status().isOk());

        verify(mfm).moveMediaFile(f, dir);
    }

    @Test
    void createDirectoryWithNoNameAtAllIsBadRequest() throws Exception {
        mvc().perform(post("/v1/weblogs/myblog/media/directories").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(mfm, never()).createMediaFileDirectory(any(), any());
    }

    @Test
    void createDirectoryStoresTheDescriptionItWasGiven() throws Exception {
        MediaFileDirectory created = new MediaFileDirectory(weblog("shadow"), "trips", null);
        when(mfm.createMediaFileDirectory(weblog, "trips")).thenReturn(created);

        mvc().perform(post("/v1/weblogs/myblog/media/directories").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"trips\",\"description\":\"Spring\"}"))
                .andExpect(status().isCreated());

        assertEquals("Spring", created.getDescription());
    }

    // ------------------------------------------- the import log's host field

    private List<String> importLogFor(String url) throws Exception {
        List<String> lines = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig(IMPORT_LOGGER, Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger(IMPORT_LOGGER, lc);
        ctx.updateLoggers();
        try (MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class)) {
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(true);
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
            MediaFileDirectory dir = new MediaFileDirectory(weblog, "default", null);
            when(mfm.getDefaultMediaFileDirectory(weblog)).thenReturn(dir);
            RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
            when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenThrow(new FetchRefusal("nope"));
            MediaApi controller = controller();
            controller.imageFetcher = fetcher;
            mvc(controller).perform(post("/v1/weblogs/myblog/media/imports").requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            ctx.getConfiguration().removeLogger(IMPORT_LOGGER);
            ctx.updateLoggers();
            appender.stop();
        }
        return lines;
    }

    @Test
    void anUnparseableUrlLogsAQuestionMarkForTheHost() throws Exception {
        List<String> lines = importLogFor("http://bad host/x?sig=SECRET");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("host=? "), lines.get(0));
        assertFalse(lines.get(0).contains("SECRET"), lines.get(0));
    }

    @Test
    void aUrlWithNoHostLogsAQuestionMarkForTheHost() throws Exception {
        List<String> lines = importLogFor("mailto:someone@example.com");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("host=? "), lines.get(0));
    }
}
