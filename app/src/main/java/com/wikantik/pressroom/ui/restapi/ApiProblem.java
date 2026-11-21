package com.wikantik.pressroom.ui.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
 *
 * <p>The {@code @Schema} text is what openapi.json says about every
 * operation's {@code default} response ({@link ApiDocumentCustomizer}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Why a request failed: an RFC 9457 problem detail, served as application/problem+json. "
        + "Branch on code, never on detail.")
public record ApiProblem(
        @Schema(description = "The problem's coarse category, as a URI.", requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(description = "The category's short English name. Not localised.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
        @Schema(description = "The HTTP status code.", requiredMode = Schema.RequiredMode.REQUIRED)
        int status,
        @Schema(description = "What went wrong, for a person to read; in the Accept-Language language when the "
                + "server has it. Never branch on it.")
        String detail,
        @Schema(description = "The path of the request that failed.")
        String instance,
        @Schema(description = "One entry per field the request got wrong, when the problem names fields.")
        List<FieldError> errors,
        @Schema(description = "On an entry-changed or page-changed 409: the hash of the text now stored, which "
                + "the request's expectedContentHash did not match.")
        String currentContentHash,
        @Schema(description = CODE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
        String code) {

    /**
     * The text of {@code code}, on the problem and on each field error. Its
     * known values ride along as {@code x-extensible-enum}: a list a client
     * may know, never an enum it must enforce.
     */
    static final String CODE_DESCRIPTION = "A stable, case-sensitive code to branch on, refining type. Codes are "
            + "never renamed or removed, but new ones are added: treat one you do not know by its type and status.";

    /**
     * One field's problem. {@code field} is null when the problem names no
     * field the caller sent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ProblemFieldError", description = "One field's problem.")
    public record FieldError(
            @Schema(description = "The request field at fault; absent when the problem names no field the "
                    + "request sent.")
            String field,
            @Schema(description = "What is wrong with it, for a person to read. Never branch on it.")
            String message,
            @Schema(description = CODE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            String code) {

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
