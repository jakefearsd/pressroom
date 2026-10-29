package com.wikantik.pressroom.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblem;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import com.wikantik.pressroom.util.TokenGenerator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer prs_...} into an authenticated
 * SecurityContext.
 *
 * <p>Never rejects an authentication failure: an absent or bad token simply
 * leaves the context empty and the security chain answers 401. Keeping
 * rejection in one place means the API cannot grow two different
 * unauthenticated responses.
 *
 * <p>The manager arrives through a Supplier because the business tier is
 * built lazily at {@code WebloggerProvider.bootstrap()}, after this filter
 * bean is constructed.
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final Supplier<ApiTokenManager> tokenManager;
    private final WebloggerProvider webloggerProvider;
    private final ApiThrottle throttle;
    private final ApiProblemWriter problemWriter;

    /**
     * @param tokenManager resolved per authentication, not latched -- this
     *        filter is constructed at context refresh, before the tier exists
     * @param webloggerProvider answers "is the tier up?" for the
     *        release-on-failure path below; this filter runs ahead of
     *        {@code BootstrapFilter} and so can see requests before bootstrap
     */
    public ApiTokenAuthFilter(Supplier<ApiTokenManager> tokenManager,
                              WebloggerProvider webloggerProvider,
                              ApiProblemWriter problemWriter) {
        this(tokenManager, webloggerProvider, ApiThrottle.create(), problemWriter);
    }

    ApiTokenAuthFilter(Supplier<ApiTokenManager> tokenManager, WebloggerProvider webloggerProvider,
                       ApiThrottle throttle, ApiProblemWriter problemWriter) {
        this.tokenManager = tokenManager;
        this.webloggerProvider = webloggerProvider;
        this.throttle = throttle;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean bearer = header != null && header.startsWith(BEARER);
        String rawToken = bearer ? header.substring(BEARER.length()).trim() : null;

        // Key by token where there is one, by client address otherwise -- so
        // the password-taking mint endpoint is rate-limited by caller and
        // every other endpoint by credential. The digest, not the raw token,
        // is the map key: a throttle map holding live credentials in memory
        // is a credential store nobody meant to build.
        String key = bearer ? TokenGenerator.sha256Hex(rawToken) : request.getRemoteAddr();
        if (throttle.isThrottled(key)) {
            writeThrottled(request, response);
            return;
        }

        if (bearer) {
            authenticate(rawToken);
        }
        chain.doFilter(request, response);
    }

    /**
     * A ServletFilter runs before, and entirely outside, DispatcherServlet --
     * so {@code ApiExceptionHandler}'s {@code @RestControllerAdvice} can never
     * see an exception thrown from here. Building the body via {@code
     * ApiException.throttled(...).toProblem(...)} and handing it to the
     * shared {@link ApiProblemWriter} (also used by {@link
     * ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler}, the
     * other two writers that run outside this same reach) is what keeps this
     * error shape from drifting from {@code ApiExceptionHandler}'s.
     */
    private void writeThrottled(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ApiProblem problem = ApiException
                .throttled("Too many requests. Slow down and retry.")
                .toProblem(request.getRequestURI());
        response.setHeader("Retry-After", Integer.toString(throttle.retryAfterSeconds()));
        problemWriter.write(response, problem);
    }

    /**
     * This filter runs inside the security chain at order 40;
     * {@code PersistenceSessionFilter} -- the only thing that otherwise
     * ever releases the current request's persistence session -- is order
     * 60. The digest lookup above (and, for a usable token, {@code
     * touchLastUsed}'s write) binds an EntityManager to this Tomcat worker
     * thread regardless of outcome. When this method fails to establish a
     * SecurityContext, {@code authorizeHttpRequests} is about to refuse the
     * request and {@code ExceptionTranslationFilter} hands the 401 straight
     * to {@code ApiAuthenticationEntryPoint} without ever calling the outer
     * chain -- order 60 never runs, and without the explicit release below
     * the leaked EntityManager stays bound to this thread, with whatever
     * persistence context it accumulated, until some unrelated later
     * request on the same thread happens to reach order 60 and inherit it.
     *
     * <p>The converse matters just as much: on a SUCCESSFUL authentication
     * this method must NOT release. {@code SecurityConfig.apiSecurityFilterChain}'s
     * {@code authorizeHttpRequests} actually has three rules -- {@code POST
     * /api/v1/tokens} and {@code /api/v1/ping} each get their own
     * ({@code authenticated()} and {@code permitAll()} respectively), then
     * {@code anyRequest().authenticated()} covers everything else -- and
     * setting the context here reliably means the request proceeds through
     * the rest of the chain to order 60 REGARDLESS of which rule applies:
     * an authenticated caller obviously satisfies {@code authenticated()},
     * and {@code permitAll()} lets the request through independent of
     * authentication either way. Order 60's release() is meant to be the
     * one that runs on this path -- the controller downstream is still
     * going to use this same EntityManager.
     *
     * <p>Releasing here is safe on the FAILURE branch even for a
     * {@code permitAll()} route like {@code /api/v1/ping}: unlike the
     * {@code authenticated()} routes, a failed authentication there does
     * NOT get refused -- the request still proceeds to order 60 regardless.
     * That is not a problem, because {@code release()} only clears this
     * thread's bound EntityManager; nothing downstream is left without one
     * -- {@code getEntityManager(...)} lazily opens a fresh one on demand,
     * and order 60's own release() immediately afterward is a harmless
     * no-op (open-then-close an EntityManager nothing used). Releasing on
     * BOTH branches unconditionally was considered and rejected instead,
     * specifically because the SUCCESS branch's release() would be wrong on
     * every non-{@code permitAll} route: the controller downstream still
     * needs the same EntityManager, and order 60 -- guaranteed to run
     * whenever authentication succeeded -- already owns closing it.
     *
     * <p>Two other routes were considered instead of this localized
     * try/finally (see {@code TokensApi}'s class javadoc's cross-reference
     * for the full writeup): reordering {@code PersistenceSessionFilter}
     * ahead of the security chain would fix this cleanly in principle, but
     * that filter's URL pattern is {@code /*} and its order is a single
     * global number -- {@code spring.security.filter.order} names one
     * position for the whole app, not a per-chain setting -- so scoping a
     * reorder to just {@code /api/*} was never actually possible; moving it
     * changes behaviour for the JSP admin path's own early security
     * rejections (bad login, CSRF) too, a bigger, differently-risky change
     * than this task's scope covers.
     */
    private void authenticate(String rawToken) {
        boolean authenticated = false;
        try {
            ApiToken token = tokenManager.get().authenticate(rawToken);
            if (token == null) {
                return;
            }
            // Spec 2026-10-13, section 2: a disabled owner -- person or agent --
            // gets a 401. Checked here, explicitly, rather than left to the
            // enabled-only user lookup downstream that happens to refuse it.
            if (!Boolean.TRUE.equals(token.getUser().getEnabled())) {
                return;
            }
            String userName = token.getUser().getUserName();
            ApiPrincipal principal =
                    new ApiPrincipal(userName, token.getScopeWeblog(), token.getScopeRole(), token.getId());
            var auth = new UsernamePasswordAuthenticationToken(
                    userName, null, List.of(new SimpleGrantedAuthority("api")));
            auth.setDetails(principal);
            SecurityContextHolder.getContext().setAuthentication(auth);
            authenticated = true;
        } catch (Exception e) {
            // Never let a lookup failure become an authenticated request.
            log.error("Error authenticating API token", e);
        } finally {
            if (!authenticated && webloggerProvider.isBootstrapped()) {
                webloggerProvider.getWeblogger().release();
            }
        }
    }
}
