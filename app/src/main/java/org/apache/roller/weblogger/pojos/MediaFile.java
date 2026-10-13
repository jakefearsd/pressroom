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
package org.apache.roller.weblogger.pojos;

import java.io.InputStream;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.util.Utilities;

/**
 * Represents a media file
 * 
 */
public class MediaFile implements Serializable {

    private static final long serialVersionUID = -6704258422169734004L;


    private String id = UUIDGenerator.generateUUID();

    private String name;
    private String description;
    private String altText;
    private String copyrightText;
    private long length;
    private int width = -1;
    private int height = -1;
    private int thumbnailHeight = -1;
    private int thumbnailWidth = -1;
    private String contentType;
    private String originalPath;
    private Timestamp dateUploaded = new Timestamp(System.currentTimeMillis());
    private Timestamp lastUpdated = new Timestamp(System.currentTimeMillis());
    private String creatorUserName;
    private Weblog weblog;

    private String blurhash;
    private String contentHash;
    private String exifCamera;
    private String exifLens;
    private String exifExposure;
    private String exifAperture;
    private Integer exifIso;
    private String exifFocalLength;
    private Timestamp exifTaken;
    private Double gpsLatitude;
    private Double gpsLongitude;

    private Integer sortOrder;
    private Double focalX;
    private Double focalY;

    private transient InputStream is;

    private transient MediaFileDirectory directory;

    private transient FileContent content;
    private transient FileContent thumbnail;

    // TODO: anchor to be populated
    // private String anchor;

    /**
     * The thumbnail's bounding box. Declared here rather than on the manager
     * interface because {@link #figureThumbnailSize()} is the entity's own
     * arithmetic and an entity does not reference the business tier;
     * {@code MediaFileManager.MAX_WIDTH}/{@code MAX_HEIGHT} alias these.
     */
    public static final int THUMBNAIL_MAX_WIDTH = 120;
    public static final int THUMBNAIL_MAX_HEIGHT = 120;

    private transient Set<MediaFileTag> tagSet = new HashSet<>();
    private transient Set<String> removedTags = new HashSet<>();
    private transient Set<String> addedTags = new HashSet<>();

    public MediaFile() {
    }

    /**
     * Database surrogate key.
     */
    public String getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Name for the media file
     * 
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Description for media file
     * 
     */
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * The author's description of what is in the image, for an alt
     * attribute. Raw storage, nullable: null means nobody has described this
     * image yet, as distinct from an author having deliberately left it
     * blank.
     */
    public String getAltText() {
        return altText;
    }

    public void setAltText(String altText) {
        this.altText = altText;
    }

    /**
     * Copyright text for media file
     *
     */
    public String getCopyrightText() {
        return copyrightText;
    }

    public void setCopyrightText(String copyrightText) {
        this.copyrightText = copyrightText;
    }

    /**
     * Size of the media file
     * 
     */
    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    /**
     * Date uploaded
     * 
     */
    public Timestamp getDateUploaded() {
        return dateUploaded;
    }

    public void setDateUploaded(Timestamp dateUploaded) {
        this.dateUploaded = dateUploaded;
    }

    public long getLastModified() {
        return getLastUpdated().getTime();
    }

    /**
     * Last updated timestamp
     * 
     */
    public Timestamp getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Timestamp time) {
        this.lastUpdated = time;
    }

    public MediaFileDirectory getDirectory() {
        return directory;
    }

    public void setDirectory(MediaFileDirectory dir) {
        this.directory = dir;
    }

    /**
     * Set of tags for this media file
     */
    public Set<MediaFileTag> getTags() {
        return tagSet;
    }

    // JPA property-access setter: MediaFile.orm.xml declares access="PROPERTY"
    // and maps the one-to-many "tags" (getTags()/setTags()), so EclipseLink
    // calls this reflectively when hydrating the entity even though no Java
    // caller ever does -- deleting it compiles clean and breaks persistence
    // in production.
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @SuppressFBWarnings(
            value = "UPM_UNCALLED_PRIVATE_METHOD",
            justification = "JPA property-access setter invoked reflectively by EclipseLink "
                    + "(MediaFile.orm.xml access=\"PROPERTY\", one-to-many \"tags\")")
    private void setTags(Set<MediaFileTag> tagSet) throws WebloggerException {
        this.tagSet = tagSet;
        this.removedTags = new HashSet<>();
        this.addedTags = new HashSet<>();
    }

    /**
     * Roller lowercases all tags based on locale because there's not a 1:1
     * mapping between uppercase/lowercase characters across all languages.
     * 
     * @param name
     * @throws WebloggerException
     */
    public void addTag(String name) throws WebloggerException {
        Locale localeObject = getWeblog() != null ? getWeblog()
                .getLocaleInstance() : Locale.getDefault();
        name = Utilities.normalizeTag(name, localeObject);
        if (name.length() == 0) {
            return;
        }

        for (MediaFileTag tag : getTags()) {
            if (tag.getName().equals(name)) {
                return;
            }
        }

        tagSet.add(new MediaFileTag(name, this));
        addedTags.add(name);
    }

    public void onRemoveTag(String name) throws WebloggerException {
        removedTags.add(name);
    }

    public Set<String> getAddedTags() {
        return addedTags;
    }

    public Set<String> getRemovedTags() {
        return removedTags;
    }

    public String getTagsAsString() {
        StringBuilder sb = new StringBuilder();
        for (MediaFileTag tag : getTags()) {
            sb.append(tag.getName()).append(" ");
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * Content type of the media file
     * 
     */
    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getPath() {
        return getDirectory().getName();
    }

    /**
     * Returns input stream for the underlying file in the file system.
     * 
     * @return
     */
    public InputStream getInputStream() {
        if (is != null) {
            return is;
        } else if (content != null) {
            return content.getInputStream();
        }
        return null;
    }

    public void setInputStream(InputStream is) {
        this.is = is;
    }

    public void setContent(FileContent content) {
        this.content = content;
    }

    /**
     * Indicates whether this is an image file.
     * 
     */
    public boolean isImageFile() {
        return getContentType() != null && getContentType().toLowerCase(Locale.ROOT).startsWith(MediaFileType.IMAGE
                .getContentTypePrefix().toLowerCase(Locale.ROOT));
    }

    // getPermalink()/getThumbnailURL() used to live here, reaching the
    // URLStrategy through the static service locator from inside a getter.
    // They are built by whoever holds the strategy now: MediaFileWrapper for
    // templates ($image.permalink) and AdminUrls.media()/mediaThumbnail() for
    // the admin JSPs (${urls.media(f)}).

    public String getCreatorUserName() {
        return creatorUserName;
    }

    public void setCreatorUserName(String creatorUserName) {
        this.creatorUserName = creatorUserName;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    /**
     * For old migrated files and theme resource files, orignal path of file can
     * never change.
     * 
     * @param originalPath
     *            the originalPath to set
     */
    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    /**
     * @return the weblog
     */
    public Weblog getWeblog() {
        return weblog;
    }

    /**
     * @param weblog
     *            the weblog to set
     */
    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * @return the width
     */
    public int getWidth() {
        return width;
    }

    /**
     * @param width
     *            the width to set
     */
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * @return the height
     */
    public int getHeight() {
        return height;
    }

    /**
     * @param height
     *            the height to set
     */
    public void setHeight(int height) {
        this.height = height;
    }

    /**
     * Returns input stream for the underlying thumbnail file in the file
     * system.
     * 
     * @return
     */
    public InputStream getThumbnailInputStream() {
        if (thumbnail != null) {
            return thumbnail.getInputStream();
        }
        return null;
    }

    public void setThumbnailContent(FileContent thumbnail) {
        this.thumbnail = thumbnail;
    }

    /**
     * @return the thumbnailHeight
     */
    public int getThumbnailHeight() {
        if (isImageFile() && (thumbnailWidth == -1 || thumbnailHeight == -1)) {
            figureThumbnailSize();
        }
        return thumbnailHeight;
    }

    /**
     * @return the thumbnailWidth
     */
    public int getThumbnailWidth() {
        if (isImageFile() && (thumbnailWidth == -1 || thumbnailHeight == -1)) {
            figureThumbnailSize();
        }
        return thumbnailWidth;
    }

    private void figureThumbnailSize() {
        // image determine thumbnail size
        int newWidth = getWidth();
        int newHeight = getHeight();

        if (getWidth() > getHeight()) {
            if (getWidth() > THUMBNAIL_MAX_WIDTH) {
                newHeight = (int) (getHeight() * ((float) THUMBNAIL_MAX_WIDTH / getWidth()));
                newWidth = THUMBNAIL_MAX_WIDTH;
            }

        } else {
            if (getHeight() > THUMBNAIL_MAX_HEIGHT) {
                newWidth = (int) (getWidth() * ((float) THUMBNAIL_MAX_HEIGHT / getHeight()));
                newHeight = THUMBNAIL_MAX_HEIGHT;
            }
        }
        thumbnailHeight = newHeight;
        thumbnailWidth = newWidth;
    }

    /**
     * BlurHash placeholder string encoded from the smallest available
     * rendition at upload time (480w rung, or the admin thumbnail if the
     * original was narrower than that). Null if encoding failed or this is
     * not an image file.
     */
    public String getBlurhash() {
        return blurhash;
    }

    public void setBlurhash(String blurhash) {
        this.blurhash = blurhash;
    }

    /**
     * SHA-256 (lower-case hex) of the bytes this file was created with, or
     * null: for files from before V032, and once the content is replaced or
     * cropped. An import of identical bytes into the same directory returns
     * this file instead of storing a copy.
     */
    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    /** Combined camera make/model read from EXIF, e.g. "Canon EOS R5". Null if absent or unreadable. */
    public String getExifCamera() {
        return exifCamera;
    }

    public void setExifCamera(String exifCamera) {
        this.exifCamera = exifCamera;
    }

    /** Lens model read from EXIF. Null if absent or unreadable. */
    public String getExifLens() {
        return exifLens;
    }

    public void setExifLens(String exifLens) {
        this.exifLens = exifLens;
    }

    /** Human-readable exposure time (e.g. "1/1000 sec") read from EXIF. Null if absent or unreadable. */
    public String getExifExposure() {
        return exifExposure;
    }

    public void setExifExposure(String exifExposure) {
        this.exifExposure = exifExposure;
    }

    /** Human-readable aperture (e.g. "f/5.6") read from EXIF. Null if absent or unreadable. */
    public String getExifAperture() {
        return exifAperture;
    }

    public void setExifAperture(String exifAperture) {
        this.exifAperture = exifAperture;
    }

    /** ISO speed rating read from EXIF. Null if absent or unreadable. */
    public Integer getExifIso() {
        return exifIso;
    }

    public void setExifIso(Integer exifIso) {
        this.exifIso = exifIso;
    }

    /** Human-readable focal length (e.g. "400 mm") read from EXIF. Null if absent or unreadable. */
    public String getExifFocalLength() {
        return exifFocalLength;
    }

    public void setExifFocalLength(String exifFocalLength) {
        this.exifFocalLength = exifFocalLength;
    }

    /** Original-capture timestamp read from EXIF. Null if absent or unreadable. */
    public Timestamp getExifTaken() {
        return exifTaken;
    }

    public void setExifTaken(Timestamp exifTaken) {
        this.exifTaken = exifTaken;
    }

    /**
     * GPS latitude read from EXIF, decimal degrees. Null if the image carried
     * no GPS block, or if {@code uploads.exif.stripGps} removed it at upload
     * time for privacy.
     */
    public Double getGpsLatitude() {
        return gpsLatitude;
    }

    public void setGpsLatitude(Double gpsLatitude) {
        this.gpsLatitude = gpsLatitude;
    }

    /**
     * GPS longitude read from EXIF, decimal degrees. Null if the image
     * carried no GPS block, or if {@code uploads.exif.stripGps} removed it
     * at upload time for privacy.
     */
    public Double getGpsLongitude() {
        return gpsLongitude;
    }

    public void setGpsLongitude(Double gpsLongitude) {
        this.gpsLongitude = gpsLongitude;
    }

    /**
     * Curated position of this file within its directory's gallery, lowest
     * first. Null means "never ordered": galleries emit the curated block
     * first (sort_order, then name as the tie-break), then unordered files
     * by name. The directory's one-to-many itself stays name-ordered; this
     * ordering is applied in code by the gallery emitter.
     */
    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * Horizontal focal-point coordinate as a 0..1 fraction of the image
     * width, for object-position style cropping. Null means center.
     */
    public Double getFocalX() {
        return focalX;
    }

    public void setFocalX(Double focalX) {
        this.focalX = focalX;
    }

    /**
     * Vertical focal-point coordinate as a 0..1 fraction of the image
     * height, for object-position style cropping. Null means center.
     */
    public Double getFocalY() {
        return focalY;
    }

    public void setFocalY(Double focalY) {
        this.focalY = focalY;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "MediaFile [name=" + getName() + ", directory=" + getDirectory()
                + ", weblog=" + getWeblog() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof MediaFile)) {
            return false;
        }
        MediaFile o = (MediaFile) other;
        return new EqualsBuilder().append(getId(), o.getId()).isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
