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

package com.wikantik.pressroom.pojos.wrapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import com.wikantik.pressroom.business.BlurHash;
import com.wikantik.pressroom.business.RenditionSupport;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileTag;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pojo safety wrapper for MediaFile objects, adding srcset-ready accessors
 * for the responsive-image rendition ladder (see {@link RenditionSupport})
 * on top of the usual sanitize-on-the-way-out behavior of the other
 * wrappers.
 */
public final class MediaFileWrapper {

    private static final Logger log = LoggerFactory.getLogger(MediaFileWrapper.class);

    // keep a reference to the wrapped pojo
    private final MediaFile pojo;

    // url strategy to use for any url building
    private final URLStrategy urlStrategy;

    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;

    // this is private so that we can force the use of the .wrap(pojo) method
    private MediaFileWrapper(MediaFile toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }

    /**
     * Wrap the given pojo if it is not null. The wrapper is the template API
     * and does its own lookups: it is handed the (possibly preview-aware)
     * {@link URLStrategy} every URL it emits must come from, and the business
     * tier it resolves related objects through -- never a static locator.
     */
    public static MediaFileWrapper wrap(MediaFile toWrap, URLStrategy strat, Weblogger weblogger) {
        if (toWrap != null) {
            return new MediaFileWrapper(toWrap, strat, weblogger);
        }
        return null;
    }

    public String getId() {
        return this.pojo.getId();
    }

    public String getName() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getName());
    }

    public String getDescription() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getDescription());
    }

    /**
     * The author's description of what is in the image, for an alt attribute.
     *
     * <p>Sanitised the same way {@link #getDescription()} is, and for the same
     * reason: it is author input that reaches a rendered page. Null when nobody
     * has described this image yet — callers pick the fallback, because what a
     * sensible fallback is depends on where the image is being rendered.
     */
    public String getAltText() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getAltText());
    }

    public String getContentType() {
        return this.pojo.getContentType();
    }

    public int getWidth() {
        return this.pojo.getWidth();
    }

    public int getHeight() {
        return this.pojo.getHeight();
    }

    public Timestamp getDateUploaded() {
        return this.pojo.getDateUploaded();
    }

    public Timestamp getLastUpdated() {
        return this.pojo.getLastUpdated();
    }

    /**
     * The uploader, resolved by name through the tier this wrapper was given;
     * null (never an exception) when the name no longer resolves, so a
     * byline cannot break a page.
     */
    public UserWrapper getCreator() {
        try {
            return UserWrapper.wrap(weblogger.getUserManager()
                    .getUserByUserName(this.pojo.getCreatorUserName()));
        } catch (Exception e) {
            log.error("ERROR fetching user object for username: {}", this.pojo.getCreatorUserName(), e);
            return null;
        }
    }

    public List<MediaFileTag> getTags() {
        return this.pojo.getTags().stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
    }

    /**
     * Permalink to the original, full-size file, from the strategy this
     * wrapper was given -- a theme preview stays inside the preview.
     */
    // SpotBugs pairs this with the unrelated, @Deprecated WeblogEntry.getPermaLink()
    // on case alone (NM_CONFUSING); the suppression sits on that method, which is
    // where SpotBugs anchors the finding. getPermalink() is the canonical template
    // spelling ($image.permalink) and cannot be renamed.
    public String getPermalink() {
        return urlStrategy.getMediaFileURL(this.pojo.getWeblog(), this.pojo.getId(), true);
    }

    /** Permalink to the admin/feed thumbnail (the {@code _sm} rendition). */
    public String getThumbnailURL() {
        return urlStrategy.getMediaFileThumbnailURL(this.pojo.getWeblog(), this.pojo.getId(), true);
    }

    /**
     * BlurHash placeholder string for a CSS/JS blur-up effect while the real
     * image loads. Null if it was never encoded (upload predates the
     * rendition pipeline, or encoding failed).
     */
    public String getBlurhash() {
        return this.pojo.getBlurhash();
    }

    /**
     * The BlurHash's average color as a CSS hex value ({@code #8ca3b7}),
     * for a JavaScript-free {@code background-color} placeholder behind the
     * loading image. Null whenever {@link #getBlurhash()} is null or corrupt.
     */
    public String getAverageColor() {
        return BlurHash.averageColor(this.pojo.getBlurhash());
    }

    /**
     * The ready-to-emit {@code srcset} attribute value for this image:
     * every {@link RenditionSupport#LADDER_WIDTHS ladder} rung narrower than
     * the original, then the original itself as the largest candidate --
     * e.g. {@code ...?w=480 480w, ...?w=960 960w, ... 1200w}.
     *
     * <p>Null -- emit a plain {@code <img>} instead -- when the format is
     * not {@linkplain #isRenditionEligible() rendition-eligible} (for
     * gif/bmp every {@code ?w=} URL silently serves the full-size original,
     * so offering a srcset or a webp {@code <source>} would lie to the
     * browser) or when the stored width is unknown (pre-ladder uploads:
     * we cannot know which rungs exist, and an honest src always renders).
     * The single source of truth for this markup: the {@code [image]}
     * shortcode and the {@code #showResponsiveImage} theme macro both emit
     * exactly this value.
     */
    public String getSrcset() {
        int originalWidth = getWidth();
        if (originalWidth <= 0 || !isRenditionEligible()) {
            return null;
        }
        StringBuilder srcset = new StringBuilder();
        for (int width : RenditionSupport.LADDER_WIDTHS) {
            if (width >= originalWidth) {
                break;
            }
            srcset.append(url(width)).append(' ').append(width).append("w, ");
        }
        srcset.append(getPermalink()).append(' ').append(originalWidth).append('w');
        return srcset.toString();
    }

    /**
     * URL for the {@code width}-wide rendition of this image, for use in a
     * {@code srcset}. Falls back to the original's permalink when
     * {@code width} is not one of {@link RenditionSupport#ladderWidths()} --
     * the servlet does the same fallback when the rendition itself is
     * missing (narrower than the original, or generation failed), so this
     * URL is always safe to emit even if that particular rung doesn't
     * actually exist on disk.
     */
    public String url(int width) {
        if (!RenditionSupport.ladderWidths().contains(width)) {
            return getPermalink();
        }
        return getPermalink() + (getPermalink().contains("?") ? "&" : "?") + "w=" + width;
    }

    /**
     * True when the rendition ladder covers this file's format (jpeg/png),
     * i.e. when a {@link #url(int)} rendition URL really serves a resized
     * image. For other formats (gif, bmp, ...) the servlet silently serves
     * the full-resolution original at the same URL, so templates that
     * declare the served image's dimensions -- the og:image:width/height
     * pair in {@code #showSeoHead} -- must fall back to the original's URL
     * and stored dimensions when this is false.
     */
    public boolean isRenditionEligible() {
        return RenditionSupport.isLadderEligible(this.pojo.getContentType());
    }

    /**
     * Same URL as {@link #url(int)}: WebP is served for this same URL via
     * {@code Accept}-header content negotiation (see
     * {@code MediaResourceServlet}) rather than a distinct path, so a
     * {@code <source type="image/webp">} element can point straight at it.
     * Kept as a separate accessor because a future serving change (e.g. an
     * explicit {@code ?fmt=webp}) should only require updating this method,
     * not every template that calls it.
     */
    public String webpUrl(int width) {
        return url(width);
    }

    // ---------------------------------------------------------- EXIF display

    public String getExifCamera() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getExifCamera());
    }

    public String getExifLens() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getExifLens());
    }

    public String getExifExposure() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getExifExposure());
    }

    public String getExifAperture() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getExifAperture());
    }

    public Integer getExifIso() {
        return this.pojo.getExifIso();
    }

    public String getExifFocalLength() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getExifFocalLength());
    }

    public Timestamp getExifTaken() {
        return this.pojo.getExifTaken();
    }

    /** Null whenever GPS was absent from the source image or stripped by {@code uploads.exif.stripGps}. */
    public Double getGpsLatitude() {
        return this.pojo.getGpsLatitude();
    }

    public Double getGpsLongitude() {
        return this.pojo.getGpsLongitude();
    }

    // ------------------------------------------------------- Gallery display

    /**
     * Curated position within the directory's gallery, lowest first; null
     * for files that were never explicitly ordered (they sort after the
     * curated block, by name).
     */
    public Integer getSortOrder() {
        return this.pojo.getSortOrder();
    }

    /**
     * Horizontal focal-point coordinate, a 0..1 fraction of the image width
     * for {@code object-position} style cropping. Null means center.
     */
    public Double getFocalX() {
        return this.pojo.getFocalX();
    }

    /**
     * Vertical focal-point coordinate, a 0..1 fraction of the image height
     * for {@code object-position} style cropping. Null means center.
     */
    public Double getFocalY() {
        return this.pojo.getFocalY();
    }

    /**
     * The focal point as a ready-to-emit CSS {@code object-position} value,
     * e.g. {@code "45.6% 30%"}, or null when no focal point is set. Formatted
     * here rather than in Velocity so the percentages are stable, clamped and
     * free of floating-point noise ({@code 0.456 * 100} is not {@code 45.6}
     * in double arithmetic). Consumed by {@code #showResponsiveImage} --
     * theme-side markup only, never the sanitized entry-content path.
     */
    public String getObjectPosition() {
        Double fx = this.pojo.getFocalX();
        Double fy = this.pojo.getFocalY();
        if (fx == null || fy == null) {
            return null;
        }
        return cssPercent(fx) + " " + cssPercent(fy);
    }

    /** Formats a 0..1 fraction as a CSS percentage with at most one decimal. */
    private static String cssPercent(double fraction) {
        double clamped = Math.min(1.0, Math.max(0.0, fraction));
        return java.math.BigDecimal.valueOf(clamped * 100)
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    /**
     * This is a special method to access the original pojo. We don't really
     * want to do this, but it's necessary because some parts of the
     * rendering process still need the original pojo object.
     */
    public MediaFile getPojo() {
        return this.pojo;
    }
}
