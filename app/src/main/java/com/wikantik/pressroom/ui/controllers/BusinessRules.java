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
package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.validator.routines.EmailValidator;
import com.wikantik.pressroom.business.BookingLink;

/**
 * The field rules for a business record and a blog's place fields, shared by
 * the Businesses admin screen and the blog settings form so both refuse the
 * same inputs. Everything here ends up in schema.org JSON-LD or an anchor on a
 * public page, so a value that fails is refused rather than cleaned up.
 */
public final class BusinessRules {

    static final Pattern TELEPHONE = Pattern.compile(
            "^(?=.{3,32}$)[0-9+()\\-. ]{3,}(?: *(?:extension|ext\\.?|x) *\\d{1,6})?$",
            Pattern.CASE_INSENSITIVE);

    /** Most sameAs profile links a business may carry. */
    public static final int MAX_SAME_AS = 10;

    private BusinessRules() {
    }

    /**
     * True for an absolute http or https URL. Delegates to
     * {@link BookingLink#isHttpUrl}, so the forms accept exactly the links
     * the card and the [cta] shortcode will use.
     */
    public static boolean isHttpUrl(String s) {
        return BookingLink.isHttpUrl(s);
    }

    public static boolean isEmail(String s) {
        return s != null && EmailValidator.getInstance().isValid(s);
    }

    /**
     * Digits, plus, parentheses, hyphen, dot and space, then optionally an
     * extension ("ext", "ext.", "extension" or "x" and 1 to 6 digits); 3 to
     * 32 characters in all, the column's width.
     */
    public static boolean isTelephone(String s) {
        return s != null && TELEPHONE.matcher(s).matches();
    }

    /** The non-blank lines of a newline-separated list, each trimmed. */
    public static List<String> sameAsLines(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null) {
            return lines;
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
