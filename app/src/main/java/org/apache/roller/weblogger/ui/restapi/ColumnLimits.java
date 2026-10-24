package org.apache.roller.weblogger.ui.restapi;

/**
 * Column-width limits for the varchar columns this API's patchable string
 * fields land in, sourced from {@code bin/db/migrations/V002__baseline_schema
 * .sql} ({@code roller_user}/{@code weblog} table definitions). A value that
 * overflows one of these reaches JPA's INSERT/UPDATE and Postgres answers
 * "value too long for type character varying(N)", wrapped in a bare
 * {@code WebloggerException} -- an unguarded 500. Centralized here rather
 * than duplicated per controller, so a future migration that changes a
 * column's width has exactly one place to update and every {@code *Api}
 * controller checks the same numbers.
 *
 * <p>Wired into every controller in {@code ui.restapi.v1} that accepts a
 * patchable string field (whole-branch review round, Must Fix 1) --
 * originally only {@code AdminApi}/{@code WeblogsApi} (review round 1,
 * Important 2).
 */
public final class ColumnLimits {

    private ColumnLimits() {
    }

    // roller_user (V002__baseline_schema.sql)
    public static final int USERNAME = 255;
    public static final int SCREEN_NAME = 255;
    public static final int FULL_NAME = 255;
    public static final int USER_EMAIL_ADDRESS = 255;

    // weblog (V002__baseline_schema.sql)
    public static final int WEBLOG_NAME = 255;
    public static final int TAGLINE = 255;
    public static final int WEBLOG_EMAIL_ADDRESS = 255;
    public static final int LOCALE = 20;
    public static final int TIME_ZONE = 50;

    // weblogentry (V002__baseline_schema.sql, V006__media_metadata_and_entry_seo.sql)
    public static final int ENTRY_TITLE = 255;
    public static final int META_TITLE = 255;
    public static final int SEARCH_DESCRIPTION = 255;
    public static final int CANONICAL_URL = 255;
    public static final int FEATURED_IMAGE_ID = 48;
    public static final int OG_IMAGE_ID = 48;
    // V008 travel structured data: event_location varchar(255)
    public static final int EVENT_LOCATION = 255;

    // weblogentry_revision (V037__entry_revision_note.sql)
    public static final int REVISION_NOTE = 255;

    // roller_weblogentrytag (V002__baseline_schema.sql)
    public static final int TAG_NAME = 255;

    // roller_mediafile / roller_mediafiledir (V002__baseline_schema.sql,
    // V024__media_alt_text.sql)
    public static final int MEDIA_NAME = 255;
    public static final int MEDIA_ALT_TEXT = 255;
    public static final int MEDIA_CONTENT_TYPE = 50;
    public static final int DIRECTORY_NAME = 255;
    public static final int DIRECTORY_DESCRIPTION = 255;

    // weblogcategory (V002__baseline_schema.sql)
    public static final int CATEGORY_NAME = 255;
    public static final int CATEGORY_DESCRIPTION = 255;

    // roller_weblogpage (V014__weblog_pages.sql)
    public static final int PAGE_SLUG = 255;

    // roller_weblog_redirect (V028__weblog_redirects.sql)
    public static final int REDIRECT_PATH = 255;
    public static final int PAGE_TITLE = 255;

    // roller_api_token (V026__api_tokens.sql)
    public static final int TOKEN_LABEL = 255;
    public static final int TOKEN_WEBLOG = 255;

    /**
     * Throws a 400 {@link ApiException} naming {@code field} when
     * {@code value} exceeds {@code max} characters. A {@code null} value is
     * never rejected here -- whether null is itself acceptable (PATCH
     * "leave unchanged" vs. POST "required") is each caller's own business,
     * decided before or after this check as appropriate.
     */
    public static void requireMaxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw ApiException.invalidField(field,
                    "'" + field + "' must be " + max + " characters or fewer.");
        }
    }
}
