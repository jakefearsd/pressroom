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
package com.wikantik.pressroom.pojos;

import java.util.Locale;
import java.util.Set;

/**
 * First path segments a weblog page's slug may not use.
 *
 * <p>Single source of truth, shared by the request parser and the save-time
 * validator. {@code /<handle>/<slug>} now resolves an unrecognised segment to
 * a page, so a page slugged {@code entry} would both be unreachable itself and
 * shadow every permalink on the weblog. Two lists would drift; this one
 * cannot.
 */
public final class ReservedSlugs {

    /**
     * The contexts {@code WeblogPageRequest} parses, plus the servlet paths
     * that never reach it ({@code feed}, {@code search}, {@code resource},
     * {@code mediaresource}, {@code media}, {@code rsd}).
     */
    public static final Set<String> RESERVED = Set.of(
            "entry", "date", "category", "page", "tags",
            "feed", "search", "resource", "mediaresource", "media", "rsd");

    private ReservedSlugs() {
    }

    /** Case-insensitive; null and blank count as reserved (nothing to route). */
    public static boolean isReserved(String slug) {
        return slug == null || slug.isBlank()
                || RESERVED.contains(slug.trim().toLowerCase(Locale.ROOT));
    }
}
