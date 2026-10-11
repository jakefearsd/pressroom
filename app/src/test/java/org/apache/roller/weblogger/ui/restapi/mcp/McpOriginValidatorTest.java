package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpOriginValidatorTest {

    private final McpOriginValidator validator = new McpOriginValidator(() -> "https://blog.wikantik.com/");

    @Test
    void noOriginIsAnyCommandLineClientAndPasses() {
        assertDoesNotThrow(() -> validator.validateHeaders(Map.of("Host", List.of("anything"))));
    }

    @Test
    void theSitesOwnOriginPasses() {
        assertDoesNotThrow(() -> validator.validateHeaders(Map.of("origin", List.of("https://blog.wikantik.com"))));
    }

    @Test
    void anotherOriginIsRefusedWith403() {
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> validator.validateHeaders(Map.of("Origin", List.of("https://evil.example"))));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void withNoSiteUrlEveryOriginIsRefused() {
        McpOriginValidator unset = new McpOriginValidator(() -> null);
        assertThrows(ServerTransportSecurityException.class,
                () -> unset.validateHeaders(Map.of("Origin", List.of("https://blog.wikantik.com"))));
        assertDoesNotThrow(() -> unset.validateHeaders(Map.of()));
    }

    @Test
    void aNonDefaultPortIsPartOfTheOrigin() {
        McpOriginValidator lan = new McpOriginValidator(() -> "http://docker2.lan:8080/roller");
        assertDoesNotThrow(() -> lan.validateHeaders(Map.of("Origin", List.of("http://docker2.lan:8080"))));
        assertThrows(ServerTransportSecurityException.class,
                () -> lan.validateHeaders(Map.of("Origin", List.of("http://docker2.lan"))));
    }

    @Test
    void aMalformedSiteUrlRefusesEveryOriginInsteadOfThrowingUnchecked() {
        McpOriginValidator bad = new McpOriginValidator(() -> "http://bad host/ with spaces");
        assertThrows(ServerTransportSecurityException.class,
                () -> bad.validateHeaders(Map.of("Origin", List.of("https://blog.wikantik.com"))));
        assertDoesNotThrow(() -> bad.validateHeaders(Map.of()));
    }
}
