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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;

/**
 * The built-in {@code [video url="..." caption="..."]} shortcode: a
 * click-to-play placeholder for an allowlisted video provider.
 *
 * <p><strong>This parses URLs; it never fetches them.</strong> Real oEmbed
 * would discover a provider endpoint by requesting the author's URL
 * server-side -- an SSRF surface pointing at this deployment's own actuator
 * port and any cloud metadata endpoint -- and the HTML it returned would be
 * discarded anyway, because {@code HTMLSanitizer} strips {@code <iframe>}.
 * Matching known URL shapes costs one regex per provider and has neither
 * problem.
 *
 * <p>Like {@code [map]}, the emitted markup is a {@code <div>} carrying data
 * attributes, never a frame: the sanitizer would delete a frame, so the
 * {@code #showEmbedAssets} macro injects one client-side when a reader
 * clicks. No frame, no cookies, and no script from the provider load before
 * that click -- but the thumbnail {@code <img>} above (YouTube's
 * {@code i.ytimg.com}) does load at render time, which sends the reader's IP
 * and referer to the provider's CDN like any other embedded image.
 */
public class VideoShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoShortcode.class);

    /** YouTube ids are [A-Za-z0-9_-]{11}; Vimeo ids are digits. */
    private record Provider(String name, Pattern urlPattern, Pattern idPattern,
                            String thumbnailFormat) {
    }

    // The watch URL's v= may follow any number of other parameters. "(?s:.*&)?"
    // skips them in one backtracking scan; the repeated group "(?:[^&]*&)*" it
    // replaces matched the same URLs but recursed once per parameter, which
    // overflowed the stack on a few thousand.
    private static final List<Provider> PROVIDERS = List.of(
            new Provider("youtube",
                    Pattern.compile("^https?://(?:www\\.|m\\.)?youtube\\.com/watch\\?"
                            + "(?s:.*&)?v=([^&#]+)"),
                    Pattern.compile("^[A-Za-z0-9_-]{11}$"),
                    "https://i.ytimg.com/vi/%s/hqdefault.jpg"),
            new Provider("youtube",
                    Pattern.compile("^https?://youtu\\.be/([^?&#/]+)"),
                    Pattern.compile("^[A-Za-z0-9_-]{11}$"),
                    "https://i.ytimg.com/vi/%s/hqdefault.jpg"),
            new Provider("vimeo",
                    Pattern.compile("^https?://(?:www\\.|player\\.)?vimeo\\.com/"
                            + "(?:video/)?(\\d+)"),
                    Pattern.compile("^\\d+$"),
                    null));

    @Override
    public String getName() {
        return "video";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("video", "shortcode.video.label",
                "[video url=\"https://youtu.be/dQw4w9WgXcQ\" caption=\"What this shows\"]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {

        String url = StringUtils.trimToNull(attributes.get("url"));
        if (url == null) {
            log.debug("[video] shortcode without a url; leaving it as written");
            return null;
        }

        for (Provider provider : PROVIDERS) {
            Matcher matcher = provider.urlPattern().matcher(url);
            if (matcher.find()) {
                String id = matcher.group(1);
                if (!provider.idPattern().matcher(id).matches()) {
                    // Refused rather than escaped: a value this shape is not an id,
                    // and it would travel into both an attribute and a thumbnail URL.
                    log.debug("[video] id is not valid for {}; leaving it as written",
                            provider.name());
                    return null;
                }
                return markup(provider, id, attributes.get("caption"));
            }
        }

        log.debug("[video] url is not an allowlisted provider; leaving it as written");
        return null;
    }

    @SuppressFBWarnings(
            value = {"POTENTIAL_XML_INJECTION", "FORMAT_STRING_MANIPULATION"},
            justification = "The id has matched its provider's id pattern (eleven [A-Za-z0-9_-] or digits) "
                    + "before it gets here and the caption goes through escape(); the format string is a "
                    + "constant in PROVIDERS, never input. The expanded entry also passes through "
                    + "HTMLSanitizer whenever weblogAdminsUntrusted is on (the shipped default).")
    private static String markup(Provider provider, String id, String caption) {
        StringBuilder html = new StringBuilder(320);
        html.append("<figure class=\"video-figure\">");
        html.append("<div class=\"video-embed\" data-provider=\"")
                .append(provider.name())
                .append("\" data-video-id=\"").append(id).append("\">");

        if (provider.thumbnailFormat() != null) {
            html.append("<img src=\"")
                    .append(escape(String.format(provider.thumbnailFormat(), id)))
                    .append("\" alt=\"\" loading=\"lazy\" decoding=\"async\">");
        }

        html.append("</div>");

        String trimmedCaption = StringUtils.trimToNull(caption);
        if (trimmedCaption != null) {
            html.append("<figcaption>").append(escape(trimmedCaption)).append("</figcaption>");
        }
        html.append("</figure>");
        return html.toString();
    }

    private static String escape(String value) {
        return StringEscapeUtils.escapeHtml4(value);
    }
}
