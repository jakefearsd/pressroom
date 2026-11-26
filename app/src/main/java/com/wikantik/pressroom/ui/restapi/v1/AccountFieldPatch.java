package com.wikantik.pressroom.ui.restapi.v1;

import java.util.function.Consumer;
import java.util.function.Function;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.RuleProblems;

/** How an account PATCH applies one optional text field, for {@code AdminApi} and {@code MeProfileApi} alike. */
final class AccountFieldPatch {

    private AccountFieldPatch() {
    }

    /**
     * One optional text field of an account patch: absent leaves it alone; present is trimmed, judged by its
     * rule and set only when it passes (the user is a managed entity), its problem recorded either way.
     */
    static void apply(FieldErrors errors, String value, Function<String, RuleViolation> rule,
            Consumer<String> setter) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        RuleViolation problem = rule.apply(trimmed);
        if (problem == null) {
            setter.accept(trimmed);
        }
        RuleProblems.add(errors, problem);
    }
}
