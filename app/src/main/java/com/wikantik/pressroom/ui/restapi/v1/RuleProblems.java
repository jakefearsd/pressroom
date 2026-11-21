package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.WeblogCreationRules;
import com.wikantik.pressroom.ui.controllers.WeblogSettingsRules;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * The problem codes of the rules a weblog create and a settings PATCH share
 * with the admin UI's forms (WeblogCreationRules, WeblogSettingsRules): each
 * rule's reason is one meaning, and the switch over it is exhaustive, so a
 * new reason does not compile until it has a code (spec
 * 2026-11-20-api-contract-design, Task 3). A code's English with the
 * violation's args is the violation's {@code detail}, word for word.
 */
final class RuleProblems {

    private RuleProblems() {
    }

    static ProblemCode code(WeblogCreationRules.Reason reason) {
        return switch (reason) {
            case CREATION_DISABLED -> ProblemCode.WEBLOG_CREATION_DISABLED;
            case ONE_BLOG_LIMIT -> ProblemCode.WEBLOG_ONE_BLOG_LIMIT;
            case REQUIRED -> ProblemCode.FIELD_REQUIRED;
            case TOO_LONG -> ProblemCode.FIELD_TOO_LONG;
            case THEME_NOT_OFFERED -> ProblemCode.THEME_NOT_OFFERED;
            case LOCALE_INVALID -> ProblemCode.LOCALE_INVALID;
            case TIME_ZONE_INVALID -> ProblemCode.TIME_ZONE_INVALID;
            case HANDLE_INVALID -> ProblemCode.HANDLE_INVALID;
            case HANDLE_RESERVED -> ProblemCode.HANDLE_RESERVED;
            case HANDLE_IN_USE -> ProblemCode.HANDLE_IN_USE;
            case HANDLE_HELD_BY_SITE_PAGE -> ProblemCode.HANDLE_HELD_BY_SITE_PAGE;
        };
    }

    static ProblemCode code(WeblogSettingsRules.Reason reason) {
        return switch (reason) {
            case ENTRY_DISPLAY_COUNT -> ProblemCode.ENTRY_DISPLAY_COUNT_OUT_OF_RANGE;
            case LOCALE_INVALID -> ProblemCode.LOCALE_INVALID;
            case TIME_ZONE_INVALID -> ProblemCode.TIME_ZONE_INVALID;
            case ENTRY_ORDER -> ProblemCode.ENTRY_ORDER_INVALID;
            case TOO_LONG -> ProblemCode.FIELD_TOO_LONG;
            case NOT_UUID -> ProblemCode.FIELD_NOT_UUID;
            case BUSINESS_UNKNOWN -> ProblemCode.BUSINESS_UNKNOWN;
            case FRONT_PAGE -> ProblemCode.FRONT_PAGE_INVALID;
            case PLACE_TYPE -> ProblemCode.PLACE_TYPE_INVALID;
            case PLACE_LOCALITY_REQUIRED -> ProblemCode.PLACE_LOCALITY_REQUIRED;
            case PLACE_COUNTRY -> ProblemCode.PLACE_COUNTRY_INVALID;
            case PLACE_LAT -> ProblemCode.PLACE_LAT_INVALID;
            case PLACE_LNG -> ProblemCode.PLACE_LNG_INVALID;
            case BOOKING_URL -> ProblemCode.BOOKING_URL_INVALID;
            case CUSTOM_DOMAIN_MALFORMED -> ProblemCode.CUSTOM_DOMAIN_MALFORMED;
            case CUSTOM_DOMAIN_SITE_HOST -> ProblemCode.CUSTOM_DOMAIN_SITE_HOST;
            case CUSTOM_DOMAIN_TAKEN -> ProblemCode.CUSTOM_DOMAIN_IN_USE;
            case CUSTOM_DOMAIN_UNCHECKABLE -> ProblemCode.CUSTOM_DOMAIN_UNCHECKABLE;
        };
    }

    /**
     * Refuses {@code violations}, when there are any, as {@link #refuse(List)}
     * refuses broken rules: a conflict alone is its 409, anything else one
     * 400 naming every violation.
     */
    static void refuseViolations(List<RuleViolation> violations) {
        refuse(violations.stream().map(v -> new Broken(v.field(), v.code(), v.codeArgs(),
                v.kind() == RuleViolation.Kind.CONFLICT)).toList());
    }

    /** One broken rule, keyed: its field (as the API names it), code and args. */
    record Broken(String field, ProblemCode code, List<String> args, boolean conflict) {
        Broken {
            args = List.copyOf(args);
        }

        private String[] arguments() {
            return args.toArray(String[]::new);
        }
    }

    /**
     * Refuses {@code broken}, when it holds any rule: a conflict alone is a
     * 409 on its field (the rules give at most one: a taken handle or custom
     * domain); otherwise one 400 naming every broken rule, each with its own
     * code, its detail their messages joined by one space. Several conflicts
     * and nothing else cannot occur; were they to, the first is answered, so
     * the status stays the conflict's.
     */
    static void refuse(List<Broken> broken) {
        if (!broken.isEmpty() && broken.stream().allMatch(Broken::conflict)) {
            Broken first = broken.get(0);
            throw ApiException.problemOnField(first.field(), first.code(), first.arguments());
        }
        FieldErrors errors = new FieldErrors();
        broken.forEach(b -> errors.add(b.field(), b.code(), b.arguments()));
        errors.throwIfAny();
    }
}
