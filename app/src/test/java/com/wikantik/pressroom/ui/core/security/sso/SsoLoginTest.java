package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/** The parts of {@link SsoLogin} the wiring test cannot see from outside the chain. */
class SsoLoginTest {

    /** Pressroom uses the provider only to sign the reader in, so no access token outlives the callback. */
    @Test
    void theAuthorizedClientIsNeverKept() {
        var repository = new SsoLogin.DiscardingAuthorizedClientRepository();
        Authentication reader = SsoHandlersTest.reader("reader-abcdefghij", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(SsoTestRegistrations.registration(),
                reader.getName(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token",
                        Instant.now(), Instant.now().plusSeconds(60)));

        repository.saveAuthorizedClient(client, reader, request, response);
        assertNull(repository.loadAuthorizedClient(SsoSettings.REGISTRATION_ID, reader, request));
        repository.removeAuthorizedClient(SsoSettings.REGISTRATION_ID, reader, request, response);
        assertNull(repository.loadAuthorizedClient(SsoSettings.REGISTRATION_ID, reader, request));
    }
}
