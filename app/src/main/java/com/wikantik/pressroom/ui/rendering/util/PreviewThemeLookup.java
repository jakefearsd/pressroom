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

package com.wikantik.pressroom.ui.rendering.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.themes.ThemeNotFoundException;
import com.wikantik.pressroom.pojos.Theme;

/**
 * Resolves the theme named by a preview url's {@code theme} parameter.
 *
 * <p>{@link WeblogPreviewRequest} and {@link WeblogPreviewResourceRequest} both
 * need this and cannot share a base class -- one extends the page request, the
 * other the resource request. They carried byte-for-byte identical copies
 * instead, including the distinction below between an unknown theme and a
 * broken lookup, which is exactly the kind of thing that survives in one copy
 * and not the other.
 */
final class PreviewThemeLookup {

    private static final Logger log = LoggerFactory.getLogger(PreviewThemeLookup.class);

    private PreviewThemeLookup() {}

    /**
     * The named theme, or null when there is not one.
     *
     * <p>An unknown name is routine -- somebody typing a theme into a preview
     * url -- and is not logged. A lookup that actually fails is, because that
     * is a broken installation rather than a broken guess. Both answer null,
     * and every caller treats that as "no theme to preview".
     */
    static Theme byName(String themeName, ThemeManager themeMgr) {

        if (themeName == null) {
            return null;
        }

        try {
            return themeMgr.getTheme(themeName);

        } catch (ThemeNotFoundException ignored) {
            return null;
        } catch (WebloggerException re) {
            log.error("Error looking up theme {}", themeName, re);
            return null;
        }
    }

}
