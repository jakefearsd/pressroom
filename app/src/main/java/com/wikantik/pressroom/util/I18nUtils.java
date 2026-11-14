/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.util;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;


/**
 * A utility class for dealing with i18n.
 */
public final class I18nUtils {

    private I18nUtils() {
    }

    /**
     * The locale a {@code language[_COUNTRY[_variant]]} string names, or the
     * default locale for null or any other shape. StringUtils.split drops
     * empty parts and never yields a null one.
     */
    public static Locale toLocale(String locale) {
        if (locale == null) {
            return Locale.getDefault();
        }
        String[] parts = StringUtils.split(locale, "_");
        return switch (parts.length) {
            case 1 -> Locale.of(parts[0]);
            case 2 -> Locale.of(parts[0], parts[1]);
            case 3 -> Locale.of(parts[0], parts[1], parts[2]);
            default -> Locale.getDefault();
        };
    }
    
}
