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
package com.wikantik.pressroom.business;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

/**
 * Reads camera/lens/exposure/GPS metadata from an uploaded image using
 * metadata-extractor. Called once per create/update from a freshly re-opened
 * stream on the file already saved to disk (see JPAMediaFileManagerImpl#updateThumbnail)
 * rather than the original upload stream, which storage has already consumed.
 *
 * <p>Unsupported formats, images with no EXIF block at all, and any parsing
 * error all produce an {@link ExifData} of all-null fields -- extraction
 * failure is never a reason to fail an upload.
 */
public final class ExifSupport {

    private static final Logger log = LoggerFactory.getLogger(ExifSupport.class);

    /**
     * Column width of {@code roller_mediafile.exif_camera} (migration V006).
     *
     * <p>EXIF ASCII tags are attacker-controlled and unbounded -- a crafted
     * JPEG can carry a 500-character Make -- while the columns these strings
     * land in are not. Truncating here, at the one place these values are
     * produced, keeps an over-long tag from failing the deferred flush of the
     * whole upload transaction (which happens well outside the catch blocks
     * that make every other metadata failure non-fatal, so the upload would
     * fail with the file bytes already orphaned on disk).
     */
    public static final int MAX_CAMERA_LENGTH = 128;

    /** Column width of {@code roller_mediafile.exif_lens} (V006). See {@link #MAX_CAMERA_LENGTH}. */
    public static final int MAX_LENS_LENGTH = 128;

    /**
     * Column width of {@code exif_exposure}, {@code exif_aperture} and
     * {@code exif_focal_length} (V006). See {@link #MAX_CAMERA_LENGTH}.
     */
    public static final int MAX_SETTING_LENGTH = 32;

    /** EXIF Orientation value meaning "no rotation needed", and the default when the tag is absent. */
    public static final int ORIENTATION_NORMAL = 1;

    private ExifSupport() {
    }

    /** Immutable holder for whatever EXIF/GPS fields could be read; every field may be null. */
    public static final class ExifData {
        public final String camera;
        public final String lens;
        public final String exposure;
        public final String aperture;
        public final Integer iso;
        public final String focalLength;
        public final Timestamp taken;
        public final Double gpsLatitude;
        public final Double gpsLongitude;

        ExifData(String camera, String lens, String exposure, String aperture, Integer iso,
                String focalLength, Timestamp taken, Double gpsLatitude, Double gpsLongitude) {
            this.camera = camera;
            this.lens = lens;
            this.exposure = exposure;
            this.aperture = aperture;
            this.iso = iso;
            this.focalLength = focalLength;
            this.taken = taken;
            this.gpsLatitude = gpsLatitude;
            this.gpsLongitude = gpsLongitude;
        }

        public static final ExifData EMPTY =
                new ExifData(null, null, null, null, null, null, null, null, null);
    }

    /**
     * Extracts whatever EXIF/GPS fields are present in {@code is}. Never
     * throws: any exception (unreadable format, corrupt EXIF block, I/O
     * error) is logged at DEBUG and produces {@link ExifData#EMPTY}.
     */
    public static ExifData extract(InputStream is) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(is);

            String camera = null;
            String lens = null;
            String exposure = null;
            String aperture = null;
            Integer iso = null;
            String focalLength = null;
            Timestamp taken = null;

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                camera = combineMakeAndModel(ifd0.getString(ExifDirectoryBase.TAG_MAKE),
                        ifd0.getString(ExifDirectoryBase.TAG_MODEL));
            }

            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                lens = nullIfBlank(subIfd.getString(ExifDirectoryBase.TAG_LENS_MODEL));
                exposure = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_EXPOSURE_TIME));
                aperture = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_FNUMBER));
                iso = subIfd.getInteger(ExifDirectoryBase.TAG_ISO_EQUIVALENT);
                focalLength = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_FOCAL_LENGTH));
                // EXIF's DateTimeOriginal (0x9003) is a bare "YYYY:MM:DD HH:MM:SS"
                // string with no timezone/offset of its own -- the camera simply
                // doesn't record one. getDate(int) with no TimeZone argument has
                // metadata-extractor parse it in the JVM's default zone, which is
                // an approximation (the photo may have been taken somewhere else)
                // but matches how every other timestamp in this codebase is
                // handled absent better information. EXIF 2.31+ can carry a
                // separate OffsetTimeOriginal (0x9011) tag with the true UTC
                // offset; a future refinement could consult it when present and
                // fall back to this approximation only when it's absent.
                Date takenDate = subIfd.getDate(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
                if (takenDate != null) {
                    taken = new Timestamp(takenDate.getTime());
                }
            }

            Double gpsLatitude = null;
            Double gpsLongitude = null;
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDir != null) {
                GeoLocation location = gpsDir.getGeoLocation();
                if (location != null && !location.isZero()) {
                    gpsLatitude = location.getLatitude();
                    gpsLongitude = location.getLongitude();
                }
            }

            // Clamp every string to the width of the column it will be stored
            // in -- see MAX_CAMERA_LENGTH for why this cannot be left to the
            // database.
            return new ExifData(clamp(camera, MAX_CAMERA_LENGTH), clamp(lens, MAX_LENS_LENGTH),
                    clamp(exposure, MAX_SETTING_LENGTH), clamp(aperture, MAX_SETTING_LENGTH), iso,
                    clamp(focalLength, MAX_SETTING_LENGTH), taken, gpsLatitude, gpsLongitude);
        } catch (Exception e) {
            // Not every upload is a format metadata-extractor understands (or has EXIF
            // at all) -- that is the normal case, not a failure worth surfacing.
            log.debug("No EXIF metadata extracted", e);
            return ExifData.EMPTY;
        }
    }

    /**
     * Combines EXIF Make/Model into one display string, e.g. "Canon" + "Canon EOS R5"
     * to "Canon EOS R5" (avoiding the duplicated make many cameras embed in Model),
     * or "Canon" + "EOS R5" to "Canon EOS R5". Returns null if both are blank.
     */
    private static String combineMakeAndModel(String make, String model) {
        make = nullIfBlank(make);
        model = nullIfBlank(model);
        if (make == null) {
            return model;
        }
        if (model == null) {
            return make;
        }
        if (model.toLowerCase(Locale.ROOT).startsWith(make.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return make + " " + model;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Truncates {@code s} to at most {@code maxLength} characters; null-safe. */
    private static String clamp(String s, int maxLength) {
        return StringUtils.truncate(s, maxLength);
    }

    /**
     * Reads the EXIF Orientation tag (1-8) from {@code is}, returning
     * {@link #ORIENTATION_NORMAL} when the tag is absent, out of range, or
     * unreadable -- an unreadable tag must never cause a photo to be rotated
     * on a guess.
     *
     * <p>Deliberately a second, separate parse of the file rather than another
     * field on {@link ExifData}: orientation is needed at the very top of the
     * pipeline (before the decoded raster is used for dimensions, thumbnail,
     * renditions and blurhash), while {@link #extract} runs at the end, after
     * the renditions the blurhash is encoded from exist. One extra metadata
     * read is negligible next to four resizes.
     */
    public static int readOrientation(InputStream is) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(is);
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
                int orientation = ifd0.getInt(ExifDirectoryBase.TAG_ORIENTATION);
                if (orientation >= 1 && orientation <= 8) {
                    return orientation;
                }
                log.debug("Ignoring out-of-range EXIF orientation {}", orientation);
            }
        } catch (Exception e) {
            // Same normal case as extract(): plenty of uploads have no EXIF at all.
            log.debug("No EXIF orientation read", e);
        }
        return ORIENTATION_NORMAL;
    }
}
