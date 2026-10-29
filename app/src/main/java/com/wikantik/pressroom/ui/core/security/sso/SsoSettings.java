package com.wikantik.pressroom.ui.core.security.sso;

import com.wikantik.pressroom.config.WebloggerConfig;

/**
 * The single-sign-on configuration read from {@code roller.properties}.
 *
 * <p>The client secret is held here but {@link #toString()} never prints it.
 *
 * @param clientId     OAuth client id
 * @param clientSecret OAuth client secret
 * @param discoveryUri the issuer URL, or its {@code /.well-known/openid-configuration} URI
 * @param providerLabel the name shown on the sign-in button
 */
public record SsoSettings(String clientId, String clientSecret, String discoveryUri, String providerLabel) {

    /** The one registration id the app uses. */
    public static final String REGISTRATION_ID = "oidc";
    /** Default provider discovery document. */
    public static final String GOOGLE_DISCOVERY = "https://accounts.google.com/.well-known/openid-configuration";

    private static final String WELL_KNOWN = "/.well-known/openid-configuration";
    private static final String DEFAULT_LABEL = "Google";

    /** Trims every value and applies the defaults for a blank discovery URI or label. */
    public SsoSettings {
        clientId = trim(clientId);
        clientSecret = trim(clientSecret);
        discoveryUri = trim(discoveryUri).isEmpty() ? GOOGLE_DISCOVERY : trim(discoveryUri);
        providerLabel = trim(providerLabel).isEmpty() ? DEFAULT_LABEL : trim(providerLabel);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** Reads the four {@code sso.*} properties. */
    public static SsoSettings fromConfig() {
        return new SsoSettings(
                WebloggerConfig.getProperty("sso.oidc.client.id"),
                WebloggerConfig.getProperty("sso.oidc.client.secret"),
                WebloggerConfig.getProperty("sso.oidc.discovery.uri"),
                WebloggerConfig.getProperty("sso.provider.label"));
    }

    /** True when both the client id and the secret are set. */
    public boolean enabled() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** The issuer: the discovery value without a {@code /.well-known/...} suffix or trailing slash. */
    public String issuer() {
        String s = discoveryUri;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(WELL_KNOWN)) {
            s = s.substring(0, s.length() - WELL_KNOWN.length());
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public String toString() {
        return "SsoSettings[clientId=" + clientId + ", discoveryUri=" + discoveryUri
                + ", providerLabel=" + providerLabel
                + ", clientSecret=" + (clientSecret.isBlank() ? "<unset>" : "<set>") + "]";
    }
}
