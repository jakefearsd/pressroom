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
}
