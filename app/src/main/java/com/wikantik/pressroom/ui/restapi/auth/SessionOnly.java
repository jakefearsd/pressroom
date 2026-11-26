package com.wikantik.pressroom.ui.restapi.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API route, or every route of a controller, as session-only (SA-18, spec
 * 2026-11-21-session-only-policy-decision): only a {@link Caller.Kind#SESSION} caller, the admin app's own
 * login, may use it. A token, a password (HTTP Basic), an unrecognised mechanism and an anonymous caller
 * are all refused with 403 {@code auth.sessionOnly}.
 *
 * <p>{@link ApiScopeInterceptor} enforces it through {@link SessionOnlyGuard}, after
 * {@code PressroomHandlerInterceptor} has judged who may see the weblog (so a caller who may not see it
 * still gets 404). It is read off the resolved handler, never the URI. Every route marked here is also an
 * MCP {@code Exclusion} with the reason "session-only" and a {@code SESSION_ONLY} row in the authorization
 * matrix; {@code SessionOnlyRoutesTest} sweeps all three, so a route cannot forget one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SessionOnly {
}
