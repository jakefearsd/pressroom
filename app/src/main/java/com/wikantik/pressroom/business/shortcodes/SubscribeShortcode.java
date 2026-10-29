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
package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;

/**
 * The built-in {@code [subscribe]} shortcode: a placeholder for a newsletter
 * subscribe form.
 *
 * <p>Like {@code [contact]}, the emitted markup is a {@code <div>} carrying
 * data attributes, never a {@code <form>}: the sanitizer strips
 * {@code <form>} from authored content by design (an authored form is a
 * phishing kit), so {@code #showAudienceAssets} builds the real form
 * client-side and posts it to the list named by {@code data-list-uuid}, at
 * the endpoint named by {@code data-endpoint}.
 *
 * <p><b>{@code data-endpoint} is server-built, not client-guessed</b> --
 * exactly the same reasoning as {@link ContactShortcode}: the client-side
 * script cannot reliably rebuild the app's context path (e.g. {@code
 * /roller}) on its own, so an absolute-root {@code /newsletter/subscribe}
 * 404s under any non-root context path. {@link WebloggerRuntimeConfig}
 * carries the context path the {@code InitFilter} published for this exact
 * reason.
 *
 * <p>Renders nothing (the author's text stays visible) when the weblog has
 * no newsletter list configured, or when the stored UUID does not have the
 * expected shape -- a malformed stored value should never reach the
 * client-side fetch as-is.
 */
public class SubscribeShortcode implements ShortcodeHandler {

    /** A UUID's textual shape: hex digits and hyphens, 36 characters. */
    private static final Pattern UUID_SHAPE = Pattern.compile("^[0-9a-fA-F-]{36}$");

    @Override
    public String getName() {
        return "subscribe";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("subscribe", "shortcode.subscribe.label", "[subscribe]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {
        if (content == null || content.getWeblog() == null) {
            return null;
        }
        String uuid = content.getWeblog().getNewsletterListUuid();
        if (uuid == null || !UUID_SHAPE.matcher(uuid).matches()) {
            return null;
        }
        String endpoint = StringUtils.defaultString(WebloggerRuntimeConfig.getRelativeContextURL())
                + "/newsletter/subscribe";
        return "<div class=\"subscribe-form-slot\" data-list-uuid=\""
                + StringEscapeUtils.escapeHtml4(uuid)
                + "\" data-endpoint=\""
                + StringEscapeUtils.escapeHtml4(endpoint)
                + "\"></div>";
    }
}
