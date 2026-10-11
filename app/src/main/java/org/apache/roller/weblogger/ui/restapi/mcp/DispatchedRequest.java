package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * The MCP request, as the API route sees it when a tool call is forwarded:
 * the action's method and JSON body, and nothing else of the MCP request.
 * Identity is not a header here -- it is the security context the token
 * filter already established for this thread -- so Authorization, cookies
 * and every MCP header are hidden.
 */
final class DispatchedRequest extends HttpServletRequestWrapper {

    private static final String JSON = "application/json";

    private final String method;
    private final byte[] body;

    DispatchedRequest(HttpServletRequest mcpRequest, String method, String jsonBody) {
        super(mcpRequest);
        this.method = method;
        this.body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getContentType() {
        return body.length == 0 ? null : JSON;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public String getHeader(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "accept" -> JSON;
            case "content-type" -> getContentType();
            case "content-length" -> body.length == 0 ? null : Integer.toString(body.length);
            default -> null;
        };
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = getHeader(name);
        return Collections.enumeration(value == null ? List.of() : List.of(value));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>(List.of("Accept"));
        if (body.length > 0) {
            names.add("Content-Type");
            names.add("Content-Length");
        }
        return Collections.enumeration(names);
    }

    @Override
    public long getDateHeader(String name) {
        return -1;
    }

    @Override
    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value);
    }

    /**
     * Null, not an empty array: the servlet contract's own "no cookies", the
     * value the container gives a request that sent none.
     */
    @Override
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
            justification = "HttpServletRequest.getCookies() returns null for a request without cookies; "
                    + "that is the contract this wrapper implements, not a choice it makes.")
    public Cookie[] getCookies() {
        return null;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("A dispatched API call is read synchronously.");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
}
