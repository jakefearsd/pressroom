package org.apache.roller.weblogger.ui.controllers;

import java.io.IOException;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which uploads {@link MediaUploads} refuses before the manager sees them,
 * how it reads the manager's refusal back, and how it resolves a directory.
 * Every refusal here is per file: it comes back as a {@link MediaUploads.Result},
 * so one bad file cannot take a whole batch down with it.
 */
class MediaUploadsDecisionTest {

    private final MediaFileManager mfm = mock(MediaFileManager.class);
    private final Weblog weblog = weblog("uploadblog");
    private final MediaFileDirectory directory = new MediaFileDirectory(weblog, "default", null);

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private MediaUploads.Result store(MediaUploads.Source source) throws Exception {
        return MediaUploads.store(source, weblog, directory, mfm, new RollerMessages());
    }

    @Test
    void aMissingFileNameIsNamedUnnamedAndNeverReachesTheManager() throws Exception {
        MediaUploads.Result result = store(MediaUploads.Source.of(null, "image/png", new byte[] {1}));

        assertEquals("(unnamed)", result.fileName());
        assertEquals("error", result.status());
        verify(mfm, never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aBlankFileNameKeepsItsTextInTheResult() throws Exception {
        MediaUploads.Result result = store(MediaUploads.Source.of("  ", "image/png", new byte[] {1}));

        assertEquals("  ", result.fileName());
        assertEquals("A file name is required.", result.detail());
    }

    @Test
    void aFileNameLongerThanTheColumnIsRefusedAtExactlyThatLimit() throws Exception {
        MediaUploads.Result tooLong = store(MediaUploads.Source.of("n".repeat(256), "image/png", new byte[] {1}));
        MediaUploads.Result atLimit = store(MediaUploads.Source.of("n".repeat(255), "image/png", new byte[] {1}));

        assertEquals("error", tooLong.status());
        assertTrue(tooLong.detail().contains("255"), tooLong.detail());
        assertEquals("created", atLimit.status());
    }

    @Test
    void aContentTypeLongerThanTheColumnIsRefusedBeforeTheStreamIsOpened() throws Exception {
        String docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        IOException[] opened = new IOException[1];
        MediaUploads.Source source = new MediaUploads.Source() {
            @Override public String fileName() { return "report.docx"; }
            @Override public String contentType() { return docx; }
            @Override public long size() { return 3; }
            @Override public boolean isEmpty() { return false; }
            @Override public java.io.InputStream openStream() throws IOException {
                opened[0] = new IOException("stream opened");
                throw opened[0];
            }
        };

        MediaUploads.Result result = store(source);

        assertEquals("error", result.status());
        assertTrue(result.detail().contains("50 characters"), result.detail());
        assertNull(opened[0], "the refusal must come before the file is opened");
    }

    @Test
    void aGenericOrMissingContentTypeIsGuessedFromTheFileName() throws Exception {
        MediaUploads.Result generic = store(MediaUploads.Source.of("pic.png", "application/octet-stream", new byte[] {1}));
        MediaUploads.Result missing = store(MediaUploads.Source.of("pic.jpg", null, new byte[] {1}));
        MediaUploads.Result unknown = store(MediaUploads.Source.of("blob.zzzz", null, new byte[] {1}));

        assertEquals("image/png", generic.file().getContentType());
        assertEquals("image/jpeg", missing.file().getContentType());
        assertEquals("application/octet-stream", unknown.file().getContentType(), "nothing to guess from: the generic type");
    }

    @Test
    void aStreamThatCannotBeOpenedFailsTheCallNotTheWholeBatchSilently() {
        MediaUploads.Source source = new MediaUploads.Source() {
            @Override public String fileName() { return "a.png"; }
            @Override public String contentType() { return "image/png"; }
            @Override public long size() { return 3; }
            @Override public boolean isEmpty() { return false; }
            @Override public java.io.InputStream openStream() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThrows(WebloggerException.class, () -> store(source));
    }

    @Test
    void aMultipartPartIsStoredUnderItsOriginalNameAndType() throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", "photo.gif", "image/gif", new byte[] {1, 2, 3});

        MediaUploads.Result result = MediaUploads.store(part, weblog, directory, mfm, new RollerMessages());

        assertEquals("created", result.status());
        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(mfm).createMediaFile(any(), created.capture(), any());
        assertEquals(3, created.getValue().getLength());
        assertSame(directory, created.getValue().getDirectory());
    }

    // ---- reading the manager's refusal back

    private MediaUploads.Result refusedWith(String key, String... args) throws Exception {
        doAnswer(inv -> {
            ((RollerMessages) inv.getArgument(2)).addError(key, args);
            return null;
        }).when(mfm).createMediaFile(any(), any(), any());
        return store(MediaUploads.Source.of("a.png", "image/png", new byte[] {1}));
    }

    @Test
    void theQuotaAndPerFileSizeRefusalsAreBothQuotaExceededWithTheirLimits() throws Exception {
        MediaUploads.Result dir = refusedWith("error.upload.dirmax", "20", "2");
        assertEquals("quota_exceeded", dir.status());
        assertTrue(dir.detail().contains("20 MB storage limit"), dir.detail());
        assertNull(dir.file());

        MediaUploads.Result file = refusedWith("error.upload.filemax", "20", "2");
        assertEquals("quota_exceeded", file.status());
        assertTrue(file.detail().contains("2 MB per-file limit"), file.detail());
    }

    @Test
    void aRefusalWithoutItsArgumentsStillReadsSensibly() throws Exception {
        assertEquals("Adding this file would exceed this weblog's storage limit.",
                refusedWith("error.upload.dirmax").detail());
        assertEquals("This file is larger than the per-file size limit.",
                refusedWith("error.upload.filemax", "20").detail());
    }

    /**
     * The author sees these strings verbatim (the editor's paste/drop shows
     * them), so they come from the message bundle, in the locale asked for,
     * and a message key the mapping does not know is never shown as text.
     */
    @Test
    void everyDetailComesFromTheBundleAndNoKeyIsEverShown() throws Exception {
        java.util.Properties bundle = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/ApplicationResources.properties")) {
            bundle.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(bundle.getProperty("mediaUploads.detail.forbiddenFile"),
                refusedWith("error.upload.forbiddenFile").detail());
        assertEquals(bundle.getProperty("mediaUploads.detail.disabled"),
                refusedWith("error.upload.disabled").detail());
        assertEquals(bundle.getProperty("error.upload.failed"),
                refusedWith("error.upload.somethingNew").detail());

        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.dirmax", new String[] {"20", "2"});
        assertEquals("Adding this file would exceed this weblog's 20 MB storage limit.",
                MediaUploads.refusal("a.png", messages, java.util.Locale.GERMAN).detail(),
                "a locale with no translation falls back to the default bundle");
    }

    @Test
    void forbiddenDisabledAndUnknownRefusalsEachGetTheirOwnStatusAndText() throws Exception {
        MediaUploads.Result forbidden = refusedWith("error.upload.forbiddenFile");
        assertEquals("forbidden_extension", forbidden.status());
        assertEquals("Files of this type may not be uploaded.", forbidden.detail());

        MediaUploads.Result disabled = refusedWith("error.upload.disabled");
        assertEquals("error", disabled.status());
        assertEquals("File upload is disabled for this site.", disabled.detail());

        MediaUploads.Result other = refusedWith("error.upload.somethingNew");
        assertEquals("error", other.status());
        assertEquals("Upload failed.", other.detail());
        assertFalse(other.detail().contains("error.upload"), "a message key is never shown to the author");
    }

    @Test
    void theNewestErrorIsTheOneReportedForThisFile() throws Exception {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.upload.forbiddenFile");
        messages.addError("error.upload.dirmax", new String[] {"20", "2"});

        assertEquals("quota_exceeded", MediaUploads.refusal("a.png", messages).status());
    }

    @Test
    void aRefusalWithNoMessageAtAllStillSaysTheUploadFailed() {
        MediaUploads.Result result = MediaUploads.refusal("a.png", new RollerMessages());

        assertEquals("error", result.status());
        assertNotNull(result.detail());
        assertTrue(!result.detail().isBlank(), "the detail comes from the message bundle");
    }

    // ---- directories

    @Test
    void theDefaultDirectoryIsCreatedOnFirstUseAndReusedAfter() throws Exception {
        MediaFileDirectory created = new MediaFileDirectory(weblog, "default", null);
        when(mfm.createDefaultMediaFileDirectory(weblog)).thenReturn(created);

        assertSame(created, MediaUploads.defaultDirectory(weblog, mfm));

        when(mfm.getDefaultMediaFileDirectory(weblog)).thenReturn(directory);
        assertSame(directory, MediaUploads.defaultDirectory(weblog, mfm));
    }

    @Test
    void aDirectoryIsResolvedOnlyForItsOwnWeblog() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mfm);
        MediaFileDirectory foreign = new MediaFileDirectory(weblog("someoneelse"), "theirs", null);
        MediaFileDirectory floating = mock(MediaFileDirectory.class);
        when(mfm.getMediaFileDirectory("mine")).thenReturn(directory);
        when(mfm.getMediaFileDirectory("theirs")).thenReturn(foreign);
        when(mfm.getMediaFileDirectory("floating")).thenReturn(floating);

        assertSame(directory, MediaUploads.directoryFor(weblogger, weblog, "mine"));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, "theirs"));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, "floating"));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, "unknown"));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, " "));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, null));
    }
}
