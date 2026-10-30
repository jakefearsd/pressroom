package com.wikantik.pressroom.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import com.wikantik.pressroom.business.WebloggerProvider;
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
 * <p>The callback is {@code <Absolute URL without trailing slashes>/pressroom-ui/sso/callback},
 * never derived from the request (spec criterion 7): a visitor on
 * {@code http://docker2.lan:8080} would otherwise send the provider a callback it
 * refuses. With no Absolute URL this resolves nothing, so no reader is ever sent
 * to the provider with a callback that cannot work.
 *
 * <p>Spring calls this on every request through the UI chain, so the Absolute
 * URL -- a database read -- is read only on the authorization endpoint itself.
 *
 * <p>The persistence session is released exactly once whichever way that read
 * ends (spec criterion 32; see {@link PersistenceSession}), and only here when
 * this resolver is what ends the request:
 * <ul>
 *   <li>a request is returned: the redirect filter answers it inside the
 *       security chain, so it is released here;</li>
 *   <li>null is returned: the redirect filter continues the chain and
 *       {@code PersistenceSessionFilter} releases;</li>
 *   <li>building the request throws: the redirect filter hands it to
 *       {@link SsoFailureHandler}, which releases.</li>
 * </ul>
 * Releasing on the last two as well would release twice, and a second release
 * is not free: it opens a fresh EntityManager only to close it.
 */
public class PressroomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final RequestMatcher authorizationEndpoint = PathPatternRequestMatcher.withDefaults()
            .matcher(SsoPaths.AUTHORIZE_BASE + "/{registrationId}");
    private final Supplier<String> absoluteUrl;
    private final WebloggerProvider webloggerProvider;

    /**
     * @param registrations     the provider registration
     * @param absoluteUrl       the configured Absolute URL, read per sign-in
     * @param webloggerProvider answers "is the tier up?" for the release when this ends the request
     */
    public PressroomAuthorizationRequestResolver(ClientRegistrationRepository registrations,
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
        if (!authorizationEndpoint.matches(request) || callbackUrl() == null) {
            return null;
        }
        return releasedIfAnswered(delegate.resolve(request));
    }

    /**
     * Reached only through {@code ClientAuthorizationRequiredException}, which
     * belongs to {@code oauth2Client()}; Pressroom configures {@code oauth2Login()}
     * alone. Kept consistent with {@link #resolve(HttpServletRequest)} all the same.
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        if (callbackUrl() == null) {
            return null;
        }
        return releasedIfAnswered(delegate.resolve(request, clientRegistrationId));
    }

    /** A returned request ends this request in the security chain, so its persistence session goes now. */
    private OAuth2AuthorizationRequest releasedIfAnswered(OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest != null) {
            PersistenceSession.release(webloggerProvider);
        }
        return authorizationRequest;
    }

    /** @return the callback for the configured Absolute URL, or null when none is set */
    private String callbackUrl() {
        String absolute = absoluteUrl.get();
        if (absolute == null || absolute.isBlank()) {
            return null;
        }
        return SsoPaths.onAbsoluteUrl(absolute, SsoPaths.CALLBACK);
    }
}
