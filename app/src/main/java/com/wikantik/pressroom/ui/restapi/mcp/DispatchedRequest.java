package com.wikantik.pressroom.ui.restapi.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * The MCP request, as the API route sees it when a tool call is forwarded:
 * the call's method, query parameters and JSON body, and nothing else of the
 * MCP request. Identity is not a header here -- it is the security context
 * the token filter already established for this thread -- so Authorization,
 * cookies and every MCP header are hidden.
 *
 * <p>Parameters are exactly the call's query. On a forward the container
 * merges the original request's parameters into the forwarded ones, so
 * without this an MCP client posting to {@code /api/mcp?status=X} would add
 * {@code status=X} to every call it makes.
 *
 * <p>Attributes are an overlay. What the dispatch sets or removes stays with
 * the dispatch, so nothing one call leaves behind (the
 * {@code actionWeblog} PressroomHandlerInterceptor sets and never clears, which
 * ApiScopeInterceptor trusts) can be read by a later call on the same MCP
 * request. Reads fall back to the MCP request, because the markers its
 * filters set (Spring Security's among them) are what stop those filters
 * from running, and clearing the security context, a second time.
 */
final class DispatchedRequest extends HttpServletRequestWrapper {

    private static final String JSON = "application/json";

    private final String method;
    private final byte[] body;
    private final Map<String, String[]> parameters;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Set<String> removedAttributes = new HashSet<>();

    DispatchedRequest(HttpServletRequest mcpRequest, ApiCall call) {
        super(mcpRequest);
        this.method = call.method();
        this.body = call.jsonBody() == null ? new byte[0] : call.jsonBody().getBytes(StandardCharsets.UTF_8);
        Map<String, String[]> query = new LinkedHashMap<>();
        call.query().forEach((name, values) -> query.put(name, values.toArray(new String[0])));
        this.parameters = Collections.unmodifiableMap(query);
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
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "The names are asked for by Spring's and Pressroom's own code while an MCP tool "
                    + "call is dispatched internally; the answers are synthetic, and no client header is "
                    + "consulted.")
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
    public String getParameter(String name) {
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    /**
     * Null for a parameter the call does not carry: the servlet contract.
     * Spring's {@code @RequestParam} resolution reads null as "absent" and
     * would take an empty array as a value that is present.
     */
    @Override
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    public String[] getParameterValues(String name) {
        String[] values = parameters.get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters;
    }

    @Override
    public Object getAttribute(String name) {
        Object value = attributes.get(name);
        if (value != null || removedAttributes.contains(name)) {
            return value;
        }
        return super.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getAttributeNames()));
        names.removeAll(removedAttributes);
        names.addAll(attributes.keySet());
        return Collections.enumeration(names);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
            return;
        }
        attributes.put(name, value);
        removedAttributes.remove(name);
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
        removedAttributes.add(name);
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
