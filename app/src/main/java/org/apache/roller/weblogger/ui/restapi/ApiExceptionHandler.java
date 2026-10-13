package org.apache.roller.weblogger.ui.restapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The only place an API error body is built. Scoped to the restapi package so
 * it cannot change how the JSP controllers report failures.
 */
@RestControllerAdvice(basePackages = "org.apache.roller.weblogger.ui.restapi")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiProblem> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ex.toProblem(request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidation(MethodArgumentNotValidException ex,
                                                       HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiProblem.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return handleApiException(
                ApiException.validation("One or more fields are invalid.", errors), request);
    }

    /**
     * A malformed or empty request body -- Spring throws this during
     * argument resolution, before any controller method runs, so no
     * controller can catch it itself. Without this handler it fell through
     * to {@link #handleUnexpected}: an opaque 500 for a client mistake as
     * ordinary as a trailing comma, on every {@code *Api} controller in the
     * wave. The parser's own message can echo fragments of the request body
     * and internal type names, so -- same rule as {@link #handleUnexpected}
     * -- it is logged, never returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> handleMalformedBody(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        log.info("Malformed request body at {}: {}", request.getRequestURI(), ex.getMessage());
        return handleApiException(
                ApiException.badRequest("The request body could not be parsed as JSON."), request);
    }

    /**
     * A query parameter or path variable that will not convert to the type
     * the handler method declares -- {@code ?limit=abc} against an {@code
     * int limit} -- fires during argument resolution, before any controller
     * method runs, the same "no controller can catch it itself" shape as
     * {@link #handleMalformedBody}. Without this handler it fell through to
     * {@link #handleUnexpected}: a 500 for a client mistake as ordinary as a
     * non-numeric {@code limit}, on every list endpoint in the wave that
     * takes a typed {@code @RequestParam}/{@code @PathVariable}, not just
     * one controller. The parameter NAME is fine to echo back (it is part of
     * this API's own published contract); the rejected VALUE and the
     * conversion exception's own message are not, for the same leak-risk
     * reason {@link #handleMalformedBody} withholds the parser's message.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiProblem> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                          HttpServletRequest request) {
        log.info("Type mismatch for parameter '{}' at {}", ex.getName(), request.getRequestURI(),
                ex);
        return handleApiException(
                ApiException.invalidField(ex.getName(), "'" + ex.getName() + "' is not a valid value for this parameter."),
                request);
    }

    /**
     * Anything unforeseen. The cause is logged; the client is told nothing
     * about it, because an exception message here routinely carries schema
     * names, file paths and connection strings.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception at {}", request.getRequestURI(), ex);
        ApiException opaque = new ApiException(500,
                "https://roller.invalid/problems/internal-error",
                "Internal error",
                "The request could not be completed.");
        return handleApiException(opaque, request);
    }
}
