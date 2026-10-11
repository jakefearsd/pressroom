package org.apache.roller.weblogger.ui.restapi.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

/**
 * A browser page can never drive /api/mcp: a request carrying an Origin
 * other than site.absoluteurl's origin is refused with 403 (with the site
 * URL unset, every Origin is). No Origin -- every command-line MCP client --
 * passes. Host is deliberately not checked (custom domains, LAN names); the
 * SDK's DefaultServerTransportSecurityValidator would check both.
 */
public final class McpOriginValidator implements ServerTransportSecurityValidator {

    private final Supplier<String> siteAbsoluteUrl;

    public McpOriginValidator(Supplier<String> siteAbsoluteUrl) {
        this.siteAbsoluteUrl = siteAbsoluteUrl;
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"origin".equals(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String origin : header.getValue()) {
                if (origin != null && !origin.isBlank() && !origin.equals(siteOrigin())) {
                    throw new ServerTransportSecurityException(403, "Invalid Origin header");
                }
            }
        }
    }

    private String siteOrigin() {
        String url = siteAbsoluteUrl.get();
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri = URI.create(url.trim());
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
