package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.Collections;
import java.util.List;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class McpOriginValidatorTest {

    private final McpOriginValidator validator = new McpOriginValidator(() -> "https://blog.wikantik.com/");

    /**
     * Headers as the SDK's servlet transport hands them over: read straight
     * from the request, so a header name matches in any case.
     */
    private static HeaderAccessor headers(String... nameValuePairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.addHeader(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return new HeaderAccessor() {
            @Override
            public List<String> getHeader(String name) {
                return Collections.list(request.getHeaders(name));
            }

            @Override
            public List<String> getHeaderNames() {
                return Collections.list(request.getHeaderNames());
            }
        };
    }

    @Test
    void noOriginIsAnyCommandLineClientAndPasses() {
        assertDoesNotThrow(() -> validator.validate(headers("Host", "anything")));
    }

    @Test
    void theSitesOwnOriginPasses() {
        assertDoesNotThrow(() -> validator.validate(headers("origin", "https://blog.wikantik.com")));
    }

    @Test
    void anotherOriginIsRefusedWith403() {
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> validator.validate(headers("Origin", "https://evil.example")));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void aSecondOriginValueIsCheckedToo() {
        assertThrows(ServerTransportSecurityException.class, () -> validator.validate(
                headers("Origin", "https://blog.wikantik.com", "Origin", "https://evil.example")));
    }

    @Test
    void withNoSiteUrlEveryOriginIsRefused() {
        McpOriginValidator unset = new McpOriginValidator(() -> null);
        assertThrows(ServerTransportSecurityException.class,
                () -> unset.validate(headers("Origin", "https://blog.wikantik.com")));
        assertDoesNotThrow(() -> unset.validate(headers()));
    }

    @Test
    void aNonDefaultPortIsPartOfTheOrigin() {
        McpOriginValidator lan = new McpOriginValidator(() -> "http://docker2.lan:8080/pressroom");
        assertDoesNotThrow(() -> lan.validate(headers("Origin", "http://docker2.lan:8080")));
        assertThrows(ServerTransportSecurityException.class,
                () -> lan.validate(headers("Origin", "http://docker2.lan")));
    }

    @Test
    void aMalformedSiteUrlRefusesEveryOriginInsteadOfThrowingUnchecked() {
        McpOriginValidator bad = new McpOriginValidator(() -> "http://bad host/ with spaces");
        assertThrows(ServerTransportSecurityException.class,
                () -> bad.validate(headers("Origin", "https://blog.wikantik.com")));
        assertDoesNotThrow(() -> bad.validate(headers()));
    }
}
