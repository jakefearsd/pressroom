package org.apache.roller.weblogger.ui.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem detail. Null members are omitted, so a plain error
 * carries no empty "errors": [] and no null detail. {@code
 * currentContentHash} is an extension member, set only on a 409
 * entry-changed: the hash of the entry text now stored, which the caller's
 * {@code expectedContentHash} did not match.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors,
        String currentContentHash) {

    /** A problem with no extension member: every problem but entry-changed. */
    public ApiProblem(String type, String title, int status, String detail, String instance,
                      List<FieldError> errors) {
        this(type, title, status, detail, instance, errors, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) { }
}
