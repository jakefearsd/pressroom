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

import com.wikantik.pressroom.pojos.Weblog;

/**
 * What a {@link ShortcodeHandler} needs to know about the thing being
 * rendered. Deliberately three methods: a survey of the five built-in
 * handlers found they use their subject for exactly this much -- the weblog
 * (media lookups, UTM source), a slug (UTM campaign), and the unexpanded
 * source text ({@link MapPins} and {@link FaqBlocks} re-parse it to build the
 * JSON-LD twin of what the shortcode renders).
 *
 * <p>Both {@link com.wikantik.pressroom.pojos.WeblogEntry} and
 * {@link com.wikantik.pressroom.pojos.WeblogPage} implement this, which
 * is what lets every shortcode work on a page without the handlers knowing
 * pages exist.
 *
 * <p>Implementations may return null from any method; handlers already treat
 * a missing subject as "render what you can" rather than failing.
 */
public interface ShortcodeContext {

    /** The weblog this content belongs to, or null when unavailable. */
    Weblog getWeblog();

    /** An entry's anchor or a page's slug; null when unavailable. */
    String getSlug();

    /** The source text before shortcode expansion; null when unavailable. */
    String getRawText();
}
