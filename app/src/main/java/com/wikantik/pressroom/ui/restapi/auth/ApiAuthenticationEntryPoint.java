package com.wikantik.pressroom.ui.restapi.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
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
 * The token chain never calls a password the wrong credential where it is
 * the right one.
 *
 * <p>On the token resource every 401 also challenges for Basic
 * ({@code WWW-Authenticate: Basic realm="Pressroom"}), so a client that sends
 * a password only once challenged can still mint: the chain's entry point
 * ({@link #challengingForPassword}) for a request with no usable credential,
 * and the Basic filter's own ({@link #rejectingPassword}) for a password it
 * turned away. That one has its own fixed detail, the same for a wrong
 * password, a disabled account, an agent and an unknown user. Spring's
 * {@code BasicAuthenticationEntryPoint} answered that case with a bare
 * {@code sendError(401)} (Boot's {@code /error} JSON, not problem+json) until
 * 0.16.0. Off the token resource nothing challenges.
 *
 * <p>Each answer is a keyed problem ({@code auth.unauthorized},
 * {@code auth.passwordNotAcceptedHere}, {@code auth.passwordRejected}), its
 * detail in the caller's {@code Accept-Language} (spec
 * 2026-11-20-api-contract-design, Task 3).
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The English detail for a password sent anywhere but the token resource. */
    public static final String PASSWORD_REFUSED_DETAIL =
            ApiException.problem(ProblemCode.AUTH_PASSWORD_NOT_ACCEPTED_HERE).getMessage();

    /** The English detail for a password the token resource's Basic filter turned away. */
    public static final String PASSWORD_REJECTED_DETAIL =
            ApiException.problem(ProblemCode.AUTH_PASSWORD_REJECTED).getMessage();

    /** The challenge every 401 on the token resource carries. */
    public static final String BASIC_CHALLENGE = "Basic realm=\"Pressroom\"";

    /** The scheme as Spring's own Basic filter reads it: case-insensitive, after a trim. */
    private static final String BASIC = "Basic";

    private final ApiProblemWriter problemWriter;
    private final boolean refusesPasswords;
    private final ProblemCode code;
    private final boolean challenges;

    /** The plain entry point: the one fixed detail, no challenge. */
    public ApiAuthenticationEntryPoint(ApiProblemWriter problemWriter) {
        this(problemWriter, false, ProblemCode.AUTH_UNAUTHORIZED, false);
    }

    private ApiAuthenticationEntryPoint(ApiProblemWriter problemWriter, boolean refusesPasswords,
                                        ProblemCode code, boolean challenges) {
        this.problemWriter = problemWriter;
        this.refusesPasswords = refusesPasswords;
        this.code = code;
        this.challenges = challenges;
    }

    /** The general API chain's entry point: it names the token resource to a password caller. */
    public static ApiAuthenticationEntryPoint refusingPasswords(ApiProblemWriter problemWriter) {
        return new ApiAuthenticationEntryPoint(problemWriter, true, ProblemCode.AUTH_UNAUTHORIZED, false);
    }

    /** The token resource chain's entry point: the one fixed detail, and a Basic challenge. */
    public static ApiAuthenticationEntryPoint challengingForPassword(ApiProblemWriter problemWriter) {
        return new ApiAuthenticationEntryPoint(problemWriter, false, ProblemCode.AUTH_UNAUTHORIZED, true);
    }

    /** The token resource's Basic filter's entry point: a password it turned away, and a challenge. */
    public static ApiAuthenticationEntryPoint rejectingPassword(ApiProblemWriter problemWriter) {
        return new ApiAuthenticationEntryPoint(problemWriter, false, ProblemCode.AUTH_PASSWORD_REJECTED, true);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        ProblemCode answer = refusesPasswords && sentAPassword(request)
                ? ProblemCode.AUTH_PASSWORD_NOT_ACCEPTED_HERE : code;
        if (challenges) {
            response.setHeader("WWW-Authenticate", BASIC_CHALLENGE);
        }
        problemWriter.write(request, response, ApiException.problem(answer));
    }

    private static boolean sentAPassword(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.trim().regionMatches(true, 0, BASIC, 0, BASIC.length());
    }
}
