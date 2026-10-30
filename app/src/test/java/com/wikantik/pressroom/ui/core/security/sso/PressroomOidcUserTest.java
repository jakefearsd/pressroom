package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * A reader's principal is held in the HTTP session, and Tomcat serializes the
 * session across a restart. A characterisation test: it passed before the two
 * {@code serial} lint warnings on this class were suppressed with a reason, and
 * it pins the claim that reason makes.
 */
class PressroomOidcUserTest {

    @Test
    void theSessionHeldPrincipalSurvivesSerialization() throws Exception {
        OidcIdToken idToken = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("iss", "https://accounts.google.com", "sub", "provider-subject", "email", "jo@example.com"));
        PressroomOidcUser user = new PressroomOidcUser(new DefaultOidcUser(List.of(), idToken), "jo",
                List.of(new SimpleGrantedAuthority("reader")), true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(user);
        }
        PressroomOidcUser copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (PressroomOidcUser) in.readObject();
        }

        assertEquals("jo", copy.getName(), "the principal name is the Pressroom username, not the subject");
        assertEquals(List.of(new SimpleGrantedAuthority("reader")), List.copyOf(copy.getAuthorities()));
        assertEquals("jo@example.com", copy.getClaims().get("email"));
        assertTrue(copy.isNewAccount());
    }
}
