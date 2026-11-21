package com.wikantik.pressroom.ui.restapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place outside {@code ApiExceptionHandler} that builds an API error
 * body by hand.
 *
 * <p>{@code ApiExceptionHandler} is a {@code @RestControllerAdvice}: it only
 * ever sees an exception thrown while {@code DispatcherServlet} is running a
 * Spring MVC handler. A {@code ServletFilter} (like {@code
 * ApiTokenAuthFilter}'s throttle) and Spring Security's own {@code
 * AuthenticationEntryPoint}/{@code AccessDeniedHandler} all run before, or
 * entirely outside, that reach -- so each of them has to write a problem+json
 * body itself rather than throw and let the advice handle it. This class is
 * what keeps those writes identical to each other and to {@code
 * ApiExceptionHandler}'s, instead of three or four hand-rolled copies
 * drifting apart one bug fix at a time.
 *
 * <p>{@link #write(HttpServletRequest, HttpServletResponse, ApiException)}
 * renders the problem's keyed text in the caller's {@code Accept-Language},
 * exactly as {@code ApiExceptionHandler} does (spec
 * 2026-11-20-api-contract-design, Task 3).
 */
public class ApiProblemWriter {

    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    /** Renders from {@code ApplicationResources}, as the application's own message source does. */
    public ApiProblemWriter(ObjectMapper objectMapper) {
        this(objectMapper, ProblemMessages.applicationResources());
    }

    public ApiProblemWriter(ObjectMapper objectMapper, MessageSource messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    /** {@code problem}, rendered for {@code request}: its status, and its detail in the caller's language. */
    public void write(HttpServletRequest request, HttpServletResponse response, ApiException problem)
            throws IOException {
        write(response, problem.toProblem(request, messages));
    }

    /** A problem already rendered (MCP's, which is always English). */
    public void write(HttpServletResponse response, ApiProblem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
