/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.pojos;

import java.util.Optional;

/**
 * How much of a wide screen a blog's pages use. Stored as the enum name in
 * {@code weblog.page_width}; the themes read it through
 * {@code WeblogWrapper.isWideLayout} and widen their columns under a body class.
 */
public enum PageWidth {
    /** The themes' reading widths: how every blog looked before the setting existed. */
    STANDARD,
    /** A wider page and a longer line on large screens; phones are unchanged. */
    WIDE;

    /** Reads a stored value leniently: null, blank or unknown never fails a render, and reads as STANDARD. */
    public static PageWidth of(String stored) {
        return parse(stored).orElse(STANDARD);
    }

    /** Reads submitted input strictly: case-insensitive STANDARD or WIDE, empty for anything else. */
    public static Optional<PageWidth> parse(String input) {
        return StoredEnums.parse(values(), input);
    }
}
