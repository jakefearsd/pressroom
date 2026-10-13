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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The single emitter of justified-grid gallery markup: a {@code <div
 * class="jgrid">} of {@code <figure>}s carrying the aspect-ratio custom
 * property, the lightbox anchor with its {@code data-*} payload, and the
 * responsive {@code <img>}, used by the inline {@link GalleryShortcode}
 * (public directories, media URLs from {@link MediaFileWrapper#getPermalink()}).
 *
 * <p>The markup contract -- what each attribute is for, and why every value
 * is double-quoted and null-skipped -- is documented on
 * {@link GalleryShortcode}; the grid CSS lives in the
 * {@code #showGalleryGridStyles} macro in
 * {@code WEB-INF/velocity/weblog.vm}.
 */
public final class GalleryMarkup {

    /** Desktop target row height (px) when none is given; mirrored in the grid CSS. */
    public static final int DEFAULT_ROW_HEIGHT = 260;

    /** Mobile row height (px) from the grid CSS's 640px breakpoint, used only for the sizes hint. */
    private static final int MOBILE_ROW_HEIGHT = 160;

    /**
     * Where a gallery image's URLs come from. The grid needs two: the
     * full-size original (anchor href and {@code <img src>}) and the
     * {@code ?w=} rendition rungs for the {@code srcset}.
     */
    public interface ImageUrls {

        /** Absolute URL of the full-size original. */
        String original(MediaFileWrapper image);

        /** Absolute URL of the {@code width}-wide rendition. */
        String rendition(MediaFileWrapper image, int width);
    }

    /**
     * The normal public URL space: the media-resource permalink and its
     * {@code ?w=} variants, exactly what {@link MediaFileWrapper#getSrcset()}
     * emits.
     */
    public static final ImageUrls PERMALINKS = new ImageUrls() {
        @Override
        public String original(MediaFileWrapper image) {
            return image.getPermalink();
        }

        @Override
        public String rendition(MediaFileWrapper image, int width) {
            return image.url(width);
        }
    };

    /**
     * Gallery display order: the curated block first ({@code sortOrder}
     * ascending, name breaking ties), then the never-ordered rest by name --
     * see {@link MediaFile#getSortOrder()}.
     */
    public static final Comparator<MediaFile> GALLERY_ORDER = Comparator
            .comparing(MediaFile::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MediaFile::getName, Comparator.nullsLast(Comparator.naturalOrder()));

    private GalleryMarkup() {
        // static use only
    }

    /**
     * The full grid for the given (already filtered and ordered) images.
     *
     * @param rowHeight explicit target row height in px, or {@code 0} for
     *                  {@link #DEFAULT_ROW_HEIGHT}
     */
    public static String grid(List<MediaFileWrapper> images, int rowHeight, ImageUrls urls) {
        StringBuilder html = new StringBuilder(512 * images.size());
        // Row height rides as a class for the same reason the aspect ratio
        // does: the sanitizer drops inline custom properties. Author values
        // snap to the ladder the stylesheet has rules for, and the snapped
        // value -- not the requested one -- drives the sizes attribute below,
        // so the hint the browser gets matches the layout it will get.
        int snapped = snapRowHeight(rowHeight);
        html.append("<div class=\"jgrid");
        if (snapped != DEFAULT_ROW_HEIGHT) {
            html.append(" jgrid-h").append(snapped);
        }
        html.append("\">\n");
        for (MediaFileWrapper image : images) {
            appendFigure(html, image, snapped, urls);
        }
        html.append("</div>");
        return html.toString();
    }

    private static void appendFigure(StringBuilder html, MediaFileWrapper image,
            int rowHeight, ImageUrls urls) {
        boolean knownDimensions = image.getWidth() > 0 && image.getHeight() > 0;
        double aspectRatio = knownDimensions
                ? (double) image.getWidth() / image.getHeight() : 0;

        html.append("<figure");
        if (knownDimensions) {
            // The grid packs rows from a --ar custom property, but the
            // sanitizer cannot carry one: OWASP's CSS schema rejects custom
            // properties outright, so an inline style="--ar:1.34" is dropped.
            // The ratio therefore travels as a class, and the theme's own
            // (unsanitized) style block turns it back into --ar. Quantizing to
            // 0.05 keeps the rule table small; the packing difference is
            // invisible.
            html.append(" class=\"").append(aspectRatioClass(aspectRatio)).append('"');
        }
        html.append(">\n");

        // ---- lightbox anchor: absolute original + data payload (T4 reads these)
        html.append("<a href=\"").append(urls.original(image)).append('"');
        if (knownDimensions) {
            html.append(" data-pswp-width=\"").append(image.getWidth())
                    .append("\" data-pswp-height=\"").append(image.getHeight()).append('"');
        }
        String caption = StringUtils.trimToNull(image.getDescription());
        appendDataAttribute(html, "data-caption", escape(caption));
        appendDataAttribute(html, "data-exif-camera", escape(image.getExifCamera()));
        appendDataAttribute(html, "data-exif-lens", escape(image.getExifLens()));
        appendDataAttribute(html, "data-exif-exposure", escape(image.getExifExposure()));
        appendDataAttribute(html, "data-exif-aperture", escape(image.getExifAperture()));
        appendDataAttribute(html, "data-exif-iso",
                image.getExifIso() == null ? null : image.getExifIso().toString());
        appendDataAttribute(html, "data-exif-focal", escape(image.getExifFocalLength()));
        appendDataAttribute(html, "data-blurhash", escape(image.getBlurhash()));
        html.append(">\n");

        // ---- the grid image itself
        html.append("<img src=\"").append(urls.original(image)).append('"');
        String srcset = srcset(image, urls);
        if (srcset != null) {
            // sizes = the figure's flex-basis (--ar * row height): what the
            // grid asks the row to give this image before stretch.
            html.append(" srcset=\"").append(srcset)
                    .append("\" sizes=\"(max-width: 640px) ")
                    .append(Math.round(aspectRatio * MOBILE_ROW_HEIGHT))
                    .append("px, ").append(Math.round(aspectRatio * rowHeight))
                    .append("px\"");
        }
        if (knownDimensions) {
            html.append(" width=\"").append(image.getWidth())
                    .append("\" height=\"").append(image.getHeight()).append('"');
        }
        html.append(" alt=\"")
                .append(escape(firstNonBlank(image.getAltText(), image.getName())))
                .append('"');
        html.append(" loading=\"lazy\" decoding=\"async\"");
        if (StringUtils.isNotBlank(image.getBlurhash())) {
            html.append(" data-blurhash=\"").append(escape(image.getBlurhash())).append('"');
            String averageColor = image.getAverageColor();
            if (averageColor != null) {
                // JS-free placeholder while the pixels arrive, exactly as the
                // [image] shortcode does it.
                html.append(" style=\"background-color:").append(averageColor).append('"');
            }
        }
        html.append(">\n</a>\n");

        if (caption != null) {
            html.append("<figcaption>").append(escape(caption)).append("</figcaption>\n");
        }
        html.append("</figure>\n");
    }

    /**
     * The {@code srcset} value for the image through the given URL space, or
     * null when the image is not rendition-eligible or has no stored width --
     * the same gating (and, for {@link #PERMALINKS}, the same bytes) as
     * {@link MediaFileWrapper#getSrcset()}.
     */
    private static String srcset(MediaFileWrapper image, ImageUrls urls) {
        int originalWidth = image.getWidth();
        if (originalWidth <= 0 || !image.isRenditionEligible()) {
            return null;
        }
        StringBuilder srcset = new StringBuilder();
        for (int width : RenditionSupport.LADDER_WIDTHS) {
            if (width >= originalWidth) {
                break;
            }
            srcset.append(urls.rendition(image, width)).append(' ').append(width).append("w, ");
        }
        srcset.append(urls.original(image)).append(' ').append(originalWidth).append('w');
        return srcset.toString();
    }

    /**
     * Row heights the stylesheet has rules for. An author's {@code row="280"}
     * snaps to the nearest of these rather than being honoured exactly --
     * arbitrary pixel values cannot survive the sanitizer, and a handful of
     * densities is what the attribute was actually for.
     */
    static final int[] ROW_HEIGHT_LADDER = {160, 200, 260, 320, 400};

    /** Nearest ladder rung to {@code requested}, or the default when unset. */
    static int snapRowHeight(int requested) {
        if (requested <= 0) {
            return DEFAULT_ROW_HEIGHT;
        }
        int best = ROW_HEIGHT_LADDER[0];
        for (int rung : ROW_HEIGHT_LADDER) {
            if (Math.abs(rung - requested) < Math.abs(best - requested)) {
                best = rung;
            }
        }
        return best;
    }

    /**
     * The justified-grid stylesheet, generated from the very constants that
     * produce the classes it targets.
     *
     * <p>This exists because the rules used to be a hand-maintained copy in
     * the {@code showGalleryGridStyles} theme macro, kept in sync with the
     * emitter by hand. When the emitter moved from an inline custom property
     * to {@code ar-NNN} classes (the sanitizer cannot carry {@code --ar}),
     * the hand-written copy could just as easily have been missed -- nothing
     * would have failed, the pictures would just have been wrong.
     *
     * <p>Generating the table removes the possibility rather than testing for
     * it: a class the emitter can produce and this stylesheet has no rule for
     * cannot exist, because the same loop writes both.
     */
    public static String gridStyles() {
        return GRID_STYLES;
    }

    /** Deterministic, so built once rather than on every page render. */
    private static final String GRID_STYLES = buildGridStyles();

    private static String buildGridStyles() {
        StringBuilder css = new StringBuilder(2048);
        css.append(".jgrid { display: flex; flex-wrap: wrap; gap: .5rem; --row-h: ")
                .append(DEFAULT_ROW_HEIGHT).append("px; }\n");
        css.append(".jgrid figure {\n")
                .append("  margin: 0;\n")
                // pre-ladder uploads have no stored dimensions
                .append("  --ar: 1.5;\n")
                // --ar is width / height
                .append("  flex-grow: var(--ar);\n")
                .append("  flex-basis: calc(var(--ar) * var(--row-h));\n")
                // stops a panorama dominating a row
                .append("  min-width: 120px;\n")
                .append("}\n");

        // Row-height ladder: the shortcode's row attribute snaps to a rung.
        for (int rung : ROW_HEIGHT_LADDER) {
            css.append(".jgrid.jgrid-h").append(rung).append("{--row-h:").append(rung).append("px}");
        }
        css.append('\n');

        // Per-figure aspect ratios, as classes rather than an inline custom
        // property because OWASP's CSS schema rejects "--ar" outright.
        for (int hundredths = MIN_AR_CLASS; hundredths <= MAX_AR_CLASS;
                hundredths += AR_CLASS_STEP) {
            css.append(".jgrid figure.ar-").append(hundredths).append("{--ar:")
                    .append(trimTrailingZeros(hundredths / 100.0)).append('}');
        }
        css.append('\n');

        css.append(".jgrid figure a { display: block; }\n")
                .append(".jgrid figure img {\n")
                .append("  display: block; width: 100%; height: auto;\n")
                // reserves the box, zero CLS
                .append("  aspect-ratio: var(--ar);\n")
                .append("  object-fit: cover;\n")
                .append("}\n")
                .append(".jgrid figcaption { font-size: .85em; padding: .15rem 0; }\n")
                // keep the last row at natural height instead of stretching it
                .append(".jgrid::after { content: \"\"; flex-grow: 999999; }\n")
                .append("@media (max-width: 640px) { .jgrid { --row-h: ")
                .append(ROW_HEIGHT_LADDER[0]).append("px; } }\n");
        return css.toString();
    }

    /** {@code 1.50 -> "1.5"}, {@code 2.00 -> "2"} -- CSS wants neither trailing zero. */
    private static String trimTrailingZeros(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * The narrowest and widest ratios the grid has a rule for. A panorama or a
     * very tall portrait clamps to the end of the range rather than falling
     * back to the default: clamped packing is still roughly right, whereas the
     * default (1.5) would be wrong in the opposite direction.
     */
    static final int MIN_AR_CLASS = 40;
    static final int MAX_AR_CLASS = 260;

    /** Quantization step, in hundredths. Must match the macro's rule table. */
    static final int AR_CLASS_STEP = 5;

    /**
     * {@code 1.3405 -> "ar-135"}: the ratio in hundredths, rounded to the
     * nearest {@link #AR_CLASS_STEP} and clamped to the range the stylesheet
     * covers.
     */
    static String aspectRatioClass(double aspectRatio) {
        long hundredths = Math.round(aspectRatio * 100 / AR_CLASS_STEP) * AR_CLASS_STEP;
        long clamped = Math.max(MIN_AR_CLASS, Math.min(MAX_AR_CLASS, hundredths));
        return "ar-" + clamped;
    }

    private static void appendDataAttribute(StringBuilder html, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            html.append(' ').append(name).append("=\"").append(value).append('"');
        }
    }

    private static String escape(String value) {
        return value == null ? null : HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }

    /**
     * The first candidate that is neither null nor blank, or {@code ""} if
     * every candidate is -- never {@code null}, because the only caller
     * appends the result straight into an {@code alt="..."} attribute value,
     * and a helper whose whole job is producing an attribute value must never
     * hand back something that stringifies to the literal text {@code null}.
     * A blank string counts as absent at every link of the alt chain (stored
     * alt text, then filename): an author who clears the alt field leaves
     * {@code ""} behind, and emitting that verbatim would assert the image is
     * decorative, which is wrong for a photograph and would also hide it from
     * the "missing alt text" marker.
     */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return "";
    }
}
