package com.wikantik.pressroom.ui.restapi.mcp;

import java.net.URI;
import java.util.function.Supplier;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;

/**
 * A browser page can never drive /api/mcp: a request carrying an Origin
 * other than site.absoluteurl's origin is refused with 403 (with the site
 * URL unset, every Origin is). No Origin -- every command-line MCP client --
 * passes. Host is deliberately not checked (custom domains, LAN names); the
 * SDK's DefaultServerTransportSecurityValidator would check both.
 */
public final class McpOriginValidator implements ServerHttpHeaderValidator {

    private final Supplier<String> siteAbsoluteUrl;

    public McpOriginValidator(Supplier<String> siteAbsoluteUrl) {
        this.siteAbsoluteUrl = siteAbsoluteUrl;
    }

    /** The accessor reads the servlet request, so the header name matches in any case. */
    @Override
    public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
        for (String origin : headers.getHeader("Origin")) {
            if (origin != null && !origin.isBlank() && !origin.equals(siteOrigin())) {
                throw new ServerTransportSecurityException(403, "Invalid Origin header");
            }
        }
    }

    private String siteOrigin() {
        String url = siteAbsoluteUrl.get();
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null; // a malformed site URL is as good as unset: every Origin is refused
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
