package com.wikantik.pressroom.ui.restapi.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API controller as requiring an {@code ADMIN}-scoped token.
 *
 * <p>{@link ApiScopeInterceptor} reads this off the handler's bean type
 * rather than matching the request URI -- {@code getRequestURI()} is
 * undecoded per the servlet spec, while Spring routes on decoded,
 * semicolon-stripped segments, so a string match against it (e.g.
 * {@code startsWith("/v1/admin/")}) can be defeated by
 * {@code /v1/%61dmin/users} or {@code /v1/admin;x=1/users} while the request
 * still reaches a controller mapped at {@code /v1/admin/users}. It also
 * doubled as an unenforced naming convention: a future admin controller
 * mapped anywhere else was silently ungated. This annotation makes "admin
 * only" a fact about the class Spring actually dispatched to, and
 * {@code AdminScopedCoverageTest} makes forgetting it on a
 * {@code /v1/admin/*}-mapped controller a build failure rather than a
 * silent gap.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AdminScoped {
}
