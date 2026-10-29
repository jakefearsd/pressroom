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
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.ImageKind;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.util.PressroomMessages;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import com.wikantik.pressroom.business.ContentHashes;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.MediaDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

class MediaApiTest {

    /**
     * Blank counts as absent for altText at every consumer -- an author who
     * cleared the field did not thereby declare the image decorative. The
     * audit endpoint and the UI marker both use isNotBlank, so the DTO must
     * report the same thing rather than EL's notion of empty.
     */
    @Test
    void whitespaceOnlyAltTextIsReportedAsMissing() {
        MediaFile file = new MediaFile();
        file.setAltText("   ");
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText("");
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText(null);
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText("A cat on a wall");
        assertFalse(MediaDtos.isAltTextMissing(file));
    }

    /** A patch that omits altText must not erase it. */
    @Test
    void anAbsentAltTextInAPatchLeavesTheStoredValue() {
        MediaFile file = new MediaFile();
        file.setAltText("kept");
        MediaDtos.applyPatch(file, new MediaDtos.MediaPatch(null, 0.5, 0.5, null, null, null), java.util.Set.of());
        assertEquals("kept", file.getAltText());
    }

    /**
     * An explicitly empty altText is a real edit -- the author cleared the
     * field -- so it is stored, and the audit endpoint then reports it as
     * missing. That is the intended loop, not a contradiction.
     */
    @Test
    void anExplicitlyEmptyAltTextIsStored() {
        MediaFile file = new MediaFile();
        file.setAltText("was here");
        MediaDtos.applyPatch(file, new MediaDtos.MediaPatch("", null, null, null, null, null), java.util.Set.of());
        assertEquals("", file.getAltText());
    }

    /**
     * Whole-branch review, Must Fix 1: pressroom_mediafile carries no dedicated
     * alt-text column width of its own in this table -- {@code alt_text} is
     * varchar(255) (V024__media_alt_text.sql) -- and model-written
     * descriptions routinely exceed 255 characters, so this is the highest
     * -value guard in the whole sweep: AuditApi hands an agent exactly this
     * field to fix, and without this guard the audit's own output breaks the
     * audit's own fix path.
     */
    @Test
    void anAltTextLongerThanTheColumnIsRejected() {
        MediaFile file = new MediaFile();
        String tooLong = "a".repeat(300);

        ApiException ex = assertThrows(ApiException.class, () -> MediaDtos.applyPatch(
                file, new MediaDtos.MediaPatch(tooLong, null, null, null, null, null), java.util.Set.of()));

        assertEquals(400, ex.getStatus());
        assertNull(file.getAltText(), "the in-memory file must be untouched on refusal");
    }

    /** A 255-character altText, exactly at the column limit, must be accepted. */
    @Test
    void anAltTextAtExactlyTheColumnLimitIsAccepted() {
        MediaFile file = new MediaFile();
        String atLimit = "a".repeat(255);
        MediaDtos.applyPatch(file, new MediaDtos.MediaPatch(atLimit, null, null, null, null, null), java.util.Set.of());
        assertEquals(255, file.getAltText().length());
    }

    /**
     * Unlike altText, a blank name is never a real value -- there is no
     * "decorative filename" concept a cleared name could mean, only a
     * silently broken one. Deliberately different handling from altText's
     * empty-string-is-real-and-stored rule right above.
     */
    @Test
    void aBlankNameInAPatchIsRejected() {
        MediaFile file = new MediaFile();
        file.setName("original.jpg");

        ApiException ex = assertThrows(ApiException.class, () -> MediaDtos.applyPatch(
                file, new MediaDtos.MediaPatch(null, null, null, null, "   ", null), java.util.Set.of()));

        assertEquals(400, ex.getStatus());
        assertEquals("original.jpg", file.getName(), "the in-memory file must be untouched on refusal");
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, the ownership checks
    // and JSON shape -- none of which the DTO-only tests above can see.
    // actionWeblog is injected as a request attribute rather than resolved
    // by a real interceptor, matching CategoriesApiTest/EntriesApiReadTest's
    // standalone style. These run against a Mockito-mocked Weblogger, not
    // the DB fixtures.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(MediaApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        MediaFileManager mediaFileManager = mock(MediaFileManager.class);
        URLStrategy urlStrategy = mock(URLStrategy.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);
        when(urlStrategy.getMediaFileURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> "https://example.invalid/media/" + inv.getArgument(1));
        return weblogger;
    }

    private MediaApi controllerFor(Weblogger weblogger) {
        MediaApi controller = new MediaApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        return weblog;
    }

    private static MediaFileDirectory aDirectory(Weblog weblog, String name) {
        return new MediaFileDirectory(weblog, name, null);
    }

    private static MediaFile aMediaFile(MediaFileDirectory directory, String name) {
        MediaFile file = new MediaFile();
        file.setId(name + "-id");
        file.setName(name);
        file.setDirectory(directory);
        return file;
    }

    @Test
    void listReturnsMediaViewsAcrossTheWeblogWhenNoDirIsGiven() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "cat.jpg");
        file.setAltText("A cat");
        when(weblogger.getMediaFileManager()
                        .searchMediaFiles(org.mockito.ArgumentMatchers.eq(weblog), any(MediaFileFilter.class)))
                .thenReturn(List.of(file));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("cat.jpg", json.get(0).get("name").asString());
        assertEquals("A cat", json.get(0).get("altText").asString());
        assertEquals(dir.getId(), json.get(0).get("directory").asString());
        assertTrue(json.get(0).get("url").asString().endsWith("/cat.jpg-id"));
    }

    /**
     * The list pages when asked: offset and limit slice the name-sorted list
     * (still a bare array, as before); without either, the whole list, as
     * before. A limit is capped at 200 like every list; a bad range is a 400.
     */
    @Test
    void listPagesWhenGivenOffsetAndLimit() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        List<MediaFile> files = new java.util.ArrayList<>();
        for (String name : List.of("e.jpg", "a.jpg", "d.jpg", "b.jpg", "c.jpg")) {
            files.add(aMediaFile(dir, name));
        }
        when(weblogger.getMediaFileManager()
                        .searchMediaFiles(org.mockito.ArgumentMatchers.eq(weblog), any(MediaFileFilter.class)))
                .thenReturn(files);
        MockMvc mvc = mockMvc(controllerFor(weblogger));
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

        tools.jackson.databind.JsonNode page = json.readTree(mvc.perform(get("/v1/weblogs/myblog/media")
                        .param("offset", "1").param("limit", "2").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, page.size());
        assertEquals("b.jpg", page.get(0).get("name").asString());
        assertEquals("c.jpg", page.get(1).get("name").asString());

        tools.jackson.databind.JsonNode rest = json.readTree(mvc.perform(get("/v1/weblogs/myblog/media")
                        .param("offset", "3").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, rest.size(), "an offset alone gives the rest of the list");

        tools.jackson.databind.JsonNode past = json.readTree(mvc.perform(get("/v1/weblogs/myblog/media")
                        .param("offset", "9").param("limit", "2").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, past.size(), "past the end is an empty page, not an error");

        assertEquals(5, json.readTree(mvc.perform(get("/v1/weblogs/myblog/media")
                        .param("limit", "5000").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size());

        mvc.perform(get("/v1/weblogs/myblog/media").param("limit", "0").requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("limit"));
        mvc.perform(get("/v1/weblogs/myblog/media").param("offset", "-1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("offset"));
    }

    /** {@code directoryId} is the name the rest of the API uses; {@code dir} stays as its alias. */
    @Test
    void listTakesDirectoryIdAndStillTakesDir() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "gallery");
        dir.getMediaFiles().add(aMediaFile(dir, "beach.jpg"));
        MediaFileDirectory other = aDirectory(weblog, "other");
        when(weblogger.getMediaFileManager().getMediaFileDirectory(dir.getId())).thenReturn(dir);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(other.getId())).thenReturn(other);
        MockMvc mvc = mockMvc(controllerFor(weblogger));

        String body = mvc.perform(get("/v1/weblogs/myblog/media").param("directoryId", dir.getId())
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("beach.jpg", new tools.jackson.databind.ObjectMapper().readTree(body).get(0).get("name").asString());
        mvc.perform(get("/v1/weblogs/myblog/media").param("directoryId", dir.getId()).param("dir", dir.getId())
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/weblogs/myblog/media").param("directoryId", dir.getId()).param("dir", other.getId())
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("dir"));
        verify(weblogger.getMediaFileManager(), never()).searchMediaFiles(any(), any());
    }

    @Test
    void listFiltersToASingleOwnedDirectoryWhenDirIsGiven() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "gallery");
        MediaFile file = aMediaFile(dir, "beach.jpg");
        dir.getMediaFiles().add(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(dir.getId())).thenReturn(dir);

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media").param("dir", dir.getId())
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("beach.jpg", json.get(0).get("name").asString());
        verify(weblogger.getMediaFileManager(), never()).searchMediaFiles(any(), any());
    }

    /**
     * Private directories are a visibility flag with no bypass, but a
     * caller holding a weblog-scoped token already IS an authorized editor
     * of the owning weblog -- listing a private directory through the API
     * is the access such a caller already has, not a new hole. Nothing here
     * should exclude it.
     */
    @Test
    void listIncludesFilesFromAPrivateDirectoryForAnAuthorizedCaller() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "private-shoot");
        dir.setPrivate(true);
        MediaFile file = aMediaFile(dir, "secret.jpg");
        dir.getMediaFiles().add(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(dir.getId())).thenReturn(dir);

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media").param("dir", dir.getId())
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("secret.jpg", json.get(0).get("name").asString());
    }

    /**
     * The IDOR case for the {@code dir} filter: a directory id belonging to
     * another weblog must read as absent, exactly like WeblogOwnership's
     * by-id lookups for entries/categories/templates/pages.
     */
    @Test
    void listWithAForeignDirIsNotFound() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        MediaFileDirectory foreign = aDirectory(anotherWeblog, "secret");
        when(weblogger.getMediaFileManager().getMediaFileDirectory(foreign.getId())).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media").param("dir", foreign.getId())
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void getReturnsAMediaViewForAnOwnedFile() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "sun.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media/{id}", "file-1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("sun.jpg", json.get("name").asString());
    }

    /**
     * getMediaFile is a global by-id lookup; a media file owned by another
     * weblog must read as absent here, the same as every other by-id lookup
     * this wave defends.
     */
    @Test
    void getIsNotFoundWhenTheMediaFileBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        MediaFileDirectory foreignDir = aDirectory(anotherWeblog, "default");
        MediaFile foreign = aMediaFile(foreignDir, "hidden.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media/{id}", "file-1").requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void patchAppliesFieldsAndPersistsThroughTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "dog.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altText\":\"A good dog\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("A good dog", json.get("altText").asString());
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, file);
        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        verify(weblogger).flush();
    }

    /**
     * Controller-level twin of the DTO-level {@code
     * anAltTextLongerThanTheColumnIsRejected}: exercised end to end through
     * real HTTP dispatch, proving the guard is actually wired into the PATCH
     * endpoint, not just correct in {@code MediaDtos.applyPatch} isolation.
     */
    @Test
    void patchWithAnAltTextLongerThanTheColumnIsBadRequestAndNothingIsPersisted() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "dog.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        String tooLong = "a".repeat(300);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altText\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
        assertNull(file.getAltText(), "the in-memory file must be untouched on refusal");
    }

    /**
     * Controller-level twin of the DTO-level {@code aBlankNameInAPatchIsRejected}:
     * a blank-but-present name must never reach the manager.
     */
    @Test
    void patchWithABlankNameIsBadRequestAndNothingIsPersisted() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "dog.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
        assertEquals("dog.jpg", file.getName(), "the in-memory file must be untouched on refusal");
        verify(weblogger, never()).flush();
    }

    @Test
    void patchWithADirectoryIdMovesToTheOwnedDirectory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory source = aDirectory(weblog, "source");
        MediaFileDirectory target = aDirectory(weblog, "target");
        MediaFile file = aMediaFile(source, "dog.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(target.getId())).thenReturn(target);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"directoryId\":\"" + target.getId() + "\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getMediaFileManager()).moveMediaFile(file, target);
        verify(weblogger).flush();
    }

    /**
     * Guard: a directoryId naming another weblog's directory must be
     * refused before anything is moved or persisted, the same shape as
     * CategoriesApi's foreign-moveTo guard. Per CLAUDE.md's guardrail on
     * self-added guards, this was verified to actually fail when the
     * ownership check is bypassed -- see the task report.
     */
    @Test
    void patchWithAForeignDirectoryIdIsNotFoundAndNothingIsChanged() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        MediaFileDirectory source = aDirectory(thisWeblog, "source");
        MediaFile file = aMediaFile(source, "dog.jpg");
        MediaFileDirectory foreignTarget = aDirectory(anotherWeblog, "target");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(foreignTarget.getId()))
                .thenReturn(foreignTarget);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"directoryId\":\"" + foreignTarget.getId() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
        verify(weblogger, never()).flush();
    }

    @Test
    void deleteRemovesTheFileAndReturnsNoContent() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "old.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/media/{id}", "file-1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, file);
        verify(weblogger).flush();
    }

    @Test
    void deleteIsNotFoundWhenTheMediaFileBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        MediaFileDirectory foreignDir = aDirectory(anotherWeblog, "default");
        MediaFile foreign = aMediaFile(foreignDir, "hidden.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/media/{id}", "file-1").requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).removeMediaFile(any(), any());
        verify(weblogger, never()).flush();
    }

    @Test
    void directoriesReturnsDirectoryViewsForTheWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "gallery");
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog)).thenReturn(List.of(dir));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/media/directories").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("gallery", json.get(0).get("name").asString());
        assertEquals(0, json.get(0).get("fileCount").asInt());
    }

    @Test
    void createDirectoryReturnsCreated() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        // Constructed against a throwaway weblog so it does not register
        // itself into `weblog`'s own directory set -- that set is what the
        // duplicate-name guard below checks, and this is exercising the
        // success path, not the guard.
        MediaFileDirectory created = aDirectory(aWeblog("shadow"), "newdir");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "newdir")).thenReturn(created);

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"newdir\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("newdir", json.get("name").asString());
        verify(weblogger.getMediaFileManager()).createMediaFileDirectory(weblog, "newdir");
        verify(weblogger).flush();
    }

    /**
     * Guard: a duplicate directory name is a 409, never the bare
     * WebloggerException ("Directory exists") that
     * JPAMediaFileManagerImpl.createMediaFileDirectory throws, which
     * ApiExceptionHandler could only turn into an opaque 500. Mirrors
     * CategoriesApi.create's duplicate-name check.
     */
    @Test
    void createDirectoryWithADuplicateNameIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        aDirectory(weblog, "existing"); // registers into weblog's own directory set

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"existing\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("name"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
        verify(weblogger, never()).flush();
    }

    @Test
    void createDirectoryWithABlankNameIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * Regression: {@code JPAMediaFileManagerImpl.createMediaFileDirectory}
     * strips a single leading slash BEFORE validating -- "/" normalises to
     * "" and the manager throws a bare {@code WebloggerException("Invalid
     * name!")}, which {@code ApiExceptionHandler} could only turn into an
     * opaque 500 for input this ordinary. The pre-check must perform the
     * same normalisation the manager performs, not just check the raw
     * string, or it lets exactly this input through untouched.
     */
    @Test
    void createDirectoryWithASlashOnlyNameIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * Regression, the other half: a name that only collides with an
     * existing directory AFTER the manager's leading-slash normalisation
     * must be a 409, not a 500 -- the duplicate pre-check has to see the
     * same normalised name the manager would.
     */
    @Test
    void createDirectoryWithASlashPrefixedDuplicateNameIsConflictNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        aDirectory(weblog, "existing"); // registers into weblog's own directory set

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"/existing\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("name"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * pressroom_mediafiledir.name/description are both varchar(255)
     * (V002__baseline_schema.sql) -- whole-branch review, Must Fix 1. Checked
     * against the NORMALISED name (leading slash stripped), the same value
     * the manager would actually store, matching the blank/reserved/
     * duplicate pre-checks right above.
     */
    @Test
    void createDirectoryWithANameLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
        verify(weblogger, never()).flush();
    }

    @Test
    void createDirectoryWithADescriptionLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"newdir\",\"description\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
        verify(weblogger, never()).flush();
    }

    /**
     * "default" is reserved by the manager (it is what
     * {@code createDefaultMediaFileDirectory} names the weblog's own
     * default folder) -- {@code createMediaFileDirectory(weblog, "default")}
     * always throws, even on a weblog with no directory literally named
     * "default" yet. Must be a 400, not a 500.
     */
    @Test
    void createDirectoryWithNameDefaultIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"default\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * Wave-level gap (Finding 3, see CategoriesApiTest): a malformed JSON
     * body must be a 400, not the opaque 500 it would be without
     * ApiExceptionHandler's dedicated HttpMessageNotReadableException
     * handler.
     */
    @Test
    void aMalformedRequestBodyIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    /**
     * MediaApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as POST -- media management is blog-wide structure, the same level
     * CategoriesApi requires, not the looser EDIT_DRAFT entry reads use.
     */
    @Test
    void declaresPostAsTheRequiredWeblogPermission() {
        assertEquals(List.of(WeblogPermission.POST), new MediaApi().requiredWeblogPermissionActions());
        assertTrue(new MediaApi().isUserRequired());
        assertTrue(new MediaApi().isWeblogRequired());
    }

    private MediaFile patchedFile(Weblogger weblogger, Weblog weblog, String json, int expectedStatus)
            throws Exception {
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "dog.jpg");
        file.setAltText("a");
        file.setFocalX(0.2);
        file.setFocalY(0.8);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        int actual = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
        assertEquals(expectedStatus, actual);
        return file;
    }

    @Test
    void clearRemovesAltTextAndTheFocalPair() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        MediaFile file = patchedFile(weblogger, aWeblog("myblog"),
                "{\"clear\":[\"altText\",\"focalX\",\"focalY\"]}", 200);

        assertNull(file.getAltText());
        assertNull(file.getFocalX());
        assertNull(file.getFocalY());
        verify(weblogger.getMediaFileManager()).updateMediaFile(any(), any());
    }

    @Test
    void halfTheFocalPairIsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        MediaFile file = patchedFile(weblogger, aWeblog("myblog"), "{\"clear\":[\"focalX\"]}", 400);

        assertEquals(0.2, file.getFocalX());
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    // -----------------------------------------------------------------
    // POST /media/imports
    // -----------------------------------------------------------------

    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final String SIGNED_URL = "https://img.example.com/a/sunset.jpg?sig=SECRET";
    private static final String IMPORT_LOGGER = "pressroom.media.import";

    /** A controller wired with a mocked fetcher and a default directory for {@code weblog}. */
    private MediaApi importController(Weblogger weblogger, Weblog weblog, RemoteImageFetcher fetcher) throws Exception {
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);
        MediaApi controller = controllerFor(weblogger);
        controller.imageFetcher = fetcher;
        return controller;
    }

    private static MockedStatic<WebloggerRuntimeConfig> runtimeConfig(boolean uploadsEnabled) {
        MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(uploadsEnabled);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        return runtime;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importRequest(
            Weblog weblog, String json) {
        return post("/v1/weblogs/{handle}/media/imports", weblog.getHandle())
                .requestAttr("actionWeblog", weblog)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Test
    void anImportStoresTheFetchedImageAndAnswers201() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            org.springframework.test.web.servlet.MvcResult result = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"Dusk\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString());
            assertEquals("sunset.jpg", json.get("name").asString());
            assertEquals("Dusk", json.get("altText").asString());
            String location = result.getResponse().getHeader("Location");
            assertTrue(location.endsWith("/api/v1/weblogs/myblog/media/" + json.get("id").asString()), location);
        }

        MediaFileManager mfm = weblogger.getMediaFileManager();
        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(mfm).createMediaFile(org.mockito.ArgumentMatchers.eq(weblog), created.capture(), any());
        assertEquals("image/jpeg", created.getValue().getContentType());
        assertEquals(JPEG_BYTES.length, created.getValue().getLength());
        verify(mfm).updateMediaFile(weblog, created.getValue());
        verify(weblogger).flush();
    }

    @Test
    void aRefusedFetchIsA400CarryingItsDetail() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new FetchRefusal("That address is not on the public internet."));
        MediaApi controller = importController(weblogger, weblog, fetcher);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"http://10.0.0.1/x.png\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("That address is not on the public internet."), body);
        }
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void withUploadsOffTheImportIsRefusedBeforeAnyFetch() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        MediaApi controller = importController(weblogger, weblog, fetcher);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(false)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("File upload is disabled for this site."), body);
        }
        verify(fetcher, never()).fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void aForbiddenExtensionCarriesTheUploadDetail() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager manager = weblogger.getMediaFileManager();
        doAnswer(inv -> {
            ((PressroomMessages) inv.getArgument(2)).addError("error.upload.forbiddenFile");
            return null;
        }).when(manager).createMediaFile(any(), any(), any());

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("Files of this type may not be uploaded."), body);
        }
        verify(weblogger, never()).flush();
    }

    @Test
    void aMissingUrlIsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        MediaApi controller = importController(weblogger, weblog, fetcher);

        String body = mockMvc(controller)
                .perform(importRequest(weblog, "{}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("url is required."), body);
        verify(fetcher, never()).fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void importingBytesAlreadyInTheDirectoryAnswers200WithThatFile() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFile existing = new MediaFile();
        existing.setName("sunset.jpg");
        existing.setAltText("");
        existing.setDirectory(mfm.getDefaultMediaFileDirectory(weblog));
        existing.setContentType("image/jpeg");
        when(mfm.getMediaFileByContentHash(mfm.getDefaultMediaFileDirectory(weblog),
                ContentHashes.sha256Hex(JPEG_BYTES))).thenReturn(existing);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"Dusk\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
            assertEquals(existing.getId(), json.get("id").asString());
            assertEquals("", json.get("altText").asString(), "a deliberate \"\" is never overwritten");
        }
        verify(mfm, never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aMatchedFileWithoutAltTextTakesTheImportsAltText() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFile existing = new MediaFile();
        existing.setName("sunset.jpg");
        existing.setDirectory(mfm.getDefaultMediaFileDirectory(weblog));
        existing.setContentType("image/jpeg");
        when(mfm.getMediaFileByContentHash(any(), any())).thenReturn(existing);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"Dusk\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals("Dusk", existing.getAltText());
        verify(mfm).updateMediaFile(weblog, existing);
    }

    @Test
    void aRefusedFetchNamesTheUrlField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new FetchRefusal("That address is not on the public internet."));
        MediaApi controller = importController(weblogger, weblog, fetcher);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"http://10.0.0.1/x.png\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertEquals("url", new tools.jackson.databind.ObjectMapper().readTree(body)
                    .get("errors").get(0).get("field").asString());
        }
    }

    /** The leading bytes a real file of each kind starts with, then a little body. */
    private static byte[] imageBytes(ImageKind kind) {
        byte[] head = switch (kind) {
            case JPEG -> new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case PNG -> new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case GIF -> "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case WEBP -> "RIFF\0\0\0\0WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        };
        byte[] bytes = java.util.Arrays.copyOf(head, head.length + 5);
        for (int i = head.length; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    /** Captures the stream createMediaFile is handed, read at the moment it is handed over. */
    private static java.util.concurrent.atomic.AtomicReference<byte[]> captureStoredBytes(MediaFileManager mfm)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<byte[]> stored = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            MediaFile file = inv.getArgument(1);
            stored.set(file.getInputStream().readAllBytes());
            return null;
        }).when(mfm).createMediaFile(any(), any(), any());
        return stored;
    }

    /**
     * Criterion 11 for the other three kinds the fetcher recognises: each is
     * stored through the upload path under its own content type, with the
     * name -- extension included -- the fetcher chose from the detected
     * kind, and the bytes stored are the bytes fetched, not merely as many.
     * Characterisation of the import as shipped (the JPEG case was the only
     * one pinned): expected to pass at once.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ImageKind.class, names = {"PNG", "GIF", "WEBP"})
    void eachImageKindIsStoredWithItsContentTypeExtensionAndExactBytes(ImageKind kind) throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        byte[] served = imageBytes(kind);
        String url = "https://img.example.com/photo?id=7";
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(url, 2097152L, null))
                .thenReturn(new FetchedImage("photo." + kind.extension(), kind, served));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        var stored = captureStoredBytes(mfm);

        String body;
        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            body = mockMvc(controller).perform(importRequest(weblog, "{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
        }

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(mfm).createMediaFile(org.mockito.ArgumentMatchers.eq(weblog), created.capture(), any());
        assertEquals(kind.contentType(), created.getValue().getContentType());
        assertEquals("photo." + kind.extension(), created.getValue().getName());
        assertEquals("default", created.getValue().getDirectory().getName());
        assertArrayEquals(served, stored.get());
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("photo." + kind.extension(), json.get("name").asString());
        assertEquals(kind.contentType(), json.get("contentType").asString());
        assertEquals(served.length, json.get("length").asLong());
    }

    /** The JPEG case's bytes, too: the stream handed to the store carries exactly what was fetched. */
    @Test
    void theBytesStoredAreTheBytesFetched() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        byte[] served = imageBytes(ImageKind.JPEG);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, served));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        var stored = captureStoredBytes(weblogger.getMediaFileManager());

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            mockMvc(controller).perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                    .andExpect(status().isCreated());
        }

        assertArrayEquals(served, stored.get());
    }

    /** {@code directoryId} names where the import lands, found through the ownership-checked lookup. */
    @Test
    void anImportWithADirectoryIdLandsInThatDirectory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory travel = aDirectory(weblog, "travel");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        when(mfm.getMediaFileDirectory(travel.getId())).thenReturn(travel);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog,
                            "{\"url\":\"" + SIGNED_URL + "\",\"directoryId\":\"" + travel.getId() + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(travel.getId(),
                    new tools.jackson.databind.ObjectMapper().readTree(body).get("directory").asString());
        }

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(mfm).createMediaFile(org.mockito.ArgumentMatchers.eq(weblog), created.capture(), any());
        assertSame(travel, created.getValue().getDirectory());
        verify(mfm, never()).getDefaultMediaFileDirectory(any());
    }

    /** Another weblog's directory and an unknown id are the same 404, and nothing is fetched for either. */
    @Test
    void aForeignOrUnknownDirectoryIdIsNotFoundAndNothingIsFetched() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory foreign = aDirectory(aWeblog("otherblog"), "travel");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        MediaApi controller = importController(weblogger, weblog, fetcher);
        when(weblogger.getMediaFileManager().getMediaFileDirectory(foreign.getId())).thenReturn(foreign);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            for (String directoryId : new String[] {foreign.getId(), "no-such-directory"}) {
                mockMvc(controller)
                        .perform(importRequest(weblog,
                                "{\"url\":\"" + SIGNED_URL + "\",\"directoryId\":\"" + directoryId + "\"}"))
                        .andExpect(status().isNotFound());
            }
        }
        verify(fetcher, never()).fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    /** Criterion 17: the weblog's quota refuses an import the way it refuses an upload, with that detail. */
    @Test
    void anExceededWeblogQuotaIsA400CarryingTheUploadDetail() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager manager = weblogger.getMediaFileManager();
        doAnswer(inv -> {
            ((PressroomMessages) inv.getArgument(2)).addError("error.upload.dirmax", "4.00");
            return null;
        }).when(manager).createMediaFile(any(), any(), any());

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("Adding this file would exceed this weblog's 4.00 MB storage limit."), body);
        }
        verify(weblogger, never()).flush();
    }

    /** A step that performs requests against a controller; may throw what MockMvc throws. */
    @FunctionalInterface
    private interface Requests {
        void perform() throws Exception;
    }

    /** Runs {@code requests} with {@value #IMPORT_LOGGER} captured at INFO and returns its lines. */
    private static List<String> importLogLines(Requests requests) throws Exception {
        List<String> lines = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("import-capture", null, null, true, Property.EMPTY_ARRAY) {
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
        try {
            requests.perform();
        } finally {
            ctx.getConfiguration().removeLogger(IMPORT_LOGGER);
            ctx.updateLoggers();
            appender.stop();
        }
        return lines;
    }

    @Test
    void eachImportLogsOneLineWithTheHostAndNeverThePathOrQuery() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        String refusedUrl = "https://Img.Example.com/private/sunset.png?sig=SECRET";
        when(fetcher.fetch(refusedUrl, 2097152L, null)).thenThrow(new FetchRefusal("The image server took too long."));
        MediaApi controller = importController(weblogger, weblog, fetcher);

        List<String> lines = importLogLines(() -> {
            try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
                mockMvc(controller).perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                        .andExpect(status().isCreated());
                mockMvc(controller).perform(importRequest(weblog, "{\"url\":\"" + refusedUrl + "\"}"))
                        .andExpect(status().isBadRequest());
            }
        });

        assertEquals(2, lines.size(), lines.toString());
        for (String line : lines) {
            for (String part : new String[] {"weblog=myblog", "host=img.example.com", "outcome=", "bytes=", "ms="}) {
                assertTrue(line.contains(part), part + " in " + line);
            }
            assertFalse(line.contains("sunset") || line.contains("SECRET") || line.contains("private"), line);
        }
        assertTrue(lines.get(0).contains("outcome=created") && lines.get(0).contains("bytes=" + JPEG_BYTES.length),
                lines.get(0));
        assertTrue(lines.get(1).contains("outcome=refused"), lines.get(1));
    }

    /**
     * Criterion 18 says each import logs one line, so an import refused
     * before any fetch -- uploads off, no url, an alt text too long, a
     * directory that is not this weblog's -- logs one too, as refused, still
     * with the host only ("?" when there is no url to take one from).
     */
    @Test
    void anImportRefusedBeforeTheFetchStillLogsOneLine() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        MediaApi controller = importController(weblogger, weblog, fetcher);

        List<String> lines = importLogLines(() -> {
            try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(false)) {
                mockMvc(controller).perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\"}"))
                        .andExpect(status().isBadRequest());
            }
            try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
                mockMvc(controller).perform(importRequest(weblog, "{\"url\":\" \"}"))
                        .andExpect(status().isBadRequest());
                mockMvc(controller).perform(importRequest(weblog,
                                "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"" + "a".repeat(256) + "\"}"))
                        .andExpect(status().isBadRequest());
                mockMvc(controller).perform(importRequest(weblog,
                                "{\"url\":\"" + SIGNED_URL + "\",\"directoryId\":\"nope\"}"))
                        .andExpect(status().isNotFound());
            }
        });

        assertEquals(4, lines.size(), lines.toString());
        for (String line : lines) {
            assertTrue(line.contains("weblog=myblog") && line.contains("outcome=refused")
                    && line.contains("bytes=0"), line);
            assertFalse(line.contains("sunset") || line.contains("SECRET"), line);
        }
        assertTrue(lines.get(0).contains("host=img.example.com"), lines.get(0));
        assertTrue(lines.get(1).contains("host=? "), lines.get(1));
        verify(fetcher, never()).fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void createDirectoryWithABlankNameNamesTheNameField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }

    @Test
    void createDirectoryNamedDefaultNamesTheNameField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"default\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }

    @Test
    void createDirectoryNamedSlashNamesTheNameField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/media/directories")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }

    @Test
    void patchWithABlankNameNamesTheNameField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        MediaFile file = aMediaFile(dir, "dog.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/media/{id}", "file-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }
}
