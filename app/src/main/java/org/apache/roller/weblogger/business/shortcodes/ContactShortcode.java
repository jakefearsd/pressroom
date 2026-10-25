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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.util.I18nMessages;

/**
 * The built-in {@code [contact]} shortcode: a placeholder for a contact form.
 *
 * <p>Like {@code [map]} and {@code [video]}, the emitted markup is a
 * {@code <div>} carrying data attributes, never a {@code <form>}: the
 * sanitizer strips {@code <form>} from authored content by design (an
 * authored form is a phishing kit), so {@code #showAudienceAssets} builds the
 * real form client-side and posts it to the weblog named by
 * {@code data-weblog}.
 *
 * <p><b>{@code data-endpoint} is server-built, not client-guessed.</b> The
 * client-side script cannot reliably rebuild the app's context path (e.g.
 * {@code /roller}) on its own -- an earlier version tried to infer it by
 * scanning the page for a stylesheet link containing {@code /roller-ui/},
 * which breaks the moment the first stylesheet on the page is something else
 * (a weblog's own theme CSS, a webjar), silently posting to the wrong origin
 * and failing the form with no visible cause. {@link WebloggerRuntimeConfig}
 * already carries the context path the {@code InitFilter} published for this
 * exact reason -- it is what {@code $url.site} resolves to in every theme.
 *
 * <p><b>Not on the site space.</b> The contact endpoint refuses the site
 * space (there is no site inbox), so a slot there would build a form whose
 * every message is lost behind a generic failure. A site page gets a short,
 * visible notice instead: the author sees it in preview and no reader is
 * offered a form that cannot work. It depends on the content's own weblog
 * only, so the entry HTML cache rules hold.
 */
public class ContactShortcode implements ShortcodeHandler {

    @Override
    public String getName() {
        return "contact";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("contact", "shortcode.contact.label", "[contact]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {
        if (content == null || content.getWeblog() == null
                || content.getWeblog().getHandle() == null) {
            return null;
        }
        if (content.getWeblog().isSiteSpace()) {
            return "<div class=\"contact-form-unavailable\">"
                    + StringEscapeUtils.escapeHtml4(I18nMessages.getMessages(content.getWeblog().getLocale())
                            .getString("shortcode.contact.siteSpace"))
                    + "</div>";
        }
        String endpoint = StringUtils.defaultString(WebloggerRuntimeConfig.getRelativeContextURL())
                + "/roller-ui/rendering/contact.rol";
        return "<div class=\"contact-form-slot\" data-weblog=\""
                + StringEscapeUtils.escapeHtml4(content.getWeblog().getHandle())
                + "\" data-endpoint=\""
                + StringEscapeUtils.escapeHtml4(endpoint)
                + "\"></div>";
    }
}
