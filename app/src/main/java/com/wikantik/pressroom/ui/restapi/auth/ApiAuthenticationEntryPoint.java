package com.wikantik.pressroom.ui.restapi.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblem;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers every unauthenticated {@code /api/**} request with problem+json.
 *
 * <p>Without this, Spring Security's default handling commits the response
 * itself -- a Basic challenge, or Boot's own {@code /error} page -- entirely
 * outside {@code DispatcherServlet}, so {@code ApiExceptionHandler}'s
 * {@code @RestControllerAdvice} never gets a chance to see it. This is the
 * single most common failure mode any API client hits (a missing, malformed,
 * unknown, expired or revoked token all end up here, since {@code
 * ApiTokenAuthFilter} never rejects -- it just leaves the context empty), so
 * it must produce the same error contract as everything else.
 *
 * <p>The detail text is fixed and never derived from {@code authException}:
 * a missing header, a malformed token, an unknown token, an expired token and
 * a revoked token must all read identically. Distinguishing them in the
 * response would tell an attacker which of several stolen tokens used to be
 * real.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiProblemWriter problemWriter;

    public ApiAuthenticationEntryPoint(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        ApiProblem problem = ApiException.unauthorized("Authentication required.")
                .toProblem(request.getRequestURI());
        problemWriter.write(response, problem);
    }
}
