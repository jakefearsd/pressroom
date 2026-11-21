package com.wikantik.pressroom.ui.restapi.mcp;

import java.io.IOException;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import com.wikantik.pressroom.ui.restapi.auth.Caller;

/**
 * The servlet at {@code /api/mcp}: the SDK's stateless transport, behind a
 * servlet of Pressroom's own so the endpoint has one place for checks the
 * transport does not make.
 */
public final class McpEndpointServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Transport-context key under which tools find the {@code HttpServletRequest}. */
    public static final String REQUEST_KEY = "pressroom.mcp.request";

    private final transient HttpServlet transport;
    private final transient ApiProblemWriter problemWriter;

    public McpEndpointServlet(HttpServlet transport, ApiProblemWriter problemWriter) {
        this.transport = transport;
        this.problemWriter = problemWriter;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        transport.init(config);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // Only a token gets through: an MCP client's config holding a password
        // is worse than one holding a revocable token. The security chain
        // already refuses Basic here (only the token resource accepts it);
        // this check does not depend on that.
        if (Caller.current().kind() != Caller.Kind.TOKEN) {
            problemWriter.write(response,
                    ApiException.unauthorized("MCP accepts API tokens only.").toProblem(request.getRequestURI()));
            return;
        }
        transport.service((ServletRequest) request, (ServletResponse) response);
    }

    @Override
    public void destroy() {
        transport.destroy();
        super.destroy();
    }
}
