package com.wikantik.pressroom.ui.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem detail. Null members are omitted, so a plain error
 * carries no empty "errors": [] and no null detail. {@code
 * currentContentHash} is an extension member, set only on a 409
 * entry-changed or page-changed: the hash of the text now stored, which the
 * caller's {@code expectedContentHash} did not match.
 *
 * <p>{@code code} is the extension member a client branches on (a {@link
 * ProblemCode}), never null on a body this API builds. It is the last member
 * of the problem and of each field error, so every earlier member
 * serialises exactly as it did before codes existed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors,
        String currentContentHash,
        String code) {

    /**
     * One field's problem. {@code field} is null when the problem names no
     * field the caller sent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message, String code) {

        /**
         * An unkeyed 400 field error: a literal message, the generic {@code
         * request.invalid} code. Package-private: no route builds one (spec
         * 2026-11-20-api-contract-design, Task 3).
         */
        FieldError(String field, String message) {
            this(field, message, ProblemCode.REQUEST_INVALID.code());
        }
    }
}
