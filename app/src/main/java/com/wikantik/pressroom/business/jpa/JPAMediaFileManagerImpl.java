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
package com.wikantik.pressroom.business.jpa;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BlurHash;
import com.wikantik.pressroom.business.ContentHashes;
import com.wikantik.pressroom.business.ExifSupport;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FileIOException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.RenditionSupport;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.FileContent;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.MediaFileTag;
import com.wikantik.pressroom.pojos.MediaFileType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;
import java.util.HashSet;
import java.util.Locale;
import com.wikantik.pressroom.util.Utilities;

public class JPAMediaFileManagerImpl implements MediaFileManager {

    private final Weblogger pressroom;
    private final JPAPersistenceStrategy strategy;
    private static final Logger log = LoggerFactory.getLogger(JPAMediaFileManagerImpl.class);

    /**
     * Creates a new instance of MediaFileManagerImpl
     */
    public JPAMediaFileManagerImpl(Weblogger pressroom,
            JPAPersistenceStrategy persistenceStrategy) {
        this.pressroom = pressroom;
        this.strategy = persistenceStrategy;
    }

    /**
     * Initialize manager; currently a no-op.
     */
    @Override
    public void initialize() {
    }

    /**
     * Release resources; currently a no-op.
     */
    @Override
    public void release() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void moveMediaFiles(Collection<MediaFile> mediaFiles,
            MediaFileDirectory targetDirectory) throws WebloggerException {

        List<MediaFile> moved = new ArrayList<>(mediaFiles);

        for (MediaFile mediaFile : moved) {
            mediaFile.getDirectory().getMediaFiles().remove(mediaFile);

            mediaFile.setDirectory(targetDirectory);
            this.strategy.store(mediaFile);

            targetDirectory.getMediaFiles().add(mediaFile);
            this.strategy.store(targetDirectory);
        }
        // update weblog last modified date. date updated by saveWebsite()
        pressroom.getWeblogManager().saveWeblog(targetDirectory.getWeblog());

        // Refresh associated parent for changes
        pressroom.flush();
        if (!moved.isEmpty()) {
            strategy.refresh(moved.get(0).getDirectory());
        }

        // Refresh associated parent for changes
        strategy.refresh(targetDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void moveMediaFile(MediaFile mediaFile,
            MediaFileDirectory targetDirectory) throws WebloggerException {
        moveMediaFiles(Arrays.asList(mediaFile), targetDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createMediaFileDirectory(MediaFileDirectory directory)
            throws WebloggerException {
        this.strategy.store(directory);

        // update weblog last modified date. date updated by saveWebsite()
        pressroom.getWeblogManager().saveWeblog(directory.getWeblog());

        // Refresh associated parent for changes
        // strategy.refresh(directory.getParent());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFileDirectory createMediaFileDirectory(Weblog weblog,
            String requestedName) throws WebloggerException {

        requestedName = requestedName.startsWith("/") ? requestedName.substring(1) : requestedName;

        if (requestedName.isEmpty() || "default".equals(requestedName)) {
            // Default cannot be created using this method.
            // Use createDefaultMediaFileDirectory instead
            throw new WebloggerException("Invalid name!");
        }

        MediaFileDirectory newDirectory;

        if (weblog.hasMediaFileDirectory(requestedName)) {
            throw new WebloggerException("Directory exists");
        } else {
            newDirectory = new MediaFileDirectory(weblog, requestedName, null);
            log.debug("Created new Directory {}", requestedName);
        }

        // update weblog last modified date. date updated by saveWeblog()
        pressroom.getWeblogManager().saveWeblog(weblog);

        return newDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFileDirectory createDefaultMediaFileDirectory(Weblog weblog)
            throws WebloggerException {
        MediaFileDirectory defaultDirectory = new MediaFileDirectory(weblog, "default",
                "default directory");
        createMediaFileDirectory(defaultDirectory);
        return defaultDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createMediaFile(Weblog weblog, MediaFile mediaFile,
            PressroomMessages errors) throws WebloggerException {

        FileContentManager cmgr = pressroom.getFileContentManager();
        if (!cmgr.canSave(weblog, mediaFile.getName(),
                mediaFile.getContentType(), mediaFile.getLength(), errors)) {
            return;
        }
        persistNewMediaFile(weblog, mediaFile.getDirectory(), mediaFile, mediaFile.getInputStream());
    }

    @Override
    public void createThemeMediaFile(Weblog weblog, MediaFile mediaFile,
                                PressroomMessages errors) throws WebloggerException {

        persistNewMediaFile(weblog, mediaFile.getDirectory(), mediaFile, mediaFile.getInputStream());
    }

    private void persistNewMediaFile(Weblog weblog, MediaFileDirectory directory,
            MediaFile mediaFile, InputStream is) throws WebloggerException {
        FileContentManager cmgr = pressroom.getFileContentManager();
        strategy.store(mediaFile);

        // Refresh associated parent for changes
        pressroom.flush();
        strategy.refresh(directory);

        // The hash is taken from the bytes as they stream to disk, so an
        // upload of any size is hashed without being held in memory.
        MessageDigest sha256 = ContentHashes.sha256();
        cmgr.saveFileContent(weblog, mediaFile.getId(), new DigestInputStream(is, sha256));
        mediaFile.setContentHash(ContentHashes.hex(sha256.digest()));
        strategy.store(mediaFile);

        if (mediaFile.isImageFile()) {
            updateThumbnail(mediaFile);
        }
        touchAfterDerivedData(weblog);
    }

    /**
     * Moves the weblog's lastModified once everything derived from new
     * content -- dimensions, renditions, EXIF, GPS, blurhash -- has been
     * written. updateThumbnail commits part of it midway (its flush), so a
     * stamp moved before that call would be committed with the dimensions
     * but ahead of the metadata: a page or entry render made in between is
     * kept under the new stamp, without the metadata, until something else
     * bumps it. Moved here, the stamp commits with the last write (the
     * caller's flush), and anything rendered earlier carries the old one.
     * Date updated by saveWeblog().
     */
    private void touchAfterDerivedData(Weblog weblog) throws WebloggerException {
        pressroom.getWeblogManager().saveWeblog(weblog);
    }

    private void updateThumbnail(MediaFile mediaFile) {
        try {
            FileContentManager cmgr = pressroom.getFileContentManager();
            FileContent fc = cmgr.getFileContent(mediaFile.getWeblog(),
                    mediaFile.getId());
            BufferedImage img;

            img = ImageIO.read(fc.getInputStream());

            // EXIF Orientation: a portrait phone/camera shot is stored as a
            // landscape raster plus a rotation tag, and ImageIO.read() hands
            // back the raw raster. Correct it here, once, so EVERYTHING
            // derived below -- stored dimensions, the _sm thumbnail, the
            // ladder renditions, the blurhash -- is built from the upright
            // image. (The original file on disk is never rewritten; browsers
            // rotate it themselves from its EXIF.)
            img = RenditionSupport.applyOrientation(img,
                    ExifSupport.readOrientation(fc.getInputStream()));

            // determine and save width and height
            mediaFile.setWidth(img.getWidth());
            mediaFile.setHeight(img.getHeight());
            strategy.store(mediaFile);

            writeAdminThumbnail(cmgr, mediaFile, img);

            pressroom.flush();
            // Refresh associated parent for changes
            strategy.refresh(mediaFile.getDirectory());

            // Responsive-image width ladder (independent of the admin thumbnail
            // above). RenditionSupport never throws -- per-rendition failures are
            // logged at WARN with the media file id and do not reach this catch.
            RenditionSupport.generate(cmgr, mediaFile, img);

            // EXIF/GPS/blurhash: reads from the file already saved to disk
            // (fc, re-opened) rather than the original upload stream, which
            // saveFileContent() already consumed. Never lets a metadata
            // failure affect the thumbnail/rendition work above.
            extractExifAndBlurhash(cmgr, mediaFile, fc);

        } catch (Exception e) {
            log.debug("ERROR creating thumbnail", e);
        }
    }

    /**
     * Renders and stores the {@code <id>_sm} admin thumbnail from the
     * already orientation-corrected image. The thumbnail box is derived from
     * the media file's stored width/height, so callers must have set those
     * from the same image first.
     */
    private void writeAdminThumbnail(FileContentManager cmgr, MediaFile mediaFile,
            BufferedImage img) throws Exception {
        int newWidth = mediaFile.getThumbnailWidth();
        int newHeight = mediaFile.getThumbnailHeight();

        Image newImage = img.getScaledInstance(newWidth, newHeight,
                Image.SCALE_SMOOTH);
        BufferedImage tmp = new BufferedImage(newWidth, newHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = tmp.createGraphics();
        g2.drawImage(newImage, 0, 0, newWidth, newHeight, null);
        g2.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(tmp, "png", baos);

        cmgr.saveFileContent(mediaFile.getWeblog(), mediaFile.getId()
                + "_sm", new ByteArrayInputStream(baos.toByteArray()));
    }

    /**
     * Reads EXIF/GPS metadata from the stored original ({@code fc}, re-opened
     * -- {@link FileContent#getInputStream()} returns a fresh stream backed
     * by the on-disk file each time it's called) and encodes a BlurHash
     * placeholder from the smallest available rendition. Applies the
     * {@code uploads.exif.stripGps} site setting by nulling the GPS fields
     * before they are ever persisted -- the original file on disk is never
     * touched either way. Metadata extraction and blurhash encoding never
     * throw: each is independent, and a failure in one (unreadable EXIF,
     * blurhash decode failure) does not prevent the other from being stored.
     * Only the final {@code strategy.store()} can throw, same as the rest of
     * this class -- callers already handle it the same way they handle every
     * other step of thumbnail/rendition generation.
     */
    private void extractExifAndBlurhash(FileContentManager cmgr, MediaFile mediaFile, FileContent fc)
            throws WebloggerException {
        ExifSupport.ExifData exif = ExifSupport.ExifData.EMPTY;
        try {
            exif = ExifSupport.extract(fc.getInputStream());
        } catch (Exception e) {
            log.debug("Could not extract EXIF metadata for media file {}", mediaFile.getId(), e);
        }

        mediaFile.setExifCamera(exif.camera);
        mediaFile.setExifLens(exif.lens);
        mediaFile.setExifExposure(exif.exposure);
        mediaFile.setExifAperture(exif.aperture);
        mediaFile.setExifIso(exif.iso);
        mediaFile.setExifFocalLength(exif.focalLength);
        mediaFile.setExifTaken(exif.taken);

        boolean stripGps = WebloggerRuntimeConfig.getBooleanProperty("uploads.exif.stripGps");
        mediaFile.setGpsLatitude(stripGps ? null : exif.gpsLatitude);
        mediaFile.setGpsLongitude(stripGps ? null : exif.gpsLongitude);

        try {
            mediaFile.setBlurhash(computeBlurhash(cmgr, mediaFile));
        } catch (Exception e) {
            log.debug("Could not compute blurhash for media file {}", mediaFile.getId(), e);
        }

        strategy.store(mediaFile);
    }

    /**
     * Encodes a BlurHash from the smallest available rendition: the 480w
     * rung of the responsive ladder, falling back to the admin thumbnail
     * ({@code _sm}) when the original was narrower than 480 (so the ladder
     * skipped it). Returns null if neither is available or decodable.
     */
    private String computeBlurhash(FileContentManager cmgr, MediaFile mediaFile) {
        for (String candidateId : List.of(mediaFile.getId() + "_480", mediaFile.getId() + "_sm")) {
            try {
                FileContent candidate = cmgr.getFileContent(mediaFile.getWeblog(), candidateId);
                BufferedImage image = ImageIO.read(candidate.getInputStream());
                if (image != null) {
                    return BlurHash.encode(image);
                }
            } catch (Exception e) {
                log.debug("No usable rendition '{}' for blurhash encoding", candidateId, e);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateMediaFile(Weblog weblog, MediaFile mediaFile)
            throws WebloggerException {
        mediaFile.setLastUpdated(new Timestamp(System.currentTimeMillis()));
        strategy.store(mediaFile);

        pressroom.flush();
        // Refresh associated parent for changes
        strategy.refresh(mediaFile.getDirectory());

        // update weblog last modified date. date updated by saveWeblog()
        pressroom.getWeblogManager().saveWeblog(weblog);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateMediaFile(Weblog weblog, MediaFile mediaFile,
            InputStream is) throws WebloggerException {
        // New content is no longer the image an earlier import matched by hash.
        mediaFile.setContentHash(null);
        updateMediaFile(weblog, mediaFile);

        FileContentManager cmgr = pressroom.getFileContentManager();
        PressroomMessages msgs = new PressroomMessages();
        if (!cmgr.canSave(weblog, mediaFile.getName(),
                mediaFile.getContentType(), mediaFile.getLength(), msgs)) {
            throw new FileIOException(msgs.toString());
        }
        cmgr.saveFileContent(weblog, mediaFile.getId(), is);

        if (mediaFile.isImageFile()) {
            updateThumbnail(mediaFile);
        }
        // updateMediaFile above already moved the stamp, before the content
        // was even written; move it again past the derived data.
        touchAfterDerivedData(weblog);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFile getMediaFile(String id) throws WebloggerException {
        return getMediaFile(id, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFile getMediaFile(String id, boolean includeContent)
            throws WebloggerException {
        MediaFile mediaFile = (MediaFile) this.strategy.load(MediaFile.class,
                id);
        if (includeContent) {
            FileContentManager cmgr = pressroom.getFileContentManager();

            FileContent content = cmgr.getFileContent(mediaFile.getDirectory()
                    .getWeblog(), id);
            mediaFile.setContent(content);

            try {
                FileContent thumbnail = cmgr.getFileContent(mediaFile
                        .getDirectory().getWeblog(), id + "_sm");
                mediaFile.setThumbnailContent(thumbnail);

            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Cannot load thumbnail for image {}", id, e);
                } else {
                    log.warn("Cannot load thumbnail for image {}", id);
                }
            }
        }
        return mediaFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFileDirectory getMediaFileDirectoryByName(Weblog weblog,
            String name) throws WebloggerException {

        name = name.startsWith("/") ? name.substring(1) : name;

        log.debug("Looking up weblog|media file directory: {}|{}", weblog.getHandle(), name);

        TypedQuery<MediaFileDirectory> q = this.strategy
                .getNamedQuery("MediaFileDirectory.getByWeblogAndName", MediaFileDirectory.class);
        q.setParameter(1, weblog);
        q.setParameter(2, name);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFile getMediaFileByPath(Weblog weblog, String path)
            throws WebloggerException {

        // get directory
        String fileName = path;
        MediaFileDirectory mdir;
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            mdir = getMediaFileDirectoryByName(weblog, path.substring(0, slash));
        } else {
            mdir = getDefaultMediaFileDirectory(weblog);
        }
        if (slash != -1) {
            fileName = fileName.substring(slash + 1);
        }
        return mdir.getMediaFile(fileName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFile getMediaFileByContentHash(MediaFileDirectory directory, String contentHash)
            throws WebloggerException {
        if (directory == null || contentHash == null) {
            return null;
        }
        TypedQuery<MediaFile> q = strategy.getNamedQuery(
                "MediaFile.getByDirectoryAndContentHash", MediaFile.class);
        q.setParameter(1, directory);
        q.setParameter(2, contentHash);
        q.setMaxResults(1);
        List<MediaFile> found = q.getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public MediaFile getMediaFileByOriginalPath(Weblog weblog, String origpath)
            throws WebloggerException {

        if (null == origpath) {
            return null;
        }

        if (!origpath.startsWith("/")) {
            origpath = "/" + origpath;
        }

        TypedQuery<MediaFile> q = this.strategy
                .getNamedQuery("MediaFile.getByWeblogAndOrigpath", MediaFile.class);
        q.setParameter(1, weblog);
        q.setParameter(2, origpath);
        MediaFile mf;
        try {
            mf = q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        FileContentManager cmgr = pressroom.getFileContentManager();
        FileContent content = cmgr.getFileContent(
                mf.getDirectory().getWeblog(), mf.getId());
        mf.setContent(content);
        return mf;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFileDirectory getMediaFileDirectory(String id)
            throws WebloggerException {
        return (MediaFileDirectory) this.strategy.load(
                MediaFileDirectory.class, id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaFileDirectory getDefaultMediaFileDirectory(Weblog weblog)
            throws WebloggerException {
        return getMediaFileDirectoryByName(weblog, "default");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MediaFileDirectory> getMediaFileDirectories(Weblog weblog)
            throws WebloggerException {

        TypedQuery<MediaFileDirectory> q = this.strategy.getNamedQuery("MediaFileDirectory.getByWeblog",
                MediaFileDirectory.class);
        q.setParameter(1, weblog);
        return q.getResultList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeMediaFile(Weblog weblog, MediaFile mediaFile)
            throws WebloggerException {
        FileContentManager cmgr = pressroom.getFileContentManager();

        this.strategy.remove(mediaFile);

        // Refresh associated parent for changes
        strategy.refresh(mediaFile.getDirectory());

        // update weblog last modified date. date updated by saveWeblog()
        pressroom.getWeblogManager().saveWeblog(weblog);

        // Off the disk only once the row's removal has committed: deleted
        // now, a rollback would leave the row pointing at nothing.
        String fileId = mediaFile.getId();
        strategy.runAfterCommit(() -> deleteContentQuietly(cmgr, weblog, fileId));
    }

    /**
     * Deletes the original file, the admin thumbnail (`_sm`), and every
     * responsive rendition sibling that may exist for a media file id. Each
     * candidate is deleted independently -- a missing rendition (never
     * generated because it was narrower than the original, or because
     * cwebp was unavailable) must not prevent the others from being
     * cleaned up.
     */
    static void deleteContentQuietly(FileContentManager cmgr, Weblog weblog, String mediaFileId) {
        deleteFileQuietly(cmgr, weblog, mediaFileId);
        deleteFileQuietly(cmgr, weblog, mediaFileId + "_sm");
        for (String renditionId : RenditionSupport.renditionFileIds(mediaFileId)) {
            deleteFileQuietly(cmgr, weblog, renditionId);
        }
    }

    private static void deleteFileQuietly(FileContentManager cmgr, Weblog weblog, String fileId) {
        try {
            cmgr.deleteFile(weblog, fileId);
        } catch (Exception e) {
            log.debug("File to be deleted already unavailable in the file store: {}", fileId, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MediaFile> searchMediaFiles(Weblog weblog,
            MediaFileFilter filter) throws WebloggerException {

        List<Object> params = new ArrayList<>();
        int size = 0;
        String queryString = "SELECT m FROM MediaFile m WHERE ";
        StringBuilder whereClause = new StringBuilder();
        StringBuilder orderBy = new StringBuilder();

        params.add(size++, weblog);
        whereClause.append("m.directory.weblog = ?").append(size);

        if (!StringUtils.isEmpty(filter.getName())) {
            String nameFilter = filter.getName();
            nameFilter = nameFilter.trim();
            if (!nameFilter.endsWith("%")) {
                nameFilter = nameFilter + "%";
            }
            params.add(size++, nameFilter);
            whereClause.append(" AND m.name like ?").append(size);
        }

        if (filter.getSize() > 0) {
            params.add(size++, filter.getSize());
            whereClause.append(" AND m.length ");
            // An expression, not a statement: adding a SizeFilterType constant
            // is then a compile error here, where a statement switch with a
            // default would quietly run a wrong query.
            whereClause.append(switch (filter.getSizeFilterType()) {
                case GT -> ">";
                case GTE -> ">=";
                case EQ -> "=";
                case LT -> "<";
                case LTE -> "<=";
            });
            whereClause.append(" ?").append(size);
        }

        if (filter.getTags() != null && filter.getTags().size() > 1) {
            whereClause
                    .append(" AND EXISTS (SELECT t FROM MediaFileTag t WHERE t.mediaFile = m and t.name IN (");
            for (String tag : filter.getTags()) {
                params.add(size++, tag);
                whereClause.append("?").append(size).append(",");
            }
            whereClause.deleteCharAt(whereClause.lastIndexOf(","));
            whereClause.append("))");
        } else if (filter.getTags() != null && filter.getTags().size() == 1) {
            params.add(size++, filter.getTags().get(0));
            whereClause
                    .append(" AND EXISTS (SELECT t FROM MediaFileTag t WHERE t.mediaFile = m and t.name = ?")
                    .append(size).append(")");
        }

        if (filter.getType() != null) {
            if (filter.getType() == MediaFileType.OTHERS) {
                for (MediaFileType type : MediaFileType.values()) {
                    if (type != MediaFileType.OTHERS) {
                        params.add(size++, type.getContentTypePrefix() + "%");
                        whereClause.append(" AND m.contentType not like ?")
                                .append(size);
                    }
                }
            } else {
                params.add(size++, filter.getType().getContentTypePrefix()
                        + "%");
                whereClause.append(" AND m.contentType like ?").append(size);
            }
        }

        if (filter.getOrder() != null) {
            orderBy.append(switch (filter.getOrder()) {
                case NAME -> " order by m.name";
                case DATE_UPLOADED -> " order by m.dateUploaded";
                case TYPE -> " order by m.contentType";
            });
        } else {
            orderBy.append(" order by m.name");
        }

        TypedQuery<MediaFile> query = strategy.getDynamicQuery(queryString
                + whereClause.toString() + orderBy.toString(), MediaFile.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        if (filter.getStartIndex() >= 0) {
            query.setFirstResult(filter.getStartIndex());
            query.setMaxResults(filter.getLength());
        }
        return query.getResultList();
    }

    @Override
    public void removeAllFiles(Weblog website) throws WebloggerException {
        // Every directory, not just the default one. Removing only the default
        // left a deleted weblog's other directories -- and their files on disk
        // -- behind forever, which showed up first as test media accumulating
        // past the upload quota run after run, and would show up in production
        // as orphaned files nothing can reach or reclaim.
        for (MediaFileDirectory dir : new ArrayList<>(getMediaFileDirectories(website))) {
            removeMediaFileDirectory(dir);
        }
    }

    @Override
    public void removeMediaFileDirectory(MediaFileDirectory dir)
            throws WebloggerException {
        if (dir == null) {
            return;
        }
        FileContentManager cmgr = pressroom.getFileContentManager();
        Weblog weblog = dir.getWeblog();
        List<String> fileIds = new ArrayList<>();
        Set<MediaFile> files = dir.getMediaFiles();
        for (MediaFile mf : files) {
            fileIds.add(mf.getId());
            this.strategy.remove(mf);
        }
        // Off the disk only once the commit just below has removed the rows.
        strategy.runAfterCommit(() -> {
            for (String fileId : fileIds) {
                deleteContentQuietly(cmgr, weblog, fileId);
            }
        });

        dir.getWeblog().getMediaFileDirectories().remove(dir);

        // Contained media files
        pressroom.flush();

        this.strategy.remove(dir);

        // Refresh associated parent
        pressroom.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int regenerateRenditions(Weblog weblog) throws WebloggerException {
        // Rebuilds every image's rendition ladder, thumbnail, stored
        // dimensions, EXIF fields and blurhash from the original on disk.
        FileContentManager cmgr = pressroom.getFileContentManager();
        int count = 0;
        for (MediaFileDirectory dir : getMediaFileDirectories(weblog)) {
            for (MediaFile mf : dir.getMediaFiles()) {
                if (!mf.isImageFile()) {
                    continue;
                }
                try {
                    FileContent fc = cmgr.getFileContent(weblog, mf.getId());
                    BufferedImage img = ImageIO.read(fc.getInputStream());
                    if (img == null) {
                        log.warn("Skipping rendition regeneration for media file {}: not a readable image",
                                mf.getId());
                        continue;
                    }
                    // Same orientation correction as the upload path, which is
                    // what makes this action a remediation for photos uploaded
                    // before it existed: their renditions are rebuilt upright,
                    // and their stored dimensions re-derived from the corrected
                    // image (90-degree orientations swap width and height).
                    img = RenditionSupport.applyOrientation(img,
                            ExifSupport.readOrientation(fc.getInputStream()));
                    RenditionSupport.generate(cmgr, mf, img);
                    mf.setWidth(img.getWidth());
                    mf.setHeight(img.getHeight());
                    // Bump lastUpdated so MediaResourceServlet's Last-Modified/304
                    // check (keyed off this field) doesn't keep serving a 304 for
                    // stale renditions cached by a client from before the backfill.
                    mf.setLastUpdated(new Timestamp(System.currentTimeMillis()));

                    // EXIF and the blurhash placeholder too, not just the
                    // rendition ladder. A photo uploaded before this pipeline
                    // existed otherwise kept blank camera/lens/exposure fields
                    // and no placeholder forever, because this action was the
                    // only remediation offered and it rebuilt just the images.
                    // Must run after generate(): the blurhash is encoded from
                    // the smallest rendition. This also stores the entity.
                    extractExifAndBlurhash(cmgr, mf, fc);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to regenerate renditions for media file {}", mf.getId(), e);
                }
            }
        }
        // Dimensions, EXIF and blurhash are all printed by [image] and
        // [gallery]; like every other media change this must move
        // lastModified, the only thing that expires a rendered page or a kept
        // entry render (EntryHtmlCache). Date updated by saveWeblog().
        pressroom.getWeblogManager().saveWeblog(weblog);
        pressroom.flush();
        return count;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Failure semantics: the re-encoded bytes replace the original through
     * {@code FileContentManagerImpl.saveFileContent}'s atomic
     * write-to-temp-then-rename, and nothing else -- no sibling deletion, no
     * entity mutation -- happens until that swap has succeeded. A failure up
     * to and including the write therefore leaves BOTH the file on disk and
     * the database row exactly as they were. After the swap the crop is
     * committed: derived-artifact regeneration is best-effort (a lost
     * thumbnail or rendition degrades to the servlet's full-size fallback and
     * can be rebuilt via regenerateRenditions), and the entity update runs
     * regardless so the stored dimensions always describe the file on disk.
     */
    @Override
    public void cropMediaFile(Weblog weblog, MediaFile mediaFile,
            int x, int y, int width, int height) throws WebloggerException {

        // Explicit ownership boundary, matching the check f9e3e8143 added to
        // MediaResourceServlet: the weblog the caller was authorized against
        // must be the weblog that owns this file. Today a foreign id would
        // also trip over the per-weblog storage directory, but that is an
        // incidental property of one storage layout, not an authorization
        // check -- a flat id-keyed store would silently reopen the hole.
        Weblog owner = mediaFile.getDirectory() == null
                ? null : mediaFile.getDirectory().getWeblog();
        if (weblog == null || owner == null || !weblog.getId().equals(owner.getId())) {
            throw new WebloggerException("Media file " + mediaFile.getId()
                    + " does not belong to weblog "
                    + (weblog == null ? "(none)" : weblog.getHandle()));
        }

        if (!mediaFile.isImageFile()
                || !RenditionSupport.isLadderEligible(mediaFile.getContentType())) {
            throw new WebloggerException("Media file " + mediaFile.getId()
                    + " cannot be cropped: content type " + mediaFile.getContentType()
                    + " has no server-side re-encode path");
        }

        FileContentManager cmgr = pressroom.getFileContentManager();
        try {
            FileContent fc = cmgr.getFileContent(weblog, mediaFile.getId());
            BufferedImage img = ImageIO.read(fc.getInputStream());
            if (img == null) {
                throw new WebloggerException("Media file " + mediaFile.getId()
                        + " is not a readable image");
            }

            // The crop rectangle was drawn on the DISPLAYED image -- browsers
            // rotate the original from its EXIF Orientation before the user
            // ever sees it -- so orientation must be composed in BEFORE the
            // rectangle is interpreted. The re-encoded result carries no EXIF
            // block at all, which is correct: its pixels are already upright,
            // so an orientation tag would rotate them a second time.
            img = RenditionSupport.applyOrientation(img,
                    ExifSupport.readOrientation(fc.getInputStream()));
            int orientedWidth = img.getWidth();
            int orientedHeight = img.getHeight();

            Rectangle rect = RenditionSupport.clampCropRect(
                    orientedWidth, orientedHeight, x, y, width, height);
            BufferedImage cropped = RenditionSupport.crop(img, rect);
            byte[] encoded = RenditionSupport.encode(cropped, mediaFile.getContentType());

            cmgr.saveFileContent(weblog, mediaFile.getId(),
                    new ByteArrayInputStream(encoded));

            // Remove every derived sibling BEFORE regenerating: a crop can
            // shrink the image below rungs it used to clear (e.g. 3000px
            // cropped to 800px), and a leftover <id>_1600 would keep serving
            // the UNCROPPED pixels at that ?w= URL forever.
            deleteFileQuietly(cmgr, weblog, mediaFile.getId() + "_sm");
            for (String renditionId : RenditionSupport.renditionFileIds(mediaFile.getId())) {
                deleteFileQuietly(cmgr, weblog, renditionId);
            }

            // A focal point marks a spot on the photo, not on the frame:
            // remap it through the crop so it keeps pointing at the same
            // pixels (clamped to the edge when the crop excluded it).
            if (mediaFile.getFocalX() != null && mediaFile.getFocalY() != null) {
                double fx = (mediaFile.getFocalX() * orientedWidth - rect.x) / rect.width;
                double fy = (mediaFile.getFocalY() * orientedHeight - rect.y) / rect.height;
                mediaFile.setFocalX(Math.min(1.0, Math.max(0.0, fx)));
                mediaFile.setFocalY(Math.min(1.0, Math.max(0.0, fy)));
            }

            mediaFile.setWidth(cropped.getWidth());
            mediaFile.setHeight(cropped.getHeight());
            mediaFile.setLength(encoded.length);
            // A cropped file is no longer the bytes it was imported as.
            mediaFile.setContentHash(null);
            // Bump lastUpdated so MediaResourceServlet's Last-Modified/304
            // check doesn't keep serving cached pre-crop bytes.
            mediaFile.setLastUpdated(new Timestamp(System.currentTimeMillis()));

            // From here on the crop is committed on disk; derived artifacts
            // are best-effort so a thumbnail/rendition failure can never
            // leave the entity describing the OLD image while the file holds
            // the new one. (RenditionSupport.generate never throws.)
            try {
                writeAdminThumbnail(cmgr, mediaFile, cropped);
            } catch (Exception e) {
                log.warn("Could not regenerate the admin thumbnail after cropping media file {}",
                        mediaFile.getId(), e);
            }
            RenditionSupport.generate(cmgr, mediaFile, cropped);

            // Recompute the blurhash from the cropped renditions. The stored
            // EXIF fields are deliberately NOT re-extracted: the re-encoded
            // file carries no EXIF block, so re-extraction would null out
            // camera metadata that still truthfully describes the photo.
            try {
                mediaFile.setBlurhash(computeBlurhash(cmgr, mediaFile));
            } catch (Exception e) {
                log.debug("Could not recompute blurhash after cropping media file {}",
                        mediaFile.getId(), e);
            }

            strategy.store(mediaFile);
            pressroom.flush();
            strategy.refresh(mediaFile.getDirectory());

            // update weblog last modified date so render caches invalidate
            pressroom.getWeblogManager().saveWeblog(weblog);
        } catch (WebloggerException e) {
            throw e;
        } catch (Exception e) {
            throw new WebloggerException("Error cropping media file " + mediaFile.getId(), e);
        }
    }

    @Override
    public void updateTags(MediaFile file, List<String> updatedTags) throws WebloggerException {
        if (updatedTags == null) {
            return;
        }

        Set<String> newTags = new HashSet<>(updatedTags.size());
        Locale localeObject = file.getWeblog() != null ? file.getWeblog()
                .getLocaleInstance() : Locale.getDefault();

        for (String inName : updatedTags) {
            newTags.add(Utilities.normalizeTag(inName, localeObject));
        }

        Set<String> removeTags = new HashSet<>();

        // remove old ones no longer passed.
        for (MediaFileTag tag : file.getTags()) {
            if (!newTags.contains(tag.getName())) {
                removeTags.add(tag.getName());
            } else {
                newTags.remove(tag.getName());
            }
        }

        for (String tag : removeTags) {
            removeMediaFileTag(tag, file);
        }

        for (String tag : newTags) {
            file.addTag(tag);
        }
    }

    @Override
    public void removeMediaFileTag(String name, MediaFile entry)
            throws WebloggerException {

        for (Iterator<MediaFileTag> it = entry.getTags().iterator(); it.hasNext();) {
            MediaFileTag tag = it.next();
            if (tag.getName().equals(name)) {

                // Call back the entity to adjust its internal state
                entry.onRemoveTag(name);

                // Refresh it from database
                this.strategy.remove(tag);

                // Refresh it from the collection
                it.remove();
            }
        }
    }
}
