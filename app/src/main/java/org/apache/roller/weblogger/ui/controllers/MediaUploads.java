/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.I18nMessages;
import org.apache.roller.weblogger.util.RollerMessages;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.web.multipart.MultipartFile;

/**
 * The per-file upload logic shared by the automation API ({@code MediaApi})
 * and the editor's session upload endpoint ({@code
 * MediaFileAddController#upload}), extracted from {@code MediaApi} (Task
 * A6) so the two surfaces cannot drift on what a refusal is -- the same
 * precedent as {@link EntryFieldRules} and {@link WeblogOwnership}.
 *
 * <p>This class lives in {@code ui.controllers} rather than {@code
 * ui.restapi}, on purpose: {@code ui.controllers} may carry at most one
 * import from {@code ui.restapi} (see {@code RollerHandlerInterceptor}'s
 * {@code ApiException} import and CLAUDE.md's note on it), so nothing here
 * may depend on a {@code ui.restapi} type -- including {@code MediaDtos} and
 * {@code ColumnLimits}. The direction that IS allowed, and the one this
 * class relies on, is {@code ui.restapi} calling INTO {@code ui.controllers}
 * -- {@code MediaDtos.refusal} delegates to {@link #refusal} here for
 * exactly that reason, rather than the two packages each keeping their own
 * copy of the message-key-to-status mapping.
 */
public final class MediaUploads {

    // roller_mediafile.name / content_type column widths
    // (bin/db/migrations/V002__baseline_schema.sql). Kept as local constants
    // rather than importing org.apache.roller.weblogger.ui.restapi.ColumnLimits
    // -- see the class javadoc on why ui.controllers may not import ui.restapi.
    private static final int MEDIA_NAME_MAX = 255;
    private static final int MEDIA_CONTENT_TYPE_MAX = 50;

    private MediaUploads() {
    }

    /** The bytes of one file to store, wherever they came from: a multipart part or a fetched image. */
    public interface Source {
        String fileName();

        String contentType();

        long size();

        boolean isEmpty();

        InputStream openStream() throws IOException;

        static Source of(MultipartFile upload) {
            return new Source() {
                @Override
                public String fileName() {
                    return upload.getOriginalFilename();
                }

                @Override
                public String contentType() {
                    return upload.getContentType();
                }

                @Override
                public long size() {
                    return upload.getSize();
                }

                @Override
                public boolean isEmpty() {
                    return upload.isEmpty();
                }

                @Override
                public InputStream openStream() throws IOException {
                    return upload.getInputStream();
                }
            };
        }

        static Source of(String fileName, String contentType, byte[] bytes) {
            return new Source() {
                @Override
                public String fileName() {
                    return fileName;
                }

                @Override
                public String contentType() {
                    return contentType;
                }

                @Override
                public long size() {
                    return bytes.length;
                }

                @Override
                public boolean isEmpty() {
                    return bytes.length == 0;
                }

                @Override
                public InputStream openStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }

    /** The per-file limit in bytes, computed exactly as FileContentManagerImpl.canSave computes it. */
    public static long maxFileBytes() {
        BigDecimal maxFileMB = new BigDecimal(WebloggerRuntimeConfig.getProperty("uploads.file.maxsize"));
        return (long) (RollerConstants.ONE_MB_IN_BYTES * maxFileMB.doubleValue());
    }

    /**
     * One uploaded file's outcome. {@code file} is populated only when
     * {@code status} is {@code "created"} -- a refused file was never
     * persisted, so there is nothing to return.
     */
    public record Result(String fileName, String status, String detail, MediaFile file) {
    }

    /**
     * One file, one result, never throws for a per-file refusal. A blank or
     * missing original filename, or a zero-byte file, never reaches the
     * manager at all -- both are ordinary client mistakes, and a blank
     * filename reaching {@code FileContentManagerImpl.canSave}'s extension
     * check throws a bare {@code NullPointerException} (it calls {@code
     * fileName.toLowerCase()} unconditionally once any allow/forbid list is
     * configured), an opaque 500 for input this ordinary rather than a
     * refusal {@link #refusal} could map. Anything else {@code
     * createMediaFile} itself refuses is read back from the shared {@code
     * RollerMessages} collector via the before/after error-count snapshot
     * -- see the callers' javadoc for the batch-is-not-a-transaction
     * rationale.
     */
    public static Result store(MultipartFile upload, Weblog weblog, MediaFileDirectory directory,
            MediaFileManager mfm, RollerMessages messages) throws WebloggerException {
        return store(Source.of(upload), weblog, directory, mfm, messages);
    }

    /**
     * {@link #store(MultipartFile, Weblog, MediaFileDirectory, MediaFileManager, RollerMessages)}
     * with each refusal's detail in {@code locale}: the editor's paste/drop
     * shows the detail to the author, so it passes the request's locale.
     */
    public static Result store(MultipartFile upload, Weblog weblog, MediaFileDirectory directory,
            MediaFileManager mfm, RollerMessages messages, Locale locale) throws WebloggerException {
        return store(Source.of(upload), weblog, directory, mfm, messages, locale);
    }

    /**
     * {@link #store(MultipartFile, Weblog, MediaFileDirectory, MediaFileManager, RollerMessages)} for any
     * {@link Source}, details in the default locale -- an API response has no request locale to honour.
     */
    public static Result store(Source upload, Weblog weblog, MediaFileDirectory directory,
            MediaFileManager mfm, RollerMessages messages) throws WebloggerException {
        return store(upload, weblog, directory, mfm, messages, Locale.getDefault());
    }

    /** The general form: any {@link Source}, every refusal's detail from the bundle in {@code locale}. */
    public static Result store(Source upload, Weblog weblog, MediaFileDirectory directory,
            MediaFileManager mfm, RollerMessages messages, Locale locale) throws WebloggerException {
        I18nMessages text = I18nMessages.getMessages(locale);
        String fileName = upload.fileName();
        if (StringUtils.isBlank(fileName)) {
            return new Result(fileName == null ? "(unnamed)" : fileName, "error",
                    text.getString("mediaUploads.detail.nameRequired"), null);
        }
        if (upload.isEmpty()) {
            return new Result(fileName, "error", text.getString("mediaUploads.detail.empty"), null);
        }
        if (fileName.length() > MEDIA_NAME_MAX) {
            return new Result(fileName, "error",
                    text.getString("mediaUploads.detail.nameTooLong", new Object[] {MEDIA_NAME_MAX}), null);
        }

        // roller_mediafile.content_type is varchar(50) -- a real-world MIME
        // type (a .docx's, for one, is 73 characters) can overflow it. Left
        // unchecked this reached the manager and threw a bare
        // WebloggerException that the caller (the per-file loop) does not
        // catch, killing the WHOLE batch's 207 rather than failing just
        // this one row -- the opposite of the isolation the rest of this
        // method exists to guarantee. Computed and checked BEFORE
        // buildMediaFile (which is what opens upload.getInputStream()) so
        // this refusal returns on the same side of the stream-opening as
        // every other per-file guard above -- it used to run after,
        // leaking a disk-backed part's file descriptor until GC on the one
        // refusal path that opened the stream at all.
        String contentType = effectiveContentType(upload, fileName);
        if (contentType != null && contentType.length() > MEDIA_CONTENT_TYPE_MAX) {
            return new Result(fileName, "error", text.getString("mediaUploads.detail.contentTypeTooLong",
                    new Object[] {contentType, MEDIA_CONTENT_TYPE_MAX}), null);
        }

        MediaFile created = buildMediaFile(upload, weblog, directory, fileName, contentType);
        int before = messages.getErrorCount();
        mfm.createMediaFile(weblog, created, messages);
        if (before != messages.getErrorCount()) {
            return refusal(fileName, messages, locale);
        }
        return new Result(fileName, "created", null, created);
    }

    /**
     * The browser-supplied content type, falling back to a filename-derived
     * guess when the browser sent none or the generic {@code
     * octet-stream}. Deliberately independent of the multipart body itself
     * (no {@code getInputStream()} call) so the too-long refusal in {@link
     * #store} can be decided BEFORE {@link #buildMediaFile} opens the
     * file's input stream, not after.
     */
    private static String effectiveContentType(Source upload, String fileName) {
        String contentType = upload.contentType();
        if (contentType == null || contentType.endsWith("/octet-stream")) {
            String detected = Utilities.getContentTypeFromFileName(fileName);
            if (detected != null) {
                contentType = detected;
            }
        }
        return contentType;
    }

    private static MediaFile buildMediaFile(Source upload, Weblog weblog, MediaFileDirectory directory,
            String fileName, String contentType) throws WebloggerException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(fileName);
        mediaFile.setDirectory(directory);
        mediaFile.setWeblog(weblog);
        mediaFile.setLength(upload.size());
        try {
            mediaFile.setInputStream(upload.openStream());
        } catch (IOException e) {
            throw new WebloggerException(e);
        }
        mediaFile.setContentType(contentType);
        return mediaFile;
    }

    /**
     * The weblog's default upload directory, created on first use -- the
     * same fallback {@code MediaFileAddController.resolveDirectory} applies
     * when the admin UI's upload form is not given an explicit directory.
     */
    public static MediaFileDirectory defaultDirectory(Weblog weblog, MediaFileManager mfm) throws WebloggerException {
        MediaFileDirectory directory = mfm.getDefaultMediaFileDirectory(weblog);
        return directory != null ? directory : mfm.createDefaultMediaFileDirectory(weblog);
    }

    /**
     * The directory with this id, but only when it belongs to {@code
     * weblog}; {@code null} for a blank id, an unknown id, or a foreign one
     * -- the same by-id ownership shape as {@link WeblogOwnership}'s
     * lookups, for the one entity {@code WeblogOwnership} does not carry a
     * member for.
     */
    public static MediaFileDirectory directoryFor(Weblogger weblogger, Weblog weblog, String directoryId)
            throws WebloggerException {
        if (StringUtils.isBlank(directoryId)) {
            return null;
        }
        MediaFileDirectory directory = weblogger.getMediaFileManager().getMediaFileDirectory(directoryId);
        if (directory == null || directory.getWeblog() == null
                || !directory.getWeblog().getId().equals(weblog.getId())) {
            return null;
        }
        return directory;
    }

    /**
     * Reads the newest (most recently added) error out of {@code messages}
     * -- the one {@code createMediaFile}/{@code canSave} just added for
     * THIS file -- and maps its key onto one of the three refusal statuses
     * the API promises. {@code FileContentManagerImpl.canSave} is the only
     * source of these keys today: {@code error.upload.dirmax} (the
     * weblog's total quota) and {@code error.upload.filemax} (the per-file
     * size cap) are both a size limit from the caller's point of view, so
     * both map to {@code "quota_exceeded"}; {@code error.upload.forbiddenFile}
     * maps to {@code "forbidden_extension"}; anything else (chiefly
     * {@code error.upload.disabled}, and defensively any key this method
     * has never seen) is {@code "error"}.
     *
     * <p>Moved here from {@code MediaDtos.refusal} (which now delegates)
     * because it is the shared per-file outcome this class exists to own,
     * not a DTO concern.
     */
    public static Result refusal(String fileName, RollerMessages messages) {
        return refusal(fileName, messages, Locale.getDefault());
    }

    /**
     * {@link #refusal(String, RollerMessages)} with the detail in {@code
     * locale}. Every detail is a bundle sentence; a key the mapping does not
     * know becomes the generic "Upload failed.", never the key itself.
     */
    public static Result refusal(String fileName, RollerMessages messages, Locale locale) {
        I18nMessages text = I18nMessages.getMessages(locale);
        RollerMessages.RollerMessage last = null;
        Iterator<RollerMessages.RollerMessage> it = messages.getErrors();
        while (it.hasNext()) {
            last = it.next();
        }
        if (last == null) {
            return new Result(fileName, "error", text.getString("error.upload.failed"), null);
        }
        String status = switch (last.getKey()) {
            case "error.upload.dirmax", "error.upload.filemax" -> "quota_exceeded";
            case "error.upload.forbiddenFile" -> "forbidden_extension";
            default -> "error";
        };
        return new Result(fileName, status, detailFor(last, text), null);
    }

    /**
     * The manager's own keys carry HTML ({@code <b>}) and the file name, so
     * the detail is a plain sentence of its own, with the limit when the
     * manager supplied it.
     */
    private static String detailFor(RollerMessages.RollerMessage msg, I18nMessages text) {
        String[] args = msg.getArgs();
        return switch (msg.getKey()) {
            case "error.upload.dirmax" -> args != null && args.length > 0
                    ? text.getString("mediaUploads.detail.dirmax", new Object[] {args[0]})
                    : text.getString("mediaUploads.detail.dirmaxUnknown");
            case "error.upload.filemax" -> args != null && args.length > 1
                    ? text.getString("mediaUploads.detail.filemax", new Object[] {args[1]})
                    : text.getString("mediaUploads.detail.filemaxUnknown");
            case "error.upload.forbiddenFile" -> text.getString("mediaUploads.detail.forbiddenFile");
            case "error.upload.disabled" -> text.getString("mediaUploads.detail.disabled");
            default -> text.getString("error.upload.failed");
        };
    }
}
