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
package com.wikantik.pressroom.ui.controllers.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Tests for {@link MediaFileEditController} and {@link MediaFileBean}.
 *
 * <p>Editing a media file can rename it, replace its contents, and move it
 * between folders. The duplicate-name check is what stops two files in one
 * folder from resolving to the same URL, and it has to exempt the file being
 * edited or no file could be saved without also being renamed.
 */
class MediaFileEditControllerTest extends EditorControllerTestSupport {

    private MediaFileEditController controller;
    private MediaFileBean bean;
    private Model model;
    private MediaFile mediaFile;
    private MediaFileDirectory directory;
    private MediaFileDirectory otherDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileEditController());
        bean = new MediaFileBean();
        model = newModel();

        directory = directory("dir-1", "default");
        otherDirectory = directory("dir-2", "photos");

        mediaFile = new MediaFile();
        mediaFile.setId("file-1");
        mediaFile.setName("photo.jpg");
        mediaFile.setContentType("image/jpeg");
        mediaFile.setWeblog(weblog);
        mediaFile.setDirectory(directory);
        directory.getMediaFiles().add(mediaFile);

        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(mediaFile);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1")).thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2")).thenReturn(otherDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void openingTheEditorLoadsTheFilesMetadata() {
        mediaFile.setDescription("A photo");
        mediaFile.setCopyrightText("(c) me");

        String view = controller.execute(request, model, bean, "file-1");

        assertEquals(".MediaFileEdit", view);
        assertEquals("photo.jpg", bean.getName());
        assertEquals("A photo", bean.getDescription());
        assertEquals("(c) me", bean.getCopyrightText());
        assertEquals("dir-1", bean.getDirectoryId());
        assertEquals("file-1", model.getAttribute("mediaFileId"));
    }

    @Test
    void openingTheEditorForAMissingFileReportsAnErrorRatherThanFailing() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("gone"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, "gone");

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("uploadFiles.error.upload"),
                "Expected an error, got: " + errors(model));
    }

    @Test
    void savingMetadataUpdatesTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");
        bean.setDescription("Updated description");
        bean.setCopyrightText("(c) someone");
        bean.setTagsAsString("holiday beach");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEditSuccess", view);
        assertEquals("Updated description", mediaFile.getDescription());
        assertEquals("(c) someone", mediaFile.getCopyrightText());
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, mediaFile);
        assertTrue(messages(model).contains("mediaFile.update.success"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void renamingOntoAnotherFilesNameInTheSameFolderIsRefused() throws Exception {
        // Two files with the same name in one folder collide on their public URL.
        MediaFile existing = new MediaFile();
        existing.setId("file-2");
        existing.setName("taken.jpg");
        existing.setWeblog(weblog);
        existing.setDirectory(directory);
        directory.getMediaFiles().add(existing);

        bean.setId("file-1");
        bean.setName("taken.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("MediaFile.error.duplicateName"),
                "Expected a duplicate-name error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    @Test
    void keepingAFilesOwnNameIsNotADuplicate() throws Exception {
        // The check has to exempt the file being edited, or saving any
        // metadata change without renaming would be impossible.
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEditSuccess", view);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void replacingTheFileContentsUpdatesLengthAndContentType() throws Exception {
        // The stored length and content type are served as Content-Length and
        // Content-Type; leaving the old values would truncate or mistype the
        // download.
        MultipartFile replacement = mock(MultipartFile.class);
        when(replacement.isEmpty()).thenReturn(false);
        when(replacement.getSize()).thenReturn(4096L);
        when(replacement.getContentType()).thenReturn("image/png");
        when(replacement.getInputStream())
                .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", replacement);

        assertEquals(4096L, mediaFile.getLength());
        assertEquals("image/png", mediaFile.getContentType());
        verify(weblogger.getMediaFileManager())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
    }

    @Test
    void savingWithNoReplacementFileDoesNotRewriteTheContents() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager(), never())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, mediaFile);
    }

    @Test
    void anEmptyReplacementFileIsTreatedAsNoReplacement() throws Exception {
        // The file input is always present in the form and arrives empty when
        // the user did not choose anything.
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", empty);

        verify(weblogger.getMediaFileManager(), never())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
    }

    @Test
    void changingTheDirectoryMovesTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-2");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager()).moveMediaFile(mediaFile, otherDirectory);
    }

    @Test
    void leavingTheDirectoryUnchangedDoesNotMoveTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
    }

    @Test
    void theDuplicateNameErrorEscapesTheNameItNames() throws Exception {
        // A media file's name is typed on this very form and lands in
        // messages.jsp's deliberately-raw sink.
        registerMessage("MediaFile.error.duplicateName", "duplicate:{0}");
        MediaFile existing = new MediaFile();
        existing.setId("file-2");
        existing.setName("<b>taken.jpg</b>");
        existing.setWeblog(weblog);
        existing.setDirectory(directory);
        directory.getMediaFiles().add(existing);

        bean.setId("file-1");
        bean.setName("<b>taken.jpg</b>");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", null);

        assertTrue(errors(model).contains("duplicate:&lt;b&gt;taken.jpg&lt;/b&gt;"),
                "Expected the file name HTML-escaped, got: " + errors(model));
    }

    @Test
    void aFailedUpdateIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).updateMediaFile(any(), any());

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("uploadFiles.error.upload"),
                "Expected an error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed update must not report success");
    }

    @Test
    void beanRoundTripsTheEditableFields() throws Exception {
        mediaFile.setDescription("A photo");
        mediaFile.setCopyrightText("(c) me");
        mediaFile.addTag("holiday");
        mediaFile.addTag("beach");
        mediaFile.setOriginalPath("/orig/photo.jpg");
        mediaFile.setLength(1234L);
        mediaFile.setAltText("A red kite riding the thermal");

        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile, weblogger.urlStrategy());

        assertEquals("file-1", copy.getId());
        assertEquals("photo.jpg", copy.getName());
        assertEquals("A photo", copy.getDescription());
        assertEquals("(c) me", copy.getCopyrightText());
        assertEquals("dir-1", copy.getDirectoryId());
        assertEquals("image/jpeg", copy.getContentType());
        assertEquals(1234L, copy.getLength());
        assertEquals("A red kite riding the thermal", copy.getAltText());
        assertTrue(copy.isIsImage(), "A jpeg must be recognised as an image for the gallery view");

        MediaFile target = new MediaFile();
        copy.copyTo(target, weblogger.mediaFileManager());

        assertEquals("photo.jpg", target.getName());
        assertEquals("A photo", target.getDescription());
        assertEquals("(c) me", target.getCopyrightText());
        assertEquals("/orig/photo.jpg", target.getOriginalPath());
        assertEquals("A red kite riding the thermal", target.getAltText());
    }

    @Test
    void beanCopyToOverwritesAltTextRatherThanLeavingAStaleValue() throws Exception {
        // Whatever the bean carries -- including blank, a cleared field --
        // must win outright; copyTo is a full overwrite, not a merge, and
        // W4's Testing section named this "the bean round trip" as a gap the
        // wave itself never closed (the only thing pinning it was a browser
        // test, which does not run on a push).
        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile, weblogger.urlStrategy());
        copy.setAltText("");

        MediaFile target = new MediaFile();
        target.setAltText("stale value from before the edit");
        copy.copyTo(target, weblogger.mediaFileManager());

        assertEquals("", target.getAltText());
    }

    @Test
    void beanCopyToDoesNotCarryTheDirectoryAcross() throws Exception {
        // Moving between folders is a separate operation with its own storage
        // side effects; a plain field copy would leave the file's row pointing
        // at a folder its bytes are not in.
        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile, weblogger.urlStrategy());
        copy.setDirectoryId("dir-2");

        MediaFile target = new MediaFile();
        copy.copyTo(target, weblogger.mediaFileManager());

        org.junit.jupiter.api.Assertions.assertNull(target.getDirectory(),
                "copyTo must leave the directory to the explicit move path");
    }

    // ------------------------------------------------------------------ crop

    @Test
    void croppingDelegatesToTheManagerAndConfirms() throws Exception {
        mediaFile.setWidth(500);
        mediaFile.setHeight(373);

        String view = controller.crop(request, model, bean, "file-1", 10, 20, 300, 200);

        assertEquals(".MediaFileEdit", view);
        verify(weblogger.getMediaFileManager())
                .cropMediaFile(weblog, mediaFile, 10, 20, 300, 200);
        assertTrue(messages(model).contains("mediaFileEdit.crop.success"),
                "Expected a crop confirmation, got: " + messages(model));
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void croppingRefreshesTheBeanSoTheFormShowsTheNewDimensions() throws Exception {
        // the manager mutates the entity's dimensions as part of the crop
        org.mockito.Mockito.doAnswer(invocation -> {
            mediaFile.setWidth(300);
            mediaFile.setHeight(200);
            return null;
        }).when(weblogger.getMediaFileManager())
                .cropMediaFile(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());

        controller.crop(request, model, bean, "file-1", 10, 20, 300, 200);

        assertEquals(300, bean.getWidth(),
                "the re-rendered form must show the post-crop dimensions");
        assertEquals(200, bean.getHeight());
    }

    @Test
    void aFailedCropIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("rectangle outside image"))
                .when(weblogger.getMediaFileManager())
                .cropMediaFile(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());

        String view = controller.crop(request, model, bean, "file-1", 600, 400, 100, 100);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("mediaFileEdit.crop.error"),
                "Expected a crop error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed crop must not also report success");
        // the form must still be populated for the re-render
        assertEquals("photo.jpg", bean.getName());
    }

    @Test
    void croppingAFileFromAnotherWeblogIsRefusedBeforeAnyWorkOrLeak() throws Exception {
        // getMediaFile is a global by-id lookup; a weblog-A editor supplying
        // a weblog-B media id must be stopped at the ownership boundary --
        // no crop, and no foreign metadata copied into weblog A's form.
        com.wikantik.pressroom.pojos.Weblog foreignWeblog =
                new com.wikantik.pressroom.pojos.Weblog();
        foreignWeblog.setId("weblog-2");
        foreignWeblog.setHandle("someoneelse");
        MediaFileDirectory foreignDir = directory("dir-foreign", "default");
        foreignDir.setWeblog(foreignWeblog);
        MediaFile foreignFile = new MediaFile();
        foreignFile.setId("file-foreign");
        foreignFile.setName("their-photo.jpg");
        foreignFile.setContentType("image/jpeg");
        foreignFile.setWeblog(foreignWeblog);
        foreignFile.setDirectory(foreignDir);
        when(weblogger.getMediaFileManager().getMediaFile("file-foreign"))
                .thenReturn(foreignFile);

        String view = controller.crop(request, model, bean, "file-foreign", 0, 0, 100, 100);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("mediaFileEdit.crop.error"),
                "Expected the crop to be refused, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).cropMediaFile(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        org.junit.jupiter.api.Assertions.assertNull(bean.getName(),
                "the foreign file's metadata must not leak into this weblog's form");
    }

    // ----------------------------------------------------------- focal point

    @Test
    void focalPointRoundTripsThroughTheBean() throws Exception {
        mediaFile.setFocalX(0.25);
        mediaFile.setFocalY(0.75);

        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile, weblogger.urlStrategy());
        assertEquals(0.25, copy.getFocalX());
        assertEquals(0.75, copy.getFocalY());

        MediaFile target = new MediaFile();
        copy.copyTo(target, weblogger.mediaFileManager());
        assertEquals(0.25, target.getFocalX());
        assertEquals(0.75, target.getFocalY());
    }

    @Test
    void savingThroughTheControllerPersistsTheFocalPoint() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");
        bean.setFocalX(0.3);
        bean.setFocalY(0.6);

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEditSuccess", view);
        assertEquals(0.3, mediaFile.getFocalX());
        assertEquals(0.6, mediaFile.getFocalY());
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, mediaFile);
    }

    @Test
    void clearingTheFocalPointPersistsNulls() throws Exception {
        mediaFile.setFocalX(0.3);
        mediaFile.setFocalY(0.6);

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");
        // the cleared form submits no focal parameters at all

        controller.save(request, model, bean, "file-1", null);

        org.junit.jupiter.api.Assertions.assertNull(mediaFile.getFocalX());
        org.junit.jupiter.api.Assertions.assertNull(mediaFile.getFocalY());
    }

    @Test
    void aLoneFocalCoordinateDegradesToNoFocalPoint() throws Exception {
        MediaFileBean lone = new MediaFileBean();
        lone.setFocalX(0.4);

        MediaFile target = new MediaFile();
        lone.copyTo(target, weblogger.mediaFileManager());

        org.junit.jupiter.api.Assertions.assertNull(target.getFocalX(),
                "half a focal point cannot position anything and must not persist");
        org.junit.jupiter.api.Assertions.assertNull(target.getFocalY());
    }

    @Test
    void focalCoordinatesAreClampedToTheirDocumentedRange() throws Exception {
        MediaFileBean outOfRange = new MediaFileBean();
        outOfRange.setFocalX(1.7);
        outOfRange.setFocalY(-0.2);

        MediaFile target = new MediaFile();
        outOfRange.copyTo(target, weblogger.mediaFileManager());

        assertEquals(1.0, target.getFocalX());
        assertEquals(0.0, target.getFocalY());
    }

    @Test
    void croppabilityFollowsTheLadderFormats() {
        MediaFileBean jpeg = new MediaFileBean();
        jpeg.setIsImage(true);
        jpeg.setContentType("image/jpeg");
        assertTrue(jpeg.isCroppable());

        MediaFileBean gif = new MediaFileBean();
        gif.setIsImage(true);
        gif.setContentType("image/gif");
        org.junit.jupiter.api.Assertions.assertFalse(gif.isCroppable(),
                "no crop UI for formats the server cannot re-encode");

        MediaFileBean pdf = new MediaFileBean();
        pdf.setIsImage(false);
        pdf.setContentType("application/pdf");
        org.junit.jupiter.api.Assertions.assertFalse(pdf.isCroppable());
    }

    // ------------------------------------------------- cross-weblog ids

    @Test
    void openingTheEditorForAFileFromAnotherWeblogLeaksNoMetadata() throws Exception {
        // getMediaFile is a global by-id lookup, and bean.copyFrom would put
        // the foreign file's name, description, copyright and tags straight
        // into weblog A's edit form.
        when(weblogger.getMediaFileManager().getMediaFile("file-foreign"))
                .thenReturn(foreignMediaFile());

        String view = controller.execute(request, model, bean, "file-foreign");

        assertEquals(".MediaFileEdit", view);
        org.junit.jupiter.api.Assertions.assertNull(bean.getName(),
                "the foreign file's metadata must not reach this weblog's form");
        org.junit.jupiter.api.Assertions.assertNull(model.getAttribute("mediaFileId"));
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected the load to be refused, got: " + errors(model));
    }

    @Test
    void savingAFileFromAnotherWeblogIsRefusedBeforeAnyWrite() throws Exception {
        MediaFile foreign = foreignMediaFile();
        when(weblogger.getMediaFileManager().getMediaFile("file-foreign")).thenReturn(foreign);

        bean.setId("file-foreign");
        bean.setName("renamed.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-foreign", null);

        assertEquals(".MediaFileEdit", view);
        assertEquals("their-photo.jpg", foreign.getName(),
                "a foreign file must not be rewritten from this weblog's form");
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected the save to be refused, got: " + errors(model));
    }

    @Test
    void savingIntoAnotherWeblogsFolderIsRefused() throws Exception {
        MediaFileDirectory foreignDir = directory("dir-foreign", "theirs");
        foreignDir.setWeblog(otherWeblog());
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-foreign"))
                .thenReturn(foreignDir);

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-foreign");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEdit", view);
        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected the save to be refused, got: " + errors(model));
    }

    private com.wikantik.pressroom.pojos.Weblog otherWeblog() {
        com.wikantik.pressroom.pojos.Weblog other =
                new com.wikantik.pressroom.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("someoneelse");
        return other;
    }

    private MediaFile foreignMediaFile() {
        com.wikantik.pressroom.pojos.Weblog other = otherWeblog();
        MediaFileDirectory foreignDir = directory("dir-foreign", "default");
        foreignDir.setWeblog(other);
        MediaFile foreign = new MediaFile();
        foreign.setId("file-foreign");
        foreign.setName("their-photo.jpg");
        foreign.setContentType("image/jpeg");
        foreign.setWeblog(other);
        foreign.setDirectory(foreignDir);
        return foreign;
    }

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        Set<MediaFile> files = new LinkedHashSet<>();
        dir.setMediaFiles(files);
        return dir;
    }
}
