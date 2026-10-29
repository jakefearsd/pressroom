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
package com.wikantik.pressroom.ui.controllers.editor;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.RenditionSupport;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.util.Utilities;

/**
 * Bean for managing media file.
 */
public class MediaFileBean {

    private String id;
    private String name;
    private String description;
    private String altText;
    private String contentType;
    private String copyrightText;
    private String tagsAsString;
    private String directoryId;
    private String permalink;
    private String thumbnailURL;
    private boolean isImage;
    private int width;
    private int height;
    private long length;
    private String originalPath;
    private Double focalX;
    private Double focalY;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAltText() {
        return altText;
    }

    public void setAltText(String altText) {
        this.altText = altText;
    }

    public String getCopyrightText() {
        return copyrightText;
    }

    public void setCopyrightText(String copyrightText) {
        this.copyrightText = copyrightText;
    }

    public String getTagsAsString() {
        return this.tagsAsString;
    }

    public void setTagsAsString(String tagsAsString) {
        this.tagsAsString = tagsAsString;
    }

    public String getDirectoryId() {
        return directoryId;
    }

    public void setDirectoryId(String directoryId) {
        this.directoryId = directoryId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Copies the contents of this bean to a media file object
     * 
     */
    public void copyTo(MediaFile dataHolder, MediaFileManager mediaFileManager) throws WebloggerException {

        dataHolder.setName(this.name);
        dataHolder.setDescription(this.description);
        dataHolder.setAltText(this.altText);
        dataHolder.setCopyrightText(this.copyrightText);
        // The tags go through the manager the caller hands in -- replacing a
        // file's tags removes the ones no longer named, a write the entity used
        // to issue itself through the static service locator; a form bean must
        // not locate anything either. A null string clears them, as before.
        if (this.tagsAsString == null) {
            dataHolder.getTags().clear();
        } else {
            mediaFileManager.updateTags(dataHolder, Utilities.splitStringAsTags(this.tagsAsString));
        }
        dataHolder.setOriginalPath(this.originalPath);
        // The focal point is both-or-neither: a lone coordinate cannot
        // position anything, so it degrades to "no focal point" rather than
        // persisting half a value.
        if (this.focalX != null && this.focalY != null) {
            dataHolder.setFocalX(clampFraction(this.focalX));
            dataHolder.setFocalY(clampFraction(this.focalY));
        } else {
            dataHolder.setFocalX(null);
            dataHolder.setFocalY(null);
        }
    }

    /** Clamps a focal-point coordinate into its documented 0..1 range. */
    private static double clampFraction(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    /**
     * Populates this bean from a media file object. The permalink and
     * thumbnail url are built from the {@link URLStrategy} the caller hands in
     * -- the entity no longer builds them itself (it used to reach the strategy
     * through the static service locator), and a form bean must not locate
     * anything either.
     */
    public void copyFrom(MediaFile dataHolder, URLStrategy urls) {
        this.setId(dataHolder.getId());
        this.setName(dataHolder.getName());
        this.setDescription(dataHolder.getDescription());
        this.setAltText(dataHolder.getAltText());
        this.setCopyrightText(dataHolder.getCopyrightText());
        this.setTagsAsString(dataHolder.getTagsAsString());
        this.setDirectoryId(dataHolder.getDirectory().getId());
        this.setPermalink(urls.getMediaFileURL(dataHolder.getWeblog(), dataHolder.getId(), true));
        this.setThumbnailURL(urls.getMediaFileThumbnailURL(dataHolder.getWeblog(), dataHolder.getId(), true));
        this.setIsImage(dataHolder.isImageFile());
        this.setWidth(dataHolder.getWidth());
        this.setHeight(dataHolder.getHeight());
        this.setLength(dataHolder.getLength());
        this.setContentType(dataHolder.getContentType());
        this.setOriginalPath(dataHolder.getOriginalPath());
        this.setFocalX(dataHolder.getFocalX());
        this.setFocalY(dataHolder.getFocalY());
    }

    /**
     * @return the permalink
     */
    public String getPermalink() {
        return permalink;
    }

    /**
     * @param permalink
     *            the permalink to set
     */
    public void setPermalink(String permalink) {
        this.permalink = permalink;
    }

    /**
     * @return the isImage
     */
    public boolean isIsImage() {
        return isImage;
    }

    /**
     * @param isImage
     *            the isImage to set
     */
    public void setIsImage(boolean isImage) {
        this.isImage = isImage;
    }

    /**
     * @return the thumbnailURL
     */
    public String getThumbnailURL() {
        return thumbnailURL;
    }

    /**
     * @param thumbnailURL
     *            the thumbnailURL to set
     */
    public void setThumbnailURL(String thumbnailURL) {
        this.thumbnailURL = thumbnailURL;
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
     * @return the length
     */
    public long getLength() {
        return length;
    }

    /**
     * @param length
     *            the length to set
     */
    public void setLength(long length) {
        this.length = length;
    }

    /**
     * @return the contentType
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * @param contentType
     *            the contentType to set
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * True when the crop UI can be offered: the server-side re-encode path
     * only exists for the format families the rendition ladder covers
     * (JPEG and PNG), so anything else (gif, bmp, ...) gets no crop section.
     */
    public boolean isCroppable() {
        return isImage && RenditionSupport.isLadderEligible(contentType);
    }

    /** Horizontal focal-point coordinate, 0..1 fraction of the width; null means unset. */
    public Double getFocalX() {
        return focalX;
    }

    public void setFocalX(Double focalX) {
        this.focalX = focalX;
    }

    /** Vertical focal-point coordinate, 0..1 fraction of the height; null means unset. */
    public Double getFocalY() {
        return focalY;
    }

    public void setFocalY(Double focalY) {
        this.focalY = focalY;
    }

    /**
     * @return the originalPath
     */
    public String getOriginalPath() {
        return originalPath;
    }

    /**
     * @param originalPath
     *            the originalPath to set
     */
    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }
}
