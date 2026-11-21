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
 *
 * <p>The one other detail is for a password sent off the token resource
 * (spec 2026-11-20, Task 2): only {@code /api/v1/tokens} and
 * {@code /api/v1/tokens/{id}} accept HTTP Basic, so the general API chain
 * carries no Basic filter, and a request there with a Basic header is simply
 * anonymous. That chain's entry point ({@link #refusingPasswords}) says where
 * a password is accepted instead. It reads the header's scheme only, never
 * the credential, so a right and a wrong password are told the same thing.
 * The token chain uses the plain entry point, so a password is never called
 * the wrong credential where it is the right one.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The detail for a password sent anywhere but the token resource. */
    public static final String PASSWORD_REFUSED_DETAIL =
            "Password authentication is accepted only on /api/v1/tokens. "
                    + "Mint a token there and send it as a bearer token.";

    private static final String AUTHENTICATION_REQUIRED = "Authentication required.";

    /** The scheme as Spring's own Basic filter reads it: case-insensitive, after a trim. */
    private static final String BASIC = "Basic";

    private final ApiProblemWriter problemWriter;
    private final boolean refusesPasswords;

    public ApiAuthenticationEntryPoint(ApiProblemWriter problemWriter) {
        this(problemWriter, false);
    }

    private ApiAuthenticationEntryPoint(ApiProblemWriter problemWriter, boolean refusesPasswords) {
        this.problemWriter = problemWriter;
        this.refusesPasswords = refusesPasswords;
    }

    /** The general API chain's entry point: it names the token resource to a password caller. */
    public static ApiAuthenticationEntryPoint refusingPasswords(ApiProblemWriter problemWriter) {
        return new ApiAuthenticationEntryPoint(problemWriter, true);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        String detail = refusesPasswords && sentAPassword(request)
                ? PASSWORD_REFUSED_DETAIL : AUTHENTICATION_REQUIRED;
        ApiProblem problem = ApiException.unauthorized(detail).toProblem(request.getRequestURI());
        problemWriter.write(response, problem);
    }

    private static boolean sentAPassword(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.trim().regionMatches(true, 0, BASIC, 0, BASIC.length());
    }
}
