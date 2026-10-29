package com.wikantik.pressroom.ui.restapi;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 */
public class ApiProblemWriter {

    private final ObjectMapper objectMapper;

    public ApiProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ApiProblem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
