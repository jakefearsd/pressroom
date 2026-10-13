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

package org.apache.roller.weblogger.business;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.FileContent;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;

/**
 * Manages contents of the file uploaded to Roller weblogs.
 * 
 * This base implementation writes file content to a file system.
 */
public class FileContentManagerImpl implements FileContentManager {

    private static final Logger log = LoggerFactory.getLogger(FileContentManagerImpl.class);

    private String storageDir;

    /**
     * Create file content manager.
     */
    public FileContentManagerImpl() {

        String inStorageDir = WebloggerConfig
                .getProperty("mediafiles.storage.dir");

        // Note: System property expansion is now handled by WebloggerConfig.

        if (inStorageDir == null || inStorageDir.isBlank()) {
            inStorageDir = System.getProperty("user.home") + File.separator
                    + "roller_data" + File.separator + "mediafiles";
        }

        if (!inStorageDir.endsWith(File.separator)) {
            inStorageDir += File.separator;
        }

        this.storageDir = inStorageDir.replace('/', File.separatorChar);

    }

    public void initialize() {

    }

    /**
     * @see org.apache.roller.weblogger.business.FileContentManager#getFileContent(Weblog,
     *      String)
     */
    @Override
    public FileContent getFileContent(Weblog weblog, String fileId)
            throws FileNotFoundException, FilePathException {

        // get a reference to the file, checks that file exists & is readable
        File resourceFile = this.getRealFile(weblog, fileId);

        // make sure file is not a directory
        if (resourceFile.isDirectory()) {
            throw new FilePathException("Invalid file id [" + fileId + "], "
                    + "path is a directory.");
        }

        // everything looks good, return resource
        return new FileContent(weblog, fileId, resourceFile);
    }

    /**
     * @see org.apache.roller.weblogger.business.FileContentManager#saveFileContent(Weblog,
     *      String, java.io.InputStream)
     *
     * <p>The write is atomic with respect to the destination file: the bytes
     * are streamed to a temporary sibling in the same directory, forced to
     * disk, and only then moved over the destination with
     * {@code ATOMIC_MOVE}. A mid-write failure (disk full, broken stream)
     * therefore never leaves a truncated file behind -- the previous content,
     * if any, survives untouched. This matters most for the destructive
     * media-crop path, which overwrites a previously-good original the user
     * has no other copy of, but every caller (uploads, thumbnails, rendition
     * ladder) gets the same all-or-nothing guarantee.
     *
     * <p>Side effect of the temp-file route: {@code Files.createTempFile}
     * creates the file with owner-only ({@code 0600}) permissions and the
     * rename preserves them, so every saved file ends up {@code 0600} rather
     * than umask-governed — harmless in the supported deploy topology, where
     * the app user owns the uploads tree and backup/restore runs as root.
     */
    @Override
    public void saveFileContent(Weblog weblog, String fileId, InputStream is)
            throws FileNotFoundException, FilePathException, FileIOException {

        checkFileName(fileId);

        // make sure uploads area exists for this weblog
        File dirPath = this.getRealFile(weblog, null);

        // create File that we are about to save
        Path saveFile = Path.of(dirPath.getAbsolutePath(), fileId);

        Path tempFile = null;
        try {
            // The temp file must live in the SAME directory as the target:
            // Files.move is only atomic within a filesystem, and a rename
            // within one directory is the strongest portable guarantee.
            tempFile = Files.createTempFile(requireParent(saveFile), "roller-save-", ".tmp");
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                is.transferTo(Channels.newOutputStream(channel));
                // flush to the device before the swap, so the rename can
                // never expose a file whose bytes are still in flight
                channel.force(true);
            }
            try {
                Files.move(tempFile, saveFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // exotic filesystem: degrade to a plain replace rather than fail
                Files.move(tempFile, saveFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("The file has been written to [{}]", saveFile);
        } catch (IOException e) {
            // the destination was never touched; only the temp needs cleanup
            if (tempFile != null) {
                deleteTempQuietly(tempFile);
            }
            throw new FileIOException("ERROR uploading file", e);
        }

    }

    /**
     * Best-effort cleanup of an aborted temp file. Deliberately swallows the
     * cleanup failure: it must never mask the original write failure the
     * caller is about to report.
     */
    static void deleteTempQuietly(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException cleanup) {
            log.debug("Could not delete temp file {}", tempFile, cleanup);
        }
    }

    /**
     * {@code path} passed in from {@code saveFileContent} is always
     * {@code dirPath}'s absolute path plus {@code fileId}
     * ({@link #getRealFile} guarantees {@code dirPath} is absolute), so
     * {@code getParent()} can never actually return null there -- but fail
     * loudly rather than let a null slip into {@code Files.createTempFile}
     * if that invariant is ever broken. Package-private so
     * {@code FileContentManagerImplTest} can pin the null case directly
     * (e.g. the root path) without needing an absolute path that provably
     * cannot arise through {@code saveFileContent}.
     */
    // Package-private for test access to a defensive branch -- not ordinary API.
    static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Cannot determine parent directory for [" + path + "]");
        }
        return parent;
    }

    /**
     * @see org.apache.roller.weblogger.business.FileContentManager#deleteFile(Weblog,
     *      String)
     */
    @Override
    public void deleteFile(Weblog weblog, String fileId)
            throws FileNotFoundException, FilePathException, FileIOException {

        // get path to delete file, checks that path exists and is readable
        File delFile = this.getRealFile(weblog, fileId);

        if (!delFile.delete()) {
            log.warn("Delete appears to have failed for [{}]", fileId);
        }
    }

    /**
     * @see org.apache.roller.weblogger.business.FileContentManager#overQuota(Weblog)
     */
    @Override
    public boolean overQuota(Weblog weblog) {

        String maxDir = WebloggerRuntimeConfig
                .getProperty("uploads.dir.maxsize");

        // maxDirSize in megabytes
        BigDecimal maxDirSize = new BigDecimal(maxDir);

        long maxDirBytes = (long) (RollerConstants.ONE_MB_IN_BYTES * maxDirSize
                .doubleValue());

        try {
            File storageDirectory = this.getRealFile(weblog, null);
            long weblogDirSize = this.getDirSize(storageDirectory, true);

            return weblogDirSize > maxDirBytes;
        } catch (Exception ex) {
            // shouldn't ever happen, this means user's uploads dir is bad
            // rethrow as a runtime exception
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void release() {
    }

    /**
     * @see org.apache.roller.weblogger.business.FileContentManager#canSave(Weblog,
     *      String, String, long, RollerMessages)
     */
    @Override
    public boolean canSave(Weblog weblog, String fileName, String contentType,
            long size, RollerMessages messages) {

        // first check, is uploading enabled?
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            messages.addError("error.upload.disabled");
            return false;
        }

        // second check, does upload exceed max size for file?
        BigDecimal maxFileMB = new BigDecimal(
                WebloggerRuntimeConfig.getProperty("uploads.file.maxsize"));
        int maxFileBytes = (int) (RollerConstants.ONE_MB_IN_BYTES * maxFileMB
                .doubleValue());
        log.debug("max allowed file size = {}", maxFileBytes);
        log.debug("attempted save file size = {}", size);
        if (size > maxFileBytes) {
            String[] args = { fileName, maxFileMB.toString() };
            messages.addError("error.upload.filemax", args);
            return false;
        }

        // third check, does file cause weblog to exceed quota?
        BigDecimal maxDirMB = new BigDecimal(
                WebloggerRuntimeConfig.getProperty("uploads.dir.maxsize"));
        long maxDirBytes = (long) (RollerConstants.ONE_MB_IN_BYTES * maxDirMB
                .doubleValue());
        try {
            File storageDirectory = this.getRealFile(weblog, null);
            long userDirSize = getDirSize(storageDirectory, true);
            if (userDirSize + size > maxDirBytes) {
                messages.addError("error.upload.dirmax", maxDirMB.toString());
                return false;
            }
        } catch (Exception ex) {
            // shouldn't ever happen, means the weblogs uploads dir is bad
            // somehow
            // rethrow as a runtime exception
            throw new RuntimeException(ex);
        }

        // fourth check, is upload type allowed?
        String allows = WebloggerRuntimeConfig
                .getProperty("uploads.types.allowed");
        String forbids = WebloggerRuntimeConfig
                .getProperty("uploads.types.forbid");
        String[] allowFiles = StringUtils.split(
                StringUtils.deleteWhitespace(allows), ",");
        String[] forbidFiles = StringUtils.split(
                StringUtils.deleteWhitespace(forbids), ",");
        if (!checkFileType(allowFiles, forbidFiles, fileName, contentType)) {
            String[] args = { fileName, contentType };
            messages.addError("error.upload.forbiddenFile", args);
            return false;
        }

        return true;
    }

    /**
     * Get the size in bytes of given directory.
     *
     * Optionally works recursively counting subdirectories if they exist.
     * Responsive-image rendition siblings ({@code <id>_480}, {@code
     * <id>_480.webp}, etc. -- see {@link RenditionSupport}) are excluded so
     * that generating them never counts against a user's upload quota; only
     * files the user actually uploaded count.
     */
    private long getDirSize(File dir, boolean recurse) {

        long size = 0;

        if (dir.exists() && dir.isDirectory() && dir.canRead()) {
            long dirSize = 0l;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isDirectory()) {
                        if (RenditionSupport.isRenditionFileName(file.getName())) {
                            continue;
                        }
                        dirSize += file.length();
                    } else if (recurse) {
                        // count a subdirectory
                        dirSize += getDirSize(file, recurse);
                    }
                }
            }
            size += dirSize;
        }

        return size;
    }

    /**
     * Return true if file is allowed to be uplaoded given specified allowed and
     * forbidden file types.
     */
    private boolean checkFileType(String[] allowFiles, String[] forbidFiles,
            String fileName, String contentType) {

        // TODO: Atom Publishing Protocol figure out how to handle file
        // allow/forbid using contentType.
        // TEMPORARY SOLUTION: In the allow/forbid lists we will continue to
        // allow user to specify file extensions (e.g. gif, png, jpeg) but will
        // now also allow them to specify content-type rules (e.g. */*, image/*,
        // text/xml, etc.).

        // if content type is invalid, reject file
        if (contentType == null || contentType.indexOf('/') == -1) {
            return false;
        }

        // default to false
        boolean allowFile = false;

        // if this person hasn't listed any allows, then assume they want
        // to allow *all* filetypes, except those listed under forbid
        if (allowFiles == null || allowFiles.length < 1) {
            allowFile = true;
        }

        // First check against what is ALLOWED

        // check file against allowed file extensions
        if (allowFiles != null && allowFiles.length > 0) {
            for (String allowRule : allowFiles) {
                // oops, this allowed rule is a content-type, skip it
                if (allowRule.indexOf('/') != -1) {
                    continue;
                }
                if (fileName.toLowerCase(Locale.ROOT)
                        .endsWith(allowRule.toLowerCase(Locale.ROOT))) {
                    allowFile = true;
                    break;
                }
            }
        }

        // check file against allowed contentTypes
        if (allowFiles != null && allowFiles.length > 0) {
            for (String allowRule : allowFiles) {
                // oops, this allowed rule is NOT a content-type, skip it
                if (allowRule.indexOf('/') == -1) {
                    continue;
                }
                if (matchContentType(allowRule, contentType)) {
                    allowFile = true;
                    break;
                }
            }
        }

        // First check against what is FORBIDDEN

        // check file against forbidden file extensions, overrides any allows
        if (forbidFiles != null && forbidFiles.length > 0) {
            for (String forbidRule : forbidFiles) {
                // oops, this forbid rule is a content-type, skip it
                if (forbidRule.indexOf('/') != -1) {
                    continue;
                }
                if (fileName.toLowerCase(Locale.ROOT).endsWith(
                        forbidRule.toLowerCase(Locale.ROOT))) {
                    allowFile = false;
                    break;
                }
            }
        }

        // check file against forbidden contentTypes, overrides any allows
        if (forbidFiles != null && forbidFiles.length > 0) {
            for (String forbidRule : forbidFiles) {
                // oops, this forbid rule is NOT a content-type, skip it
                if (forbidRule.indexOf('/') == -1) {
                    continue;
                }
                if (matchContentType(forbidRule, contentType)) {
                    allowFile = false;
                    break;
                }
            }
        }

        return allowFile;
    }

    /**
     * Super simple contentType range rule matching
     */
    private boolean matchContentType(String rangeRule, String contentType) {
        if ("*/*".equals(rangeRule)) {
            return true;
        }
        if (rangeRule.equals(contentType)) {
            return true;
        }
        String ruleParts[] = rangeRule.split("/");
        String typeParts[] = contentType.split("/");
        return ruleParts[0].equals(typeParts[0]) && "*".equals(ruleParts[1]);
    }

    /**
     * Construct the full real path to a resource in a weblog's uploads area.
     */
    private File getRealFile(Weblog weblog, String fileId)
            throws FileNotFoundException, FilePathException {

        // make sure uploads area exists for this weblog
        Path weblogDir = Path.of(this.storageDir, weblog.getHandle());
        if (!Files.exists(weblogDir)) {
            try {
                Files.createDirectories(weblogDir);
            } catch (IOException ex) {
                throw new FilePathException("Can't create storage dir [" + weblogDir + "]", ex);
            }
        }

        // now form the absolute path
        Path weblogRoot = weblogDir.toAbsolutePath().normalize();
        Path filePath = weblogRoot;
        if (fileId != null) {
            checkFileName(fileId);
            filePath = requireInside(weblogRoot, weblogRoot.resolve(fileId), fileId);
        }

        // make sure path exists and is readable
        if (!Files.isReadable(filePath)) {
            throw new FileNotFoundException("Invalid path [" + filePath + "], "
                    + "file does not exist or is not readable.");
        }

        return filePath.toFile();
    }

    /**
     * The uploads-dir boundary itself: {@code candidate}, normalized, must sit
     * under {@code root}.
     *
     * <p>This is the check that does not depend on enumerating hostile
     * spellings, and it is the reason an absolute file id can no longer
     * escape. {@link Path#resolve} RETURNS ITS ARGUMENT when that argument is
     * absolute, discarding {@code root} entirely -- so such an id used to walk
     * straight out of the uploads area without ever containing {@code ".."}
     * for {@link #checkFileName} to catch.
     *
     * <p>Package-private for test access to a defensive branch, the same way
     * {@link #requireParent} is, and labelled here so a later reader does not
     * mistake it for ordinary API: with {@code checkFileName} refusing both
     * {@code ".."} and absolute ids ahead of it, no public call can currently
     * drive this to throw. The invariant making that true -- that the name
     * check names every shape that could resolve outside -- is not
     * compiler-enforced and is exactly the sort that breaks quietly later,
     * which is why the guard is kept and tested rather than dropped as
     * redundant.
     */
    static Path requireInside(Path root, Path candidate, String fileId) throws FilePathException {
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(root.normalize())) {
            throw new FilePathException("Invalid file id [" + fileId + "], "
                    + "resolves outside the weblog's uploads dir.");
        }
        return normalized;
    }

    /**
     * Make sure someone isn't trying to sneak outside the uploads dir.
     *
     * <p>This is the cheap, name-shaped half of the boundary; {@link
     * #getRealFile} does the authoritative containment check on the resolved
     * path. Kept as its own guard because {@code saveFileContent} calls it
     * directly, and because refusing an obviously hostile id with a message
     * naming it beats a generic containment failure.
     */
    private static void checkFileName(String fileId) throws FilePathException {
        if(fileId.contains("..")) {
            throw new FilePathException("Invalid file name [" + fileId + "], "
                    + "trying to get outside uploads dir.");
        }
        // An absolute id is refused outright rather than resolved: see
        // getRealFile for why resolve() makes this a real escape and not a
        // theoretical one. Path.of rejects some strings outright (an embedded
        // NUL, for instance), which is itself a good enough reason to refuse.
        try {
            if (Path.of(fileId).isAbsolute()) {
                throw new FilePathException("Invalid file name [" + fileId + "], "
                        + "absolute paths are not valid file ids.");
            }
        } catch (InvalidPathException e) {
            throw new FilePathException("Invalid file name [" + fileId + "].", e);
        }
    }

}
