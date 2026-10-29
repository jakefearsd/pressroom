/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.ui.rendering.util.cache;

import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A signed-in reader's page is cached under that reader's key, never the
 * anonymous one (spec criterion 27).
 *
 * <p>Characterisation test: written to pass against the existing code. A
 * reader signs in through Spring's {@link OAuth2AuthenticationToken}, whose
 * {@code getName()} is the {@link PressroomOidcUser}'s Roller username, and
 * {@code ParsedRequest} reads the user from {@code request.getUserPrincipal()}.
 * Nothing in the cache had to change for readers, and this pins that so a
 * principal whose name is not the username cannot silently merge readers'
 * pages into the anonymous entry (or one reader's into another's).
 */
class ReaderCacheKeyTest {

    private static final String READER = "reader-abcdefghij";

    private static OAuth2AuthenticationToken readerToken() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject("google-sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(), idToken);
        List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority("ROLE_reader"));
        PressroomOidcUser principal = new PressroomOidcUser(delegate, READER, roles, false);
        return new OAuth2AuthenticationToken(principal, roles, "oidc");
    }

    private static WeblogPageRequest pageRequest(OAuth2AuthenticationToken token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pressroom/pressroom-ui/rendering/page/myblog/");
        request.setServletPath("/pressroom-ui/rendering/page");
        request.setPathInfo("/myblog/");
        request.setUserPrincipal(token);
        return new WeblogPageRequest(mock(Weblogger.class), request);
    }

    @Test
    void aReaderRequestIsAuthenticatedAsThePressroomUsername() throws Exception {
        assertEquals(READER, pageRequest(readerToken()).getAuthenticUser(),
                "the principal's name must be the Roller username, not the provider subject");
    }

    @Test
    void aReadersPageIsCachedUnderTheReadersKeyAndAnonymousIsNot() throws Exception {
        WeblogPageCache cache = WeblogPageCache.getInstance();

        String readerKey = cache.generateKey(pageRequest(readerToken()));
        String anonymousKey = cache.generateKey(pageRequest(null));

        assertTrue(readerKey.contains("/user=" + READER), readerKey);
        assertFalse(anonymousKey.contains("/user="), anonymousKey);
        assertFalse(readerKey.equals(anonymousKey), "a reader must never share the anonymous entry");
    }
}
