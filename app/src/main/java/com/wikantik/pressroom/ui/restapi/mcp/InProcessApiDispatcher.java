package com.wikantik.pressroom.ui.restapi.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Forwards a tool call to its {@code /api/v1} route inside the container, on
 * the MCP request's own thread (the SDK server runs with immediate
 * execution). The container computes the forwarded paths itself, so Spring
 * routes it exactly as a REST call; the security context the token filter
 * set for this thread is still in place, so {@code PressroomHandlerInterceptor}
 * and {@code ApiScopeInterceptor} decide as they do for REST. Filters
 * registered for REQUEST dispatch only (the persistence session among them)
 * run for the MCP request alone. The Spring Security chain is registered for
 * FORWARD too ({@code spring.security.filter.dispatcher-types}) and authorizes
 * the forwarded path, but the token filter inside it is a
 * {@code OncePerRequestFilter} that finds its own marker still on the request
 * and passes, so authentication is not repeated and the throttle counts a
 * tool call once.
 *
 * <p>The forward goes to a {@link CapturedResponse} that stands alone rather
 * than wrapping the MCP response: Tomcat finishes a wrapped container
 * response after a forward, which would leave the transport nothing to write
 * the JSON-RPC answer to (see {@link CapturedResponse}).
 */
public final class InProcessApiDispatcher implements ApiDispatcher {

    @Override
    public ApiReply dispatch(HttpServletRequest mcpRequest, ApiCall call) throws IOException, ServletException {
        String target = "/api" + call.path() + queryString(call.query());
        RequestDispatcher dispatcher = mcpRequest.getServletContext().getRequestDispatcher(target);
        if (dispatcher == null) {
            throw new IllegalArgumentException("The container cannot dispatch to " + target);
        }
        DispatchedRequest request = new DispatchedRequest(mcpRequest, call);
        CapturedResponse response = new CapturedResponse();
        dispatcher.forward(request, response);
        return response.reply();
    }

    /**
     * Every character outside RFC 3986's unreserved set is percent-encoded,
     * not only the ones a query parameter must escape: the container reads a
     * bare {@code +} in a query string back as a space, so a time zone offset
     * such as {@code +02:00} would otherwise arrive as {@code " 02:00"}.
     */
    static String queryString(Map<String, List<String>> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringJoiner joined = new StringJoiner("&", "?", "");
        query.forEach((name, values) -> values.forEach(value ->
                joined.add(UriUtils.encode(name, UTF_8) + "=" + UriUtils.encode(value, UTF_8))));
        return joined.toString();
    }
}
