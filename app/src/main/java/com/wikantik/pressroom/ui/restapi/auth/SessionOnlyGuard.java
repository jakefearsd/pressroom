package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.springframework.web.method.HandlerMethod;

/**
 * The one place a {@link SessionOnly} route is refused to a caller that is not an interactive session.
 * The caller kind comes from {@link Caller}, the one resolver; only {@link Caller.Kind#SESSION} passes,
 * so every other kind, including one added later, is refused until it is named here.
 */
public final class SessionOnlyGuard {

    private SessionOnlyGuard() {
    }

    /** Whether {@code handler} is a session-only route: the method or its controller carries the mark. */
    public static boolean isMarked(Object handler) {
        return handler instanceof HandlerMethod method
                && (method.getMethod().isAnnotationPresent(SessionOnly.class)
                        || method.getBeanType().isAnnotationPresent(SessionOnly.class));
    }

    /** Refuses, with 403 {@code auth.sessionOnly}, any caller that is not a session. */
    public static void require(Caller caller) {
        if (!caller.isSession()) {
            throw ApiException.problem(ProblemCode.AUTH_SESSION_ONLY);
        }
    }

    /** Refuses {@code caller} when {@code handler} is session-only. */
    public static void check(Caller caller, Object handler) {
        if (isMarked(handler)) {
            require(caller);
        }
    }
}
