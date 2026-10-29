package com.wikantik.pressroom.ui.core.security.sso;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Resolves the OIDC client registration on demand. Discovery is fetched lazily so
 * an unreachable provider at startup neither blocks boot nor stays broken: the
 * next sign-in retries. Success is cached for the life of the process.
 */
public class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger("roller.sso");
    private static final int ATTEMPTS = 3;

    private final SsoSettings settings;
    private final Function<String, ClientRegistration.Builder> fetcher;
    private final AtomicReference<ClientRegistration> cached = new AtomicReference<>();

    /** Production constructor: discovery over HTTP. */
    public LazyOidcClientRegistrationRepository(SsoSettings settings) {
        this(settings, ClientRegistrations::fromOidcIssuerLocation);
    }

    LazyOidcClientRegistrationRepository(SsoSettings settings,
            Function<String, ClientRegistration.Builder> fetcher) {
        this.settings = settings;
        this.fetcher = fetcher;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!SsoSettings.REGISTRATION_ID.equals(registrationId) || !settings.enabled()) {
            return null;
        }
        // Synchronous on purpose: the sign-in needs the registration now, a success is cached for
        // the life of the process, and a failure is not, so the next sign-in retries. Three tries with
        // no back-off keep an outage to three timeouts per sign-in; two racing first sign-ins may both
        // fetch, which is harmless because compareAndSet keeps one.
        for (int attempt = 1; attempt <= ATTEMPTS && cached.get() == null; attempt++) {
            tryResolveOnce();
        }
        return cached.get();
    }

    /** One discovery attempt; caches success. */
    public boolean tryResolveOnce() {
        if (cached.get() != null) {
            return true;
        }
        try {
            ClientRegistration built = fetcher.apply(settings.issuer())
                    .registrationId(SsoSettings.REGISTRATION_ID)
                    .clientId(settings.clientId())
                    .clientSecret(settings.clientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .scope("openid", "profile", "email")
                    // Replaced per request by PressroomAuthorizationRequestResolver with the
                    // configured Absolute URL; the template only satisfies the builder.
                    .redirectUri("{baseUrl}/pressroom-ui/sso/callback")
                    .userNameAttributeName("sub")
                    .clientName(settings.providerLabel())
                    .build();
            cached.compareAndSet(null, built);
            return true;
        } catch (RuntimeException ex) {
            // Class names only, never a message or the exception object: a provider error body
            // could echo request details, and the secret must never reach a log (spec
            // criterion 4). The whole chain, because discovery wraps the cause that matters.
            log.warn("OIDC discovery for {} failed: {}", settings.issuer(),
                    ex.getClass().getSimpleName() + CauseChain.causeNames(ex));
            return false;
        }
    }
}
