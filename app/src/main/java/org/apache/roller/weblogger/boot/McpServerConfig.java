package org.apache.roller.weblogger.boot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiDispatcher;
import org.apache.roller.weblogger.ui.restapi.mcp.InProcessApiDispatcher;
import org.apache.roller.weblogger.ui.restapi.mcp.McpEndpointServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP endpoint (docs/superpowers/specs/2026-10-11-mcp-endpoint-design.md):
 * the SDK's stateless Streamable HTTP transport behind {@link McpEndpointServlet}
 * at exactly {@value #ENDPOINT}. An exact servlet mapping wins over the
 * DispatcherServlet's {@code /api/*} prefix mapping, and the path is inside
 * {@code apiSecurityFilterChain}'s {@code /api/**}, so token authentication and
 * the throttle apply with no MCP-specific security code. Nothing here touches
 * the Weblogger: these beans are built at context refresh, before bootstrap.
 */
@Configuration
public class McpServerConfig {

    public static final String ENDPOINT = "/api/mcp";

    @Bean
    public HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .messageEndpoint(ENDPOINT)
                .contextExtractor(request ->
                        McpTransportContext.create(Map.of(McpEndpointServlet.REQUEST_KEY, request)))
                .build();
    }

    /**
     * Stops Boot from mapping the transport a second time, bare. Boot maps
     * every Servlet bean no registration claims, at "/" when it is the only
     * one (else at "/beanName/"), so without this claim the SDK transport
     * could displace the container's default servlet (every static asset) and
     * would answer outside {@code apiSecurityFilterChain}. A disabled registration claims
     * the bean and maps nothing; {@code SecurityConfig.apiTokenAuthFilterRegistration}
     * does the same for a Filter bean. The transport is served only through
     * {@link McpEndpointServlet}, below.
     */
    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpTransportRegistration(
            HttpServletStatelessServerTransport mcpTransport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(mcpTransport);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ApiDispatcher mcpApiDispatcher() {
        return new InProcessApiDispatcher();
    }

    @Bean
    public McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport mcpTransport,
            ApiDispatcher mcpApiDispatcher) {
        return McpServer.sync(mcpTransport)
                .serverInfo("roller", rollerVersion())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .immediateExecution(true)
                .tools(org.apache.roller.weblogger.ui.restapi.mcp.SpikeToolsAccess.all(mcpApiDispatcher))
                .build();
    }

    @Bean
    public ServletRegistrationBean<McpEndpointServlet> mcpEndpointRegistration(
            HttpServletStatelessServerTransport mcpTransport, McpStatelessSyncServer mcpServer) {
        // mcpServer is a parameter so the server -- which installs itself as
        // the transport's handler when built -- exists before the first request.
        ServletRegistrationBean<McpEndpointServlet> registration =
                new ServletRegistrationBean<>(new McpEndpointServlet(mcpTransport), ENDPOINT);
        registration.setName("mcp");
        registration.setLoadOnStartup(2);
        return registration;
    }

    /**
     * The version WebloggerImpl reports, read from the same build-filtered
     * resource without bootstrapping the business tier.
     */
    static String rollerVersion() {
        Properties props = new Properties();
        try (InputStream in = McpServerConfig.class.getResourceAsStream("/roller-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            return "UNKNOWN";
        }
        return props.getProperty("ro.version", "UNKNOWN");
    }
}
