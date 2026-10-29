package com.wikantik.pressroom.ui.restapi.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API endpoint that a weblog-scoped token may reach even though the
 * route identifies no weblog for {@link ApiScopeInterceptor} to check the
 * token's scope against.
 *
 * <p>The default for that situation is deny, not allow -- a scoped token is
 * a ceiling, and a route the ceiling cannot evaluate must not become an
 * unlimited one by accident (this is exactly how a POST-scoped token could
 * otherwise enumerate and delete every token its owner held via
 * {@code /v1/tokens}, a route with no weblog in it at all). This annotation
 * is the explicit, narrow exception list, applied method-by-method rather
 * than a whole controller at a time so that a controller which mixes
 * weblog-less and weblog-scoped endpoints (as {@code MetaApi} will once it
 * grows one) cannot exempt more than it means to.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WeblogScopeExempt {
}
