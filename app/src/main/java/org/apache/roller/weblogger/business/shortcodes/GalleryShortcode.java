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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;

/**
 * The built-in {@code [gallery dir="Name" row="260" max="0"]} shortcode:
 * renders every image in the entry's weblog's named media directory as a
 * justified grid ({@code <div class="jgrid">} of {@code <figure>}s packed by
 * the flex-grow CSS in the {@code #showGalleryGridStyles} theme macro).
 *
 * <p>Each figure carries the image's aspect ratio as a CSS custom property
 * ({@code style="--ar:1.3405;"}) -- verified to survive the
 * HTML sanitizer's style re-parse by
 * {@code HTMLSanitizerTest.GalleryGridMarkup} -- and wraps the {@code <img>}
 * in an anchor to the full-size original for the lightbox (Wave 2 T4):
 * {@code data-pswp-width}/{@code data-pswp-height} full-size dimensions,
 * {@code data-caption}, {@code data-exif-*} overlay payload, and
 * {@code data-blurhash}, all double-quoted and null-skipped so the sanitizer
 * keeps them and the lightbox never sees empty values.
 *
 * <p>Ordering is the curated block first -- {@code sortOrder} ascending with
 * ties broken by name -- then the never-ordered files ({@code sortOrder}
 * null) by name, matching the contract documented on
 * {@link MediaFile#getSortOrder()}.
 *
 * <p>Returns null -- the expander's "leave the shortcode text exactly as
 * written" failure signal -- when the {@code dir} attribute is missing, the
 * directory does not exist, it is {@linkplain MediaFileDirectory#isPrivate()
 * private} (share-page rendering is a separate, gated path), or it contains
 * no image files.
 */
public class GalleryShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(GalleryShortcode.class);

    @Override
    public String getName() {
        return "gallery";
    }

    private final Weblogger weblogger;

    /**
     * @param weblogger the tier whose media manager resolves the directory and
     *                  whose url strategy the files are wrapped with; never
     *                  dereferenced at construction, so a lazy proxy is fine
     */
    public GalleryShortcode(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("gallery", "shortcode.gallery.label",
                "[gallery dir=\"default\" row=\"320\"]");
    }

    @Override
    public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
        String directoryName = attributes.get("dir");
        if (StringUtils.isBlank(directoryName)) {
            log.debug("[gallery] shortcode without a dir attribute; leaving it as written");
            return null;
        }
        Weblog weblog = content == null ? null : content.getWeblog();
        if (weblog == null) {
            return null;
        }

        List<MediaFileWrapper> images;
        try {
            MediaFileDirectory directory = weblogger
                    .getMediaFileManager().getMediaFileDirectoryByName(weblog, directoryName);
            if (directory == null) {
                log.debug("[gallery] shortcode dir \"{}\" does not exist in weblog {}",
                        directoryName, weblog.getHandle());
                return null;
            }
            if (directory.isPrivate()) {
                // A private directory is only reachable through its share
                // link (Wave 2 T5); the normal render path must not leak it.
                log.debug("[gallery] shortcode dir \"{}\" is private; not rendering it inline",
                        directoryName);
                return null;
            }
            images = directory.getMediaFiles().stream()
                    .filter(MediaFile::isImageFile)
                    .sorted(GalleryMarkup.GALLERY_ORDER)
                    .map(f -> MediaFileWrapper.wrap(f, weblogger.getUrlStrategy(), weblogger))
                    .toList();
        } catch (Exception e) {
            // Null leaves the [gallery] text in the page on purpose: a visible broken tag tells the author
            // something is wrong, an empty block would not. Only the log says why.
            log.warn("[gallery] shortcode could not resolve directory \"{}\"",
                    directoryName, e);
            return null;
        }

        int max = parsePositiveInt(attributes.get("max"), 0);
        if (max > 0 && images.size() > max) {
            images = images.subList(0, max);
        }
        if (images.isEmpty()) {
            log.debug("[gallery] shortcode dir \"{}\" holds no image files; leaving it as written",
                    directoryName);
            return null;
        }

        int rowHeight = parsePositiveInt(attributes.get("row"), 0);
        // Emission is shared with the share-page renderer (GalleryMarkup);
        // the inline path always uses the public permalink URL space.
        return GalleryMarkup.grid(images, rowHeight, GalleryMarkup.PERMALINKS);
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
