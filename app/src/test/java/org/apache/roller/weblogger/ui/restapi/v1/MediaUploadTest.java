package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.MediaDtos;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;

/**
 * A batch is not a transaction. createMediaFile reports quota and
 * forbidden-extension refusals through RollerMessages WITHOUT throwing, so
 * the absence of an exception proves nothing -- the error count has to be
 * snapshotted around each call. Before the bulk-upload work in W4, one bad
 * file suppressed the entire success list.
 */
class MediaUploadTest {

    @Test
    void aPartlyFailedBatchReportsBothHalves() {
        var results = List.of(
                new MediaDtos.UploadResult("good.jpg", "created", null, null),
                new MediaDtos.UploadResult("huge.jpg", "quota_exceeded",
                        "Adding 4.2 MB would exceed this weblog's limit.", null),
                new MediaDtos.UploadResult("evil.exe", "forbidden_extension",
                        "Files of this type may not be uploaded.", null));

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);

        assertEquals(1, response.created());
        assertEquals(2, response.failed());
        assertEquals(3, response.results().size());
    }

    @Test
    void anAllSuccessBatchReportsNoFailures() {
        var results = List.of(
                new MediaDtos.UploadResult("a.jpg", "created", null, null),
                new MediaDtos.UploadResult("b.jpg", "created", null, null));

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);

        assertEquals(2, response.created());
        assertEquals(0, response.failed());
    }

    // -----------------------------------------------------------------
    // MediaDtos.refusal: which message keys map to which status. Found by
    // grepping FileContentManagerImpl.canSave -- see the task report for
    // the exact grep and the keys it turned up.
    // -----------------------------------------------------------------

    @Test
    void aDirectoryQuotaErrorMapsToQuotaExceeded() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.dirmax", "500");
        MediaDtos.UploadResult result = MediaDtos.refusal("huge.jpg", messages);
        assertEquals("quota_exceeded", result.status());
        assertTrue(result.detail().contains("500"));
    }

    @Test
    void aPerFileMaxSizeErrorMapsToQuotaExceeded() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.filemax", new String[] {"huge.jpg", "10"});
        MediaDtos.UploadResult result = MediaDtos.refusal("huge.jpg", messages);
        assertEquals("quota_exceeded", result.status());
    }

    @Test
    void aForbiddenFileTypeErrorMapsToForbiddenExtension() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.forbiddenFile", new String[] {"evil.exe", "application/octet-stream"});
        MediaDtos.UploadResult result = MediaDtos.refusal("evil.exe", messages);
        assertEquals("forbidden_extension", result.status());
    }

    @Test
    void anUploadsDisabledErrorMapsToError() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.disabled");
        MediaDtos.UploadResult result = MediaDtos.refusal("anything.jpg", messages);
        assertEquals("error", result.status());
    }

    @Test
    void refusalReadsTheNewestErrorNotTheFirst() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.dirmax", "500"); // some earlier file's refusal
        messages.addError("error.upload.forbiddenFile", new String[] {"evil.exe", "x"});
        MediaDtos.UploadResult result = MediaDtos.refusal("evil.exe", messages);
        assertEquals("forbidden_extension", result.status());
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, per-file reporting,
    // and the guards against ordinary client input that would otherwise
    // reach the manager and 500. Mirrors MediaApiTest's standalone style.
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
        when(urlStrategy.getMediaFileURL(any(), any(), anyBoolean()))
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

    @Test
    void uploadOfTwoGoodFilesReturns201AndPersistsBoth() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        MockMultipartFile a = new MockMultipartFile("file", "a.jpg", "image/jpeg", "aaa".getBytes());
        MockMultipartFile b = new MockMultipartFile("file", "b.jpg", "image/jpeg", "bbb".getBytes());

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(a).file(b)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(2, json.get("created").asInt());
        assertEquals(0, json.get("failed").asInt());
        assertEquals(2, json.get("results").size());
        assertEquals("created", json.get("results").get(0).get("status").asString());
        assertEquals("created", json.get("results").get(1).get("status").asString());

        verify(weblogger.getMediaFileManager(), times(2))
                .createMediaFile(eq(weblog), any(MediaFile.class), any(RollerMessages.class));
        // upload() only begins a JPA transaction; without flush() a
        // "successful" 201 batch would be rolled back by
        // PersistenceSessionFilter's end-of-request release (whole-branch
        // review, Must Fix 7).
        verify(weblogger).flush();
    }

    /**
     * The case the whole task is about: createMediaFile reports a refusal
     * through the shared RollerMessages collector WITHOUT throwing, so the
     * response must be 207 and must report the file that landed AND the
     * one that did not -- not suppress the success because something else
     * in the batch failed.
     */
    @Test
    void uploadWithOneRefusalReturns207AndReportsBothHalves() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        MediaFileManager mfm = weblogger.getMediaFileManager();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(mfm.getDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        // The second file is refused for quota: createMediaFile adds an
        // error to the shared collector and returns normally, exactly the
        // "no exception" trap this task exists to defend against.
        org.mockito.Mockito.doAnswer(inv -> {
            MediaFile file = inv.getArgument(1);
            RollerMessages messages = inv.getArgument(2);
            if ("huge.jpg".equals(file.getName())) {
                messages.addError("error.upload.dirmax", "500");
            }
            return null;
        }).when(mfm).createMediaFile(eq(weblog), any(MediaFile.class), any(RollerMessages.class));

        MockMultipartFile good = new MockMultipartFile("file", "good.jpg", "image/jpeg", "aaa".getBytes());
        MockMultipartFile huge = new MockMultipartFile("file", "huge.jpg", "image/jpeg", "bbb".getBytes());

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(good).file(huge)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isMultiStatus())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("created").asInt());
        assertEquals(1, json.get("failed").asInt());
        var results = json.get("results");
        assertEquals(2, results.size());
        assertEquals("good.jpg", results.get(0).get("fileName").asString());
        assertEquals("created", results.get(0).get("status").asString());
        assertEquals("huge.jpg", results.get(1).get("fileName").asString());
        assertEquals("quota_exceeded", results.get(1).get("status").asString());
        assertNull(results.get(1).get("file"));
    }

    @Test
    void uploadWithNoFilesIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(any(), any(), any());
        verify(weblogger, never()).flush();
    }

    /**
     * Guard: a directoryId is client input and the lookup is global, same
     * shape as PATCH's directoryId guard on this same controller -- a
     * foreign directory must read as 404, never leak into which files a
     * caller can drop into someone else's weblog.
     */
    @Test
    void uploadWithAForeignDirectoryIdIsNotFound() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        MediaFileDirectory foreign = aDirectory(anotherWeblog, "secret");
        when(weblogger.getMediaFileManager().getMediaFileDirectory(foreign.getId())).thenReturn(foreign);

        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", "aaa".getBytes());

        mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(file)
                        .param("directoryId", foreign.getId())
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(any(), any(), any());
    }

    @Test
    void uploadWithNoDirectoryIdUsesTheDefaultDirectory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", "aaa".getBytes());

        mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(file)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isCreated());

        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(weblogger.getMediaFileManager())
                .createMediaFile(eq(weblog), captor.capture(), any(RollerMessages.class));
        assertEquals(dir, captor.getValue().getDirectory());
        verify(weblogger.getMediaFileManager(), never()).createDefaultMediaFileDirectory(any());
    }

    @Test
    void uploadCreatesTheDefaultDirectoryWhenTheWeblogHasNoneYet() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(null);
        when(weblogger.getMediaFileManager().createDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", "aaa".getBytes());

        mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(file)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isCreated());

        verify(weblogger.getMediaFileManager()).createDefaultMediaFileDirectory(weblog);
    }

    /**
     * Guard: a blank/missing original filename never reaches the manager.
     * FileContentManagerImpl.canSave's extension check calls
     * fileName.toLowerCase() unconditionally once any allow/forbid list is
     * configured, which throws a bare NullPointerException for a null
     * filename -- an opaque 500 for input this ordinary (an unselected file
     * input still submits an empty part). Verified by writing this test
     * first and confirming a naive implementation without the guard fails
     * it -- see the task report.
     */
    @Test
    void uploadWithABlankFileNameIsReportedAsAnErrorWithoutReachingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        MockMultipartFile blank = new MockMultipartFile("file", "", "image/jpeg", "aaa".getBytes());

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(blank)
                        .requestAttr("actionWeblog", weblog))
                // Nothing landed, so the whole batch reports failure via 207
                // (its only member failed) rather than 201.
                .andExpect(status().isMultiStatus())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(0, json.get("created").asInt());
        assertEquals(1, json.get("failed").asInt());
        assertEquals("error", json.get("results").get(0).get("status").asString());

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(any(), any(), any());
    }

    /**
     * Guard: a zero-byte file never reaches the manager either -- there is
     * nothing meaningful to persist, and for an image content-type it would
     * otherwise sail through createMediaFile and only fail silently much
     * later inside thumbnail generation (caught-and-logged there, so the
     * request would "succeed" with a phantom, thumbnail-less media file).
     */
    @Test
    void uploadWithAZeroByteFileIsReportedAsAnErrorWithoutReachingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);

        MockMultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(empty)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isMultiStatus())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(0, json.get("created").asInt());
        assertEquals(1, json.get("failed").asInt());
        assertEquals("error", json.get("results").get(0).get("status").asString());

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(any(), any(), any());
    }

    /**
     * Whole-branch review, Must Fix 1: roller_mediafile.name is varchar(255)
     * NOT NULL. A batch is not a transaction (see class javadoc) -- one
     * oversized filename must be reported as that ONE file's failure, not
     * reach the manager and not affect the other files in the same batch.
     */
    @Test
    void uploadWithAFileNameLongerThanTheColumnIsReportedAsAnErrorWithoutReachingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);
        String tooLong = "a".repeat(300) + ".jpg";

        MockMultipartFile good = new MockMultipartFile("file", "good.jpg", "image/jpeg", "aaa".getBytes());
        MockMultipartFile huge = new MockMultipartFile("file", tooLong, "image/jpeg", "bbb".getBytes());

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(good).file(huge)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isMultiStatus())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("created").asInt());
        assertEquals(1, json.get("failed").asInt());
        assertEquals("error", json.get("results").get(1).get("status").asString());

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(eq(weblog), org.mockito.ArgumentMatchers.argThat(
                        f -> tooLong.equals(f.getName())), any(RollerMessages.class));
    }

    /**
     * Whole-branch review, Must Fix 1: roller_mediafile.content_type is
     * varchar(50) -- much shorter than name/description -- and a real
     * browser-supplied MIME type for an ordinary document (e.g. a .docx's,
     * 73 characters) overflows it. Before this guard, that reached
     * createMediaFile and threw a bare WebloggerException that upload()'s
     * per-file loop does not catch, killing the WHOLE batch's 207 rather
     * than failing just this one row -- the opposite of the isolation every
     * other guard in this class exists to prove. Must be a per-file
     * UploadResult, not a request-level 400.
     */
    @Test
    void uploadWithAContentTypeLongerThanTheColumnIsReportedAsAnErrorWithoutReachingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);
        String longContentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.extra";
        assertTrue(longContentType.length() > 50, "fixture must actually overflow content_type varchar(50)");

        MockMultipartFile good = new MockMultipartFile("file", "good.jpg", "image/jpeg", "aaa".getBytes());
        MockMultipartFile odd = new MockMultipartFile("file", "report.docx", longContentType, "bbb".getBytes());

        String body = mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").file(good).file(odd)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isMultiStatus())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("created").asInt());
        assertEquals(1, json.get("failed").asInt());
        assertEquals("good.jpg", json.get("results").get(0).get("fileName").asString());
        assertEquals("created", json.get("results").get(0).get("status").asString());
        assertEquals("report.docx", json.get("results").get(1).get("fileName").asString());
        assertEquals("error", json.get("results").get(1).get("status").asString());

        verify(weblogger.getMediaFileManager(), never())
                .createMediaFile(eq(weblog), org.mockito.ArgumentMatchers.argThat(
                        f -> "report.docx".equals(f.getName())), any(RollerMessages.class));
    }

    /**
     * Parked-item fix: {@code buildMediaFile} opens {@code
     * upload.getInputStream()} unconditionally, and it used to run BEFORE
     * the content-type-length refusal above -- a disk-backed multipart part
     * that gets refused for its content type held an open file descriptor
     * until GC, the only per-file refusal in this class that opened the
     * stream at all. Driven through the controller method directly (not
     * MockMvc's {@code multipart()}, which only accepts a real body) so a
     * Mockito mock of {@code MultipartFile} can prove {@code
     * getInputStream()} is never called on the refused file -- the other
     * file in the same batch still lands, so the refusal is still a
     * per-file {@code UploadResult}, not a request-level failure.
     */
    @Test
    void uploadWithATooLongContentTypeNeverOpensThatFilesInputStream() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        MediaFileDirectory dir = aDirectory(weblog, "default");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(dir);
        String longContentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.extra";
        assertTrue(longContentType.length() > 50, "fixture must actually overflow content_type varchar(50)");

        MockMultipartFile good = new MockMultipartFile("file", "good.jpg", "image/jpeg", "aaa".getBytes());
        MultipartFile odd = mock(MultipartFile.class);
        when(odd.getOriginalFilename()).thenReturn("report.docx");
        when(odd.isEmpty()).thenReturn(false);
        when(odd.getContentType()).thenReturn(longContentType);
        when(odd.getSize()).thenReturn(3L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("actionWeblog", weblog);

        var response = controllerFor(weblogger).upload(request, new MultipartFile[] { good, odd }, null);

        assertEquals(org.springframework.http.HttpStatus.MULTI_STATUS, response.getStatusCode());
        assertEquals(1, response.getBody().created());
        assertEquals(1, response.getBody().failed());
        verify(odd, never()).getInputStream();
    }

    @Test
    void uploadWithNoFilesNamesTheFileField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(multipart("/v1/weblogs/myblog/media").requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("file"));
    }
}
