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

package com.wikantik.pressroom.ui.view;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * How a screen writes a figure: a count in the reader's locale, and the dash
 * that stands for "no figure". Shared by the traffic screens and the entry
 * list's views column, so a view model needs no controller type for it.
 */
public final class Figures {

    /** An em dash: "no figure", never NaN or an infinite change. */
    public static final String DASH = "—";

    private Figures() {
    }

    /** A count in the reader's locale. */
    public static String number(long value, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(value);
    }
}
