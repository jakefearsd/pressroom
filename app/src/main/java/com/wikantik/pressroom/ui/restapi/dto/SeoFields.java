package com.wikantik.pressroom.ui.restapi.dto;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;

/**
 * The SEO card's field rules, shared by the entry and page writes: one rule
 * per field, so an entry and a page can never accept different values for the
 * same card. The columns have the same sizes on both tables
 * (V002/V014: varchar(255), og_image_id varchar(48)). Each method answers the
 * value to store or throws the 400 naming its field; a blank value stores
 * null, since removing a value is {@code clear}'s job and a blank is no value.
 * Whether {@code ogImageId} names a usable image is the controller's check
 * ({@code BaseApiController.checkImageId}): it needs a lookup.
 */
public final class SeoFields {

    private SeoFields() {
    }

    public static String metaTitle(String raw) {
        String value = StringUtils.trimToNull(raw);
        ColumnLimits.requireMaxLength("metaTitle", value, ColumnLimits.META_TITLE);
        return value;
    }

    public static String searchDescription(String raw) {
        String value = StringUtils.trimToNull(raw);
        ColumnLimits.requireMaxLength("searchDescription", value, ColumnLimits.SEARCH_DESCRIPTION);
        return value;
    }

    /** The validator the entry and page editors use ({@link EntryFieldRules#isValidCanonicalUrl}). */
    public static String canonicalUrl(String raw) {
        String value = StringUtils.trimToNull(raw);
        ColumnLimits.requireMaxLength("canonicalUrl", value, ColumnLimits.CANONICAL_URL);
        if (value != null && !EntryFieldRules.isValidCanonicalUrl(value)) {
            throw ApiException.invalidField("canonicalUrl", "canonicalUrl must be an absolute http or https URL.");
        }
        return value;
    }

    public static String ogImageId(String raw) {
        ColumnLimits.requireMaxLength("ogImageId", raw, ColumnLimits.OG_IMAGE_ID);
        return raw;
    }
}
