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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BlurHash;
import com.wikantik.pressroom.business.ExifSupport;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.ImageDecoding;
import com.wikantik.pressroom.business.RenditionSupport;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.FileContent;
import com.wikantik.pressroom.pojos.MediaFile;

/**
 * The artifacts derived from an upright image: the {@code <id>_sm} admin
 * thumbnail, the rendition ladder, EXIF/GPS fields and the blurhash. Upload,
 * regenerate and crop all rebuild them through {@link #rebuild}, so they share
 * one failure policy: every derived artifact is best-effort. The admin list
 * falls back to the full image when the thumbnail is missing, so a thumbnail
 * that cannot be written costs only itself; the ladder never throws; EXIF and
 * blurhash failures leave their fields empty. Only the final store of the
 * entity can throw, and that propagates like any failure to persist.
 */
final class ImageDerivatives {

    private static final Logger log = LoggerFactory.getLogger(ImageDerivatives.class);

    /** Whether the EXIF fields are re-read from the stored original. */
    enum ExifPolicy {
        /** Read EXIF/GPS from the file on disk (upload, regenerate). */
        EXTRACT,
        /** Leave the stored fields alone (crop: the re-encoded file has no EXIF block). */
        KEEP
    }

    private ImageDerivatives() {
    }

    /**
     * Rebuilds the derived artifacts of {@code mediaFile} from {@code img},
     * which must already be orientation-corrected and whose dimensions must
     * already be set on the media file. {@code fc} is the stored original,
     * needed only for {@link ExifPolicy#EXTRACT}. Stores the entity last.
     */
    static void rebuild(JPAPersistenceStrategy strategy, FileContentManager cmgr, MediaFile mediaFile,
            BufferedImage img, FileContent fc, ExifPolicy exifPolicy) throws WebloggerException {
        try {
            writeAdminThumbnail(cmgr, mediaFile, img);
        } catch (Exception e) {
            // the admin list falls back to the full image; the rest still runs
            log.warn("Could not write the admin thumbnail for media file {}", mediaFile.getId(), e);
        }

        // RenditionSupport never throws: per-rendition failures are logged at
        // WARN with the media file id.
        RenditionSupport.generate(cmgr, mediaFile, img);

        if (exifPolicy == ExifPolicy.EXTRACT) {
            extractExif(mediaFile, fc);
        }
        // from the smallest rendition, so after generate()
        try {
            mediaFile.setBlurhash(computeBlurhash(cmgr, mediaFile));
        } catch (Exception e) {
            log.debug("Could not compute blurhash for media file {}", mediaFile.getId(), e);
        }
        strategy.store(mediaFile);
    }

    /**
     * Renders and stores the {@code <id>_sm} admin thumbnail from the
     * already orientation-corrected image. The thumbnail box is derived from
     * the media file's stored width/height, so callers must have set those
     * from the same image first.
     */
    private static void writeAdminThumbnail(FileContentManager cmgr, MediaFile mediaFile,
            BufferedImage img) throws IOException, WebloggerException {
        BufferedImage tmp = adminThumbnail(img, mediaFile.getThumbnailWidth(), mediaFile.getThumbnailHeight());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(tmp, "png", baos);

        cmgr.saveFileContent(mediaFile.getWeblog(), mediaFile.getId()
                + "_sm", new ByteArrayInputStream(baos.toByteArray()));
    }

    /**
     * The admin thumbnail's pixels: {@code img} scaled to exactly {@code width} x {@code height}, ARGB, by
     * Thumbnailator, the scaler the responsive ladder uses. AWT's area-averaging getScaledInstance did this
     * until 2026-11-27, at several times the cost on every upload; AdminThumbnailTest keeps the result within a
     * small mean difference of what it made.
     */
    static BufferedImage adminThumbnail(BufferedImage img, int width, int height) throws IOException {
        return Thumbnails.of(img).forceSize(width, height).imageType(BufferedImage.TYPE_INT_ARGB).asBufferedImage();
    }

    /**
     * Reads EXIF/GPS metadata from the stored original ({@code fc}, re-opened
     * -- {@link FileContent#getInputStream()} returns a fresh stream backed
     * by the on-disk file each time it's called) and encodes a BlurHash
     * placeholder from the smallest available rendition. Applies the
     * {@code uploads.exif.stripGps} site setting by nulling the GPS fields
     * before they are ever persisted -- the original file on disk is never
     * touched either way. Never throws: unreadable EXIF leaves the fields empty.
     */
    private static void extractExif(MediaFile mediaFile, FileContent fc)
{
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
    }

    /**
     * Encodes a BlurHash from the smallest available rendition: the 480w
     * rung of the responsive ladder, falling back to the admin thumbnail
     * ({@code _sm}) when the original was narrower than 480 (so the ladder
     * skipped it). Returns null if neither is available or decodable.
     */
    private static String computeBlurhash(FileContentManager cmgr, MediaFile mediaFile) {
        for (String candidateId : List.of(mediaFile.getId() + "_480", mediaFile.getId() + "_sm")) {
            try {
                FileContent candidate = cmgr.getFileContent(mediaFile.getWeblog(), candidateId);
                BufferedImage image = ImageDecoding.read(candidate.getInputStream());
                if (image != null) {
                    return BlurHash.encode(image);
                }
            } catch (Exception e) {
                log.debug("No usable rendition '{}' for blurhash encoding", candidateId, e);
            }
        }
        return null;
    }
}
