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

/**
 * Renders one registered shortcode. Unlike
 * {@link org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin}s,
 * which an entry opts into by name, shortcode handlers are a platform
 * feature: the {@link ShortcodeExpander} applies every registered handler to
 * every entry unconditionally.
 */
public interface ShortcodeHandler {

    /**
     * The shortcode's tag name, matched case-insensitively against
     * {@code [name ...]} in entry text. Must be stable: authors write it into
     * their posts.
     */
    String getName();

    /**
     * How the entry editor offers this shortcode to an author. Required, so
     * that registering a handler and teaching the editor about it are one act
     * rather than two -- see {@link ShortcodeCard}.
     */
    ShortcodeCard getCard();

    /**
     * Renders the shortcode into the HTML that replaces it.
     *
     * @param attributes the shortcode's attributes, keys lower-cased, in
     *                   source order; never null
     * @param body       the text between {@code [name]} and {@code [/name]}
     *                   (already expanded, so shortcodes may nest), or null
     *                   when the shortcode was written self-closing
     * @param content    the entry or page being rendered, for weblog context
     * @return the replacement HTML, or null to leave the shortcode text in
     *         the output exactly as the author wrote it (the "I can't render
     *         this" signal -- it keeps the problem visible to the author
     *         instead of silently swallowing their markup)
     */
    String render(Map<String, String> attributes, String body, ShortcodeContext content);

    /**
     * {@link #render(Map, String, ShortcodeContext)}, reporting to
     * {@code reads} the owner of anything it looked up beyond
     * {@code content}'s own weblog. The expander always calls this form; a
     * handler that reads only its content's weblog keeps the default.
     */
    default String render(Map<String, String> attributes, String body, ShortcodeContext content,
                          ShortcodeReads reads) {
        return render(attributes, body, content);
    }
}
