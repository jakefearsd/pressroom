package org.apache.roller.weblogger.ui.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem detail. Null members are omitted, so a plain error
 * carries no empty "errors": [] and no null detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) { }
}
