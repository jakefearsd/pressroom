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

import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The built-in {@code [image id=<mediaFileId> caption="..." alt="..."]}
 * shortcode: renders an uploaded image as a responsive
 * {@code <figure><picture>} block whose {@code srcset} climbs the
 * {@link RenditionSupport} width ladder ({@code ?w=} URLs, WebP negotiated
 * by the media servlet via the Accept header on those same URLs).
 *
 * <p>A body ({@code [image id=x]caption markup[/image]}) is used as the
 * caption verbatim -- it is entry HTML like any other and flows through the
 * downstream sanitizer -- while a {@code caption} attribute is escaped.
 * When the media file cannot be resolved -- unknown, not an image, another
 * weblog's, or in a private directory -- the handler returns null so the
 * expander leaves the shortcode text visible to the author.
 */
public class ImageShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageShortcode.class);

    /** The browser hint for how wide the image renders; themes show entry images full-column. */
    private static final String SIZES = "100vw";

    private final Weblogger weblogger;

    /**
     * @param weblogger the tier whose media manager resolves the id and whose
     *                  url strategy the rendered file is wrapped with; never
     *                  dereferenced at construction, so a lazy proxy is fine
     */
    public ImageShortcode(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public String getName() {
        return "image";
    }

    /**
     * The chooser, not a snippet: an [image] is addressed by media-file id,
     * which is a UUID no author is going to type.
     */
    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.mediaChooser("image", "shortcode.image.label");
    }

    @Override
    public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
        return render(attributes, body, content, ShortcodeReads.IGNORED);
    }

    /**
     * Reports the media file's weblog to {@code reads}: the id is global, so
     * it may name another weblog's file, whose changes move that weblog's
     * {@code lastModified} and never this entry's.
     */
    @Override
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "src and srcset are built by the media wrapper from ids and widths, alt and "
                    + "the caption attribute go through escape(), and a body caption is the author's own "
                    + "markup; the expanded entry then passes through HTMLSanitizer, the one content "
                    + "boundary, whenever weblogAdminsUntrusted is on (the shipped default; with it off, "
                    + "authors are trusted with raw HTML anyway).")
    public String render(Map<String, String> attributes, String body, ShortcodeContext content,
                         ShortcodeReads reads) {
        String id = attributes.get("id");
        if (StringUtils.isBlank(id)) {
            log.debug("[image] shortcode without an id attribute; leaving it as written");
            return null;
        }

        Weblog weblog = content == null ? null : content.getWeblog();
        if (weblog == null) {
            log.debug("[image] shortcode outside any weblog; leaving it as written");
            return null;
        }

        MediaFileWrapper media;
        try {
            MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(id);
            Weblog owner = mediaFile == null ? null : ownerOf(mediaFile);
            if (mediaFile != null) {
                // Reported before the checks below, refusal included: the
                // render cache keeps nothing that read another weblog.
                reads.readFrom(owner);
            }
            if (mediaFile == null || !mediaFile.isImageFile()) {
                log.debug("[image] shortcode id {} is not an image media file", id);
                return null;
            }
            // The id is global, so it can name any weblog's file. Only this
            // weblog's images outside every private directory render: the
            // same rule the API applies to featuredImageId, and the one
            // [gallery] applies to a private directory.
            if (owner == null || !weblog.getId().equals(owner.getId()) || isPrivate(mediaFile)) {
                log.debug("[image] shortcode id {} is not a public image of weblog {}", id, weblog.getHandle());
                return null;
            }
            media = MediaFileWrapper.wrap(mediaFile, weblogger.getUrlStrategy(), weblogger);
        } catch (Exception e) {
            log.warn("[image] shortcode could not resolve media file {}", id, e);
            reads.readFrom(null);
            return null;
        }

        // srcset: every ladder rung narrower than the original, then the
        // original itself as the largest candidate. Null -- a plain <img> --
        // for pre-ladder uploads without width metadata AND for formats the
        // ladder does not cover (gif/bmp), where every ?w= URL silently
        // serves the full-size original. Shared with #showResponsiveImage
        // via the wrapper so both emitters stay aligned.
        String srcset = media.getSrcset();

        StringBuilder html = new StringBuilder(256);
        html.append("<figure class=\"shortcode-image\">\n<picture>\n");
        if (srcset != null) {
            // WebP is negotiated on the same URLs via the Accept header, so
            // this source just gives webp-capable browsers an early pick.
            html.append("<source type=\"image/webp\" srcset=\"").append(srcset)
                    .append("\" sizes=\"").append(SIZES).append("\">\n");
        }

        html.append("<img src=\"").append(media.getPermalink()).append('"');
        if (srcset != null) {
            html.append(" srcset=\"").append(srcset)
                    .append("\" sizes=\"").append(SIZES).append('"');
        }
        if (media.getWidth() > 0 && media.getHeight() > 0) {
            html.append(" width=\"").append(media.getWidth())
                    .append("\" height=\"").append(media.getHeight()).append('"');
        }
        // An explicit alt="" attribute is an author's deliberate assertion
        // that the image is decorative -- the standard way to say so -- and
        // must be honoured verbatim, so this link of the chain is gated on
        // containsKey (attribute present at all), not blankness. The stored
        // altText link below it is gated on blankness instead: an author who
        // clears the edit field leaves "" behind and has no way to express
        // "decorative" through that field, so a blank altText still falls
        // through to the filename rather than being taken as an assertion.
        String alt = attributes.containsKey("alt")
                ? attributes.get("alt")
                : firstNonBlank(media.getAltText(), media.getName());
        html.append(" alt=\"").append(escape(StringUtils.defaultString(alt))).append('"');
        html.append(" loading=\"lazy\" decoding=\"async\"");
        if (StringUtils.isNotBlank(media.getBlurhash())) {
            html.append(" data-blurhash=\"").append(escape(media.getBlurhash())).append('"');
            // JS-free blur-up: the BlurHash average color as an inline
            // background shows through until the real pixels arrive (the
            // width/height attributes above reserve the box).
            String averageColor = media.getAverageColor();
            if (averageColor != null) {
                html.append(" style=\"background-color:").append(averageColor).append('"');
            }
        }
        html.append(">\n</picture>\n");

        String caption = StringUtils.isNotBlank(body)
                ? body.trim()
                : escape(StringUtils.trimToNull(attributes.get("caption")));
        if (caption != null) {
            html.append("<figcaption>").append(caption).append("</figcaption>\n");
        }

        html.append("</figure>");
        return html.toString();
    }

    /**
     * The weblog that owns a media file: its directory's, falling back to
     * the file's own field (the shape {@code WeblogOwnership.mediaFile}
     * uses; business code cannot import it, and it swallows exceptions).
     */
    private static Weblog ownerOf(MediaFile mediaFile) {
        MediaFileDirectory directory = mediaFile.getDirectory();
        return directory != null && directory.getWeblog() != null ? directory.getWeblog() : mediaFile.getWeblog();
    }

    private static boolean isPrivate(MediaFile mediaFile) {
        return mediaFile.getDirectory() != null && mediaFile.getDirectory().isPrivate();
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
