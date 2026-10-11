package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

    public McpEndpointServlet(HttpServlet transport) {
        this.transport = transport;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        transport.init(config);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        transport.service((ServletRequest) request, (ServletResponse) response);
    }

    @Override
    public void destroy() {
        transport.destroy();
        super.destroy();
    }
}
