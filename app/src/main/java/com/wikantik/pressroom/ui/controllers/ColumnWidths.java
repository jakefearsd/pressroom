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

/**
 * The widths of the varchar columns the shared rule classes hold a value to,
 * where the screens and the API both reach them: the rules live in
 * {@code ui.controllers} and may not import {@code ui.restapi}, whose
 * {@code ColumnLimits} delegates here for these. Sourced from
 * {@code bin/db/migrations/V002__baseline_schema.sql} and
 * {@code V024__media_alt_text.sql}; a migration that changes a width changes
 * it here, once.
 */
public final class ColumnWidths {

    private ColumnWidths() {
    }

    /** {@code weblogcategory.name}. */
    public static final int CATEGORY_NAME = 255;
    /** {@code weblogcategory.description}. */
    public static final int CATEGORY_DESCRIPTION = 255;
    /** {@code pressroom_mediafiledir.name}. */
    public static final int DIRECTORY_NAME = 255;
    /** {@code pressroom_mediafile.name}. */
    public static final int MEDIA_NAME = 255;
    /** {@code pressroom_weblogpage.slug}. */
    public static final int PAGE_SLUG = 255;
}
