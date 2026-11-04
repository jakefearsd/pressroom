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
package com.wikantik.pressroom.ui.controllers.util;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The locale and time-zone lists are built once and handed to every settings
 * page and every check of a submitted locale or zone; a caller must not be
 * able to change them for the whole server.
 */
class UIUtilsTest {

    @Test
    void theSharedLocaleListCannotBeChangedByACaller() {
        assertFalse(UIUtils.getLocales().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> UIUtils.getLocales().set(0, Locale.ROOT));
        assertThrows(UnsupportedOperationException.class, () -> UIUtils.getLocales().sort(null));
    }

    @Test
    void theSharedTimeZoneListCannotBeChangedByACaller() {
        assertFalse(UIUtils.getTimeZones().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> UIUtils.getTimeZones().set(0, "Mars/Olympus"));
    }
}
