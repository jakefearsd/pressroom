package com.wikantik.pressroom.ui.restapi.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblem;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers a denied {@code /api/**} request with problem+json.
 *
 * <p>Same reasoning as {@link ApiAuthenticationEntryPoint}: Spring Security's
 * default handling for a denied request commits the response entirely
 * outside {@code DispatcherServlet}'s reach, where {@code
 * ApiExceptionHandler}'s {@code @RestControllerAdvice} cannot see it.
 */
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiProblemWriter problemWriter;

    public ApiAccessDeniedHandler(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        ApiProblem problem = ApiException.forbidden("Access denied.")
                .toProblem(request.getRequestURI());
        problemWriter.write(response, problem);
    }
}
