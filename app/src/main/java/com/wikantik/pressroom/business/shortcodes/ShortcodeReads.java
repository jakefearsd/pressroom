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
 * Told which weblog's data a shortcode expansion read, so whoever keeps the
 * expanded HTML knows what it depends on.
 *
 * <p>The entry-HTML cache ({@code EntryHtmlCache}) holds an entry's render
 * until its own weblog's {@code lastModified} moves. That is only sound for
 * data the entry's weblog owns: a change to another weblog's media moves
 * that weblog's stamp, never this one's. A handler that resolves anything by
 * a global id -- today only {@code [image id=..]} -- reports the owner here.
 * One that reads only {@link ShortcodeContext#getWeblog()}'s data (gallery,
 * map, contact, subscribe, cta) has nothing to report, except a lookup that
 * failed: its degraded output (the shortcode left as written) must not be
 * kept as though it were the render.
 */
@FunctionalInterface
public interface ShortcodeReads {

    /** For callers that keep nothing. */
    ShortcodeReads IGNORED = weblog -> { };

    /**
     * The expansion read data owned by {@code weblog}. Null when the owner
     * could not be told, a lookup having failed: a cache must not keep that
     * render.
     */
    void readFrom(Weblog weblog);
}
