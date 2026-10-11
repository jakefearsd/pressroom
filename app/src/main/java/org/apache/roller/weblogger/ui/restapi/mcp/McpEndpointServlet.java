package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The servlet at {@code /api/mcp}: the SDK's stateless transport, behind a
 * servlet of Roller's own so the endpoint has one place for checks the
 * transport does not make.
 */
public final class McpEndpointServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Transport-context key under which tools find the {@code HttpServletRequest}. */
    public static final String REQUEST_KEY = "roller.mcp.request";

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
        // apiSecurityFilterChain accepts Basic across /api/** because minting a
        // token needs it; an MCP client's config holding a password is worse
        // than one holding a revocable token, so only a token gets through.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof ApiPrincipal)) {
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
