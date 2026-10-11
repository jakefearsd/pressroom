package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Runs an {@link ApiCall} through the automation API on behalf of the MCP
 * request that asked for it, so the API's own authorization and validation
 * decide -- the reason MCP is a façade rather than a second API.
 */
@FunctionalInterface
public interface ApiDispatcher {

    ApiReply dispatch(HttpServletRequest mcpRequest, ApiCall call) throws IOException, ServletException;
}
