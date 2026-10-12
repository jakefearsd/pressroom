package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring's authorization request, with two changes: PKCE although the client is
 * confidential, and a callback built from the configured Absolute URL.
 *
 * <p>The callback is {@code <Absolute URL without trailing slashes>/roller-ui/sso/callback},
 * never derived from the request (spec criterion 7): a visitor on
 * {@code http://docker2.lan:8080} would otherwise send the provider a callback it
 * refuses. With no Absolute URL this resolves nothing, so no reader is ever sent
 * to the provider with a callback that cannot work.
 *
 * <p>Spring calls this on every request through the UI chain, so the Absolute
 * URL -- a database read -- is read only on the authorization endpoint itself.
 * That endpoint answers the request inside the security chain, so the read's
 * persistence session is released here; see {@link PersistenceSession}. When
 * building the request throws, {@link SsoFailureHandler} answers and releases
 * again; the second release finds nothing bound and is a no-op, the same as
 * {@code PersistenceSessionFilter}'s after a failed API token lookup.
 */
public class RollerAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final RequestMatcher authorizationEndpoint = PathPatternRequestMatcher.withDefaults()
            .matcher(SsoPaths.AUTHORIZE_BASE + "/{registrationId}");
    private final Supplier<String> absoluteUrl;
    private final WebloggerProvider webloggerProvider;

    /**
     * @param registrations     the provider registration
     * @param absoluteUrl       the configured Absolute URL, read per sign-in
     * @param webloggerProvider answers "is the tier up?" for the release after the read
     */
    public RollerAuthorizationRequestResolver(ClientRegistrationRepository registrations,
            Supplier<String> absoluteUrl, WebloggerProvider webloggerProvider) {
        this.absoluteUrl = absoluteUrl;
        this.webloggerProvider = webloggerProvider;
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, SsoPaths.AUTHORIZE_BASE);
        // Set once, never per request: the delegate is shared by every request
        // thread. The lambda reads the Absolute URL each time it runs.
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.redirectUri(callbackUrl())));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!authorizationEndpoint.matches(request)) {
            return null;
        }
        try {
            return callbackUrl() == null ? null : delegate.resolve(request);
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /**
     * Reached only through {@code ClientAuthorizationRequiredException}, which
     * belongs to {@code oauth2Client()}; Roller configures {@code oauth2Login()}
     * alone. Kept consistent with {@link #resolve(HttpServletRequest)} all the same.
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        try {
            return callbackUrl() == null ? null : delegate.resolve(request, clientRegistrationId);
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /** @return the callback for the configured Absolute URL, or null when none is set */
    private String callbackUrl() {
        String absolute = absoluteUrl.get();
        if (absolute == null || absolute.isBlank()) {
            return null;
        }
        return StringUtils.stripEnd(absolute.trim(), "/") + SsoPaths.CALLBACK;
    }
}
