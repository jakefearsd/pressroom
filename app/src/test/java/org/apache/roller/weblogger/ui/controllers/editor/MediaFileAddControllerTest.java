/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileAddController}.
 *
 * <p>This is the one place in the editor where a user hands the server a file
 * and a filename. The filename is used to build a path on disk, so the
 * traversal check ("no slashes, no ..", and truncate at any embedded NUL) is a
 * security control, not a nicety: without it an upload named
 * {@code ../../../etc/cron.d/x} escapes the weblog's upload directory. The
 * content-type fallback matters for a different reason — browsers frequently
 * send {@code application/octet-stream}, and storing that would make every
 * image download instead of display.
 */
class MediaFileAddControllerTest extends EditorControllerTestSupport {

    /**
     * A literal NUL, spelled out rather than embedded in the source: an actual
     * 0x00 byte in a .java file is invisible in every editor and diff tool, and
     * these tests exist precisely to prove it is handled.
     */
    private static final String NUL = String.valueOf((char) 0);

    private MediaFileAddController controller;
    private MediaFileBean bean;
    private Model model;
    private MediaFileDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileAddController());
        bean = new MediaFileBean();
        model = newModel();

        directory = new MediaFileDirectory();
        directory.setId("dir-1");
        directory.setName("default");
        directory.setWeblog(weblog);

        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1")).thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(Collections.emptyList());

        // Uploads live behind a runtime switch that defaults to off in an
        // unstubbed business tier; most tests here need it on.
        givenRuntimeProperty("uploads.enabled", "true");
    }

    @Test
    void theUploadFormResolvesTheDefaultDirectoryWhenNoneWasChosen() {
        String view = controller.execute(request, model, bean);

        assertEquals(".MediaFileAdd", view);
        assertEquals(directory, model.getAttribute("directory"));
        assertEquals("dir-1", bean.getDirectoryId(),
                "The resolved directory must be written back to the form so the subsequent "
                        + "POST uploads into the folder the user was looking at");
    }

    @Test
    void theUploadFormCreatesADefaultDirectoryForABrandNewWeblog() throws Exception {
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(null);
        when(weblogger.getMediaFileManager().createDefaultMediaFileDirectory(weblog))
                .thenReturn(directory);

        controller.execute(request, model, bean);

        verify(weblogger.getMediaFileManager()).createDefaultMediaFileDirectory(weblog);
        assertEquals(directory, model.getAttribute("directory"));
    }

    @Test
    void uploadingIsRefusedWhenTheSiteAdminHasTurnedUploadsOff() throws Exception {
        givenRuntimeProperty("uploads.enabled", "false");

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "data")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("error.upload.disabled"),
                "Expected the uploads-disabled error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aValidUploadIsStoredAgainstTheWeblogAndDirectory() throws Exception {
        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(".MediaFileAddSuccess", view);
        MediaFile stored = captureCreatedFile();
        assertEquals("photo.jpg", stored.getName());
        assertEquals("image/jpeg", stored.getContentType());
        assertEquals(weblog, stored.getWeblog());
        assertEquals(directory, stored.getDirectory(),
                "An upload with no directory must land in the resolved default, not nowhere");
        assertEquals("binary".length(), stored.getLength());
    }

    @Test
    void aFilenameContainingASlashIsRefused() throws Exception {
        // Path traversal: the name is used to build a path under the weblog's
        // upload directory.
        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("../../etc/passwd", "text/plain", "x")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameContainingABackslashIsRefused() throws Exception {
        // Windows-style separators have to be caught too; the check is not
        // about the host OS but about what the storage layer will interpret.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("..\\..\\windows\\system32\\evil.dll", "text/plain", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameContainingDotDotIsRefusedEvenWithoutASeparator() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo..jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameIsTruncatedAtAnEmbeddedNulByte() throws Exception {
        // The classic poison-NUL trick: "shell.jsp\0.gif" passes an extension
        // check but is written as "shell.jsp" by anything using C string
        // semantics. Roller truncates at the NUL so the stored name is
        // unambiguous.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg" + NUL + ".exe", "image/jpeg", "x")});

        assertEquals("photo.jpg", captureCreatedFile().getName(),
                "Everything from the NUL byte onwards must be discarded");
    }

    @Test
    void aNulTruncatedFilenameIsStillCheckedForTraversal() throws Exception {
        // Truncation happens first, so a payload hidden before the NUL must
        // still be caught.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("../evil" + NUL + ".jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Traversal must be detected on the truncated name: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void anOctetStreamContentTypeIsReplacedByOneInferredFromTheExtension() throws Exception {
        // Browsers send octet-stream for anything they do not recognise; storing
        // it would make the file download rather than render.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "application/octet-stream", "x")});

        assertEquals("image/jpeg", captureCreatedFile().getContentType(),
                "octet-stream must be replaced by the type implied by the extension");
    }

    @Test
    void aMissingContentTypeIsInferredFromTheExtension() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", null, "x")});

        assertEquals("image/jpeg", captureCreatedFile().getContentType());
    }

    @Test
    void aBrowserSuppliedContentTypeIsKeptWhenItIsSpecific() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/png", "x")});

        assertEquals("image/png", captureCreatedFile().getContentType(),
                "A specific content type from the browser must win over the extension guess");
    }

    @Test
    void anUnrecognisedExtensionKeepsTheOctetStreamContentType() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("archive.zzzz", "application/octet-stream", "x")});

        assertEquals("application/octet-stream", captureCreatedFile().getContentType(),
                "With nothing better to infer, the original type must be preserved rather "
                        + "than nulled");
    }

    @Test
    void emptyFileSlotsInTheFormAreSkipped() throws Exception {
        // The upload form renders several file inputs; the user typically fills
        // in one. The empty ones must not become zero-byte media files.
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        controller.save(request, model, bean,
                new MultipartFile[]{empty, upload("photo.jpg", "image/jpeg", "x"), null});

        verify(weblogger.getMediaFileManager(), org.mockito.Mockito.times(1))
                .createMediaFile(any(), any(), any());
    }

    @Test
    void submittingNoFilesAtAllJustRedisplaysTheForm() throws Exception {
        String view = controller.save(request, model, bean, new MultipartFile[0]);

        assertEquals(".MediaFileAdd", view);
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void uploadedImagesAndOtherFilesAreListedSeparatelyOnTheSuccessPage() throws Exception {
        // The success page shows a thumbnail grid for images and a plain list
        // for everything else.
        controller.save(request, model, bean, new MultipartFile[]{
                upload("photo.jpg", "image/jpeg", "x"),
                upload("notes.txt", "text/plain", "y")});

        assertEquals(1, ((java.util.List<?>) model.getAttribute("newImages")).size());
        assertEquals(1, ((java.util.List<?>) model.getAttribute("newFiles")).size());
    }

    @Test
    void aValidationFailureReportedByTheManagerStopsTheSuccessPage() throws Exception {
        // createMediaFile reports quota and file-type violations through the
        // RollerMessages collector rather than by throwing, so a controller that
        // only watched for exceptions would report success on a rejected upload.
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.forbiddenFile");
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("evil.exe", "application/octet-stream", "x")});

        assertEquals(".MediaFileAdd", view,
                "A rejected upload must not land on the success page");
        assertTrue(errors(model).contains("error.upload.forbiddenFile"),
                "The manager's complaint must reach the user: " + errors(model));
    }

    @Test
    void aFailureWhileStoringOneFileIsReportedAndDoesNotAbortTheBatch() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "x")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("mediaFileAdd.errorUploading"),
                "Expected an upload error, got: " + errors(model));
    }

    @Test
    void theFailureReportEscapesTheFilenameItNames() throws Exception {
        // The filename is whatever the browser sent in the multipart part --
        // client-supplied text, reflected straight into messages.jsp's
        // deliberately-raw error sink.
        registerMessage("mediaFileAdd.errorUploading", "Error uploading file {0}");
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        controller.save(request, model, bean,
                new MultipartFile[]{upload("<b>photo.jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("Error uploading file &lt;b&gt;photo.jpg"),
                "Expected the filename HTML-escaped, got: " + errors(model));
    }

    @Test
    void theFailureReportNamesTheFileThatFailedNotTheRemovedBeanName() throws Exception {
        // bean.getName() is always null now that the form's Name field is
        // gone; the error must name the upload that actually failed.
        registerMessage("mediaFileAdd.errorUploading", "Error uploading file {0}");
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        controller.save(request, model, bean,
                new MultipartFile[]{upload("vacation.jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("Error uploading file vacation.jpg"),
                "Expected the failed file's own name in the error, got: " + errors(model));
    }

    @Test
    void aBatchThatPartlySucceedsReportsBothTheLandedAndTheFailedFiles() throws Exception {
        // Thirty files where one is bad must not hide the other twenty-nine:
        // the success list and the failure must both reach the author.
        String view = controller.save(request, model, bean, new MultipartFile[]{
                upload("good.jpg", "image/jpeg", "x"),
                upload("bad/name.jpg", "image/jpeg", "y")});

        assertEquals(".MediaFileAddSuccess", view,
                "A batch with at least one landed file must still reach the success "
                        + "page so the author can act on what DID upload");
        assertEquals(1, ((java.util.List<?>) model.getAttribute("newImages")).size(),
                "Only the good file should be counted as landed");
        assertTrue(messages(model).contains("uploadFiles.uploadedFiles"),
                "The success list must still be reported: " + messages(model));
        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "The failure must ALSO be reported in the same response, not "
                        + "suppressed by the batch's partial success: " + errors(model));
    }

    @Test
    void aBatchThatFailsCompletelyStillRedisplaysTheFormWithEveryError() throws Exception {
        String view = controller.save(request, model, bean, new MultipartFile[]{
                upload("bad/one.jpg", "image/jpeg", "x"),
                upload("bad/two.jpg", "image/jpeg", "y")});

        assertEquals(".MediaFileAdd", view,
                "Nothing landed, so there is no success page to show");
        assertTrue(messages(model).isEmpty(),
                "No file landed; there must be no success message: " + messages(model));

        // The name of this test promises EVERY error, and that is the part
        // worth pinning: a batch reports per file, so a regression that
        // collapsed two rejections into one would leave the author fixing one
        // filename and resubmitting the other unchanged. Two files, two
        // errors -- count them, do not just check the list is non-empty.
        assertEquals(2, errors(model).stream()
                        .filter("uploadFiles.error.badPath"::equals).count(),
                "Each rejected file must be reported on its own: " + errors(model));

        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFileTheManagerRefusesIsNotCountedAsLandedEvenWithoutAnException() throws Exception {
        // createMediaFile reports a quota/forbidden-type refusal by adding to
        // the shared RollerMessages collector and returning normally, not by
        // throwing -- the controller must not count that file as uploaded just
        // because no exception was thrown.
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.forbiddenFile");
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("evil.exe", "application/octet-stream", "x")});

        assertEquals(".MediaFileAdd", view,
                "The one file offered was refused by the manager, so nothing landed");
        assertTrue(messages(model).isEmpty(),
                "A refused file must not be reported as a success: " + messages(model));
        assertTrue(errors(model).contains("error.upload.forbiddenFile"));
    }

    @Test
    void aQuotaRefusalReachesTheModelWithItsArgumentsFilledIn() throws Exception {
        // error.upload.dirmax carries a {0} placeholder for the configured
        // limit. Replaying msg.getKey() alone (dropping msg.getArgs()) would
        // leave the literal "{0}" in the rendered error -- this pins that the
        // argument actually reaches the resolved message.
        registerMessage("error.upload.dirmax",
                "You cannot exceed the maximum directory size of {0} MB.");
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.dirmax", "50");
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("You cannot exceed the maximum directory size of 50 MB."),
                "Expected the quota limit substituted into the message, got: " + errors(model));
    }

    @Test
    void aTwoArgumentRefusalReachesTheModelWithBothArgumentsFilledIn() throws Exception {
        // error.upload.forbiddenFile carries two placeholders (filename,
        // content-type); this is the shape that only the Object[]/String[]
        // addError overload can carry.
        registerMessage("error.upload.forbiddenFile",
                "File <b>{0}</b> content-type <b>{1}</b> not allowed");
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.forbiddenFile", new String[]{"evil.exe", "application/x-msdownload"});
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        controller.save(request, model, bean,
                new MultipartFile[]{upload("evil.exe", "application/octet-stream", "x")});

        assertTrue(errors(model).contains(
                        "File <b>evil.exe</b> content-type <b>application/x-msdownload</b> not allowed"),
                "Expected both arguments substituted into the message, got: " + errors(model));
    }

    /**
     * messages.jsp prints banners unescaped, and both refusals carry the
     * uploaded file's name -- text the browser sent, so markup in it would
     * render as markup.
     */
    @Test
    void aRefusedFilesNameIsEscapedInTheBanner() throws Exception {
        registerMessage("error.upload.forbiddenFile", "File <b>{0}</b> content-type <b>{1}</b> not allowed");
        registerMessage("uploadFiles.error.badPath", "Bad name: {0}");
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.forbiddenFile", new String[]{"<i>x.exe", "a/<b>"});
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        controller.save(request, model, bean, new MultipartFile[]{
                upload("<i>x.exe", "application/octet-stream", "x"),
                upload("<i>a</i>/b.jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains(
                        "File <b>&lt;i&gt;x.exe</b> content-type <b>a/&lt;b&gt;</b> not allowed"),
                "the manager's arguments must be escaped, got: " + errors(model));
        assertTrue(errors(model).contains("Bad name: &lt;i&gt;a&lt;/i&gt;/b.jpg"),
                "the refused name must be escaped, got: " + errors(model));
    }

    @Test
    void aManagerRefusalOfOneFileDoesNotHideAnotherFilesSuccessInTheSameBatch() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            MediaFile target = invocation.getArgument(1);
            if ("evil.exe".equals(target.getName())) {
                org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
                messages.addError("error.upload.forbiddenFile");
            }
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean, new MultipartFile[]{
                upload("good.jpg", "image/jpeg", "x"),
                upload("evil.exe", "application/octet-stream", "y")});

        assertEquals(".MediaFileAddSuccess", view,
                "The good file landed, so the success page must still show");
        assertEquals(1, ((java.util.List<?>) model.getAttribute("newImages")).size());
        assertTrue(errors(model).contains("error.upload.forbiddenFile"),
                "The refused file's error must still reach the author: " + errors(model));
    }

    @Test
    void cancellingReturnsToTheMediaFileList() {
        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE,
                controller.cancel(request, null));
    }

    @Test
    void cancellingReturnsToTheDirectoryTheUploadWasHeadedFor() {
        // Without the directory id, cancelling an upload into a nested folder
        // dropped the author back at the media root, to navigate down again.
        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE
                        + "&directoryId=dir-42",
                controller.cancel(request, "dir-42"));
    }

    @Test
    void cancellingEncodesADirectoryIdContainingAnAmpersand() {
        // directoryId used to be concatenated unencoded into the redirect
        // query string; a value containing '&' injected an extra query
        // parameter into the URL instead of round-tripping as one value.
        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE
                        + "&directoryId=dir%2642",
                controller.cancel(request, "dir&42"));
    }

    @Test
    void cancellingWithNoWeblogInContextStillProducesAUsableRedirect() {
        // The interceptor normally guarantees a weblog, but the cancel link is
        // the one path that tolerates its absence rather than throwing.
        when(request.getAttribute("actionWeblog")).thenReturn(null);

        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=",
                controller.cancel(request, null));
    }

    @Test
    void uploadingIntoAnotherWeblogsDirectoryIsRefused() throws Exception {
        // bean.directoryId is a hidden form field; getMediaFileDirectory is a
        // global by-id lookup, so without an ownership check an editor on
        // weblog A can write files into weblog B's folder.
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        MediaFileDirectory foreign = new MediaFileDirectory();
        foreign.setId("dir-x");
        foreign.setName("theirs");
        foreign.setWeblog(other);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x")).thenReturn(foreign);
        bean.setDirectoryId("dir-x");

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(".MediaFileAdd", view);
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected the upload to be refused, got: " + errors(model));
    }

    // --- upload!.rol: the editor's session JSON upload endpoint ---

    @Test
    void uploadReturns201AndTheImageShortcodeIdForAGoodFile() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller.upload(request, null,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(1, body.get("created"));
        assertEquals(0, body.get("failed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((java.util.List<Map<String, Object>>) body.get("results")).get(0);
        assertEquals("created", row.get("status"));
        assertTrue(row.get("id") != null && !row.get("id").toString().isBlank(),
                "Expected a non-blank id for the created file, got: " + row.get("id"));
    }

    @Test
    void uploadReturns404ForAForeignDirectory() throws Exception {
        // directoryId is client input and getMediaFileDirectory is a global
        // by-id lookup -- the same ownership rule save()/resolveDirectory
        // already enforces, exercised here on the new endpoint.
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        MediaFileDirectory foreign = new MediaFileDirectory();
        foreign.setId("dir-x");
        foreign.setName("theirs");
        foreign.setWeblog(other);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x")).thenReturn(foreign);

        ResponseEntity<Map<String, Object>> response = controller.upload(request, "dir-x",
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void uploadReturns403WhenUploadsAreDisabled() throws Exception {
        givenRuntimeProperty("uploads.enabled", "false");

        ResponseEntity<Map<String, Object>> response = controller.upload(request, null,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("uploads.disabled", response.getBody().get("error"));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void uploadReturns207WhenOneOfTwoFilesIsRefused() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            MediaFile target = invocation.getArgument(1);
            if ("evil.exe".equals(target.getName())) {
                org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
                messages.addError("error.upload.forbiddenFile");
            }
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        ResponseEntity<Map<String, Object>> response = controller.upload(request, null, new MultipartFile[]{
                upload("good.jpg", "image/jpeg", "x"),
                upload("evil.exe", "application/octet-stream", "y")});

        assertEquals(HttpStatus.MULTI_STATUS, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(1, body.get("created"));
        assertEquals(1, body.get("failed"));
    }

    // --- helpers ---

    private MediaFile captureCreatedFile() throws WebloggerException {
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(weblogger.getMediaFileManager()).createMediaFile(any(), captor.capture(), any());
        return captor.getValue();
    }

    private MultipartFile upload(String fileName, String contentType, String content)
            throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(fileName);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn((long) content.length());
        when(file.getInputStream())
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return file;
    }
}
