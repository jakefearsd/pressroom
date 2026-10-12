package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.MediaDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
     * Whole-branch review, Must Fix 1: roller_mediafile carries no dedicated
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
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * roller_mediafiledir.name/description are both varchar(255)
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
}
