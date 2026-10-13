package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A tool call's forward inside a real Tomcat, under a context path, with
 * stand-ins on both ends: an "API" servlet at {@code /api/*} that echoes what
 * it was sent, and an MCP "transport" at {@code /api/mcp} that dispatches one
 * call and then writes its own answer -- as the SDK transport writes the
 * JSON-RPC result after the tool returns.
 *
 * <p>The container is the point. Tomcat 11 suspends the container's response
 * after a forward whenever the forwarded response is a wrapper around it
 * ({@code suspendWrappedResponseAfterForward}, on by default), so a capture
 * that wraps the MCP response silently swallows the JSON-RPC answer written
 * after it: a 200 with an empty body. MockMvc and mock servlet contexts
 * cannot show that; this can, without the packaged WAR.
 */
class InProcessDispatchContainerTest {

    @TempDir
    static Path baseDir;

    /** Held so the level sticks: JUL keeps only weak references to its loggers. */
    private static final Logger TOMCAT_LOG = Logger.getLogger("org.apache");

    /** An attribute the outer request carries, as Spring Security's filter markers are. */
    private static final String MARKER = "mcp.outer.marker";

    private static Tomcat tomcat;
    private static String mcpUrl;
    private static Level tomcatLogLevel;

    /**
     * Echoes the request it was forwarded, as text. On /v1/missing it 404s
     * through sendError; on /v1/attribute it reports two request attributes,
     * then sets one and removes the other, as RollerHandlerInterceptor sets
     * actionWeblog and a filter removes its own marker.
     */
    private static final class EchoApi extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if ("/v1/missing".equals(request.getPathInfo())) {
                response.getWriter().write("partial output");
                response.sendError(404);
                return;
            }
            response.setStatus(201);
            response.setContentType("application/json");
            if ("/v1/attribute".equals(request.getPathInfo())) {
                response.getWriter().write("marker=" + request.getAttribute(MARKER)
                        + " actionWeblog=" + request.getAttribute("actionWeblog"));
                request.setAttribute("actionWeblog", "it_weblog");
                request.removeAttribute(MARKER);
                return;
            }
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, List<String>> params = new TreeMap<>();
            request.getParameterMap().forEach((name, values) -> params.put(name, List.of(values)));
            response.getWriter().write(request.getMethod() + " " + request.getRequestURI()
                    + " params=" + params
                    + " auth=" + request.getHeader("Authorization")
                    + " body=" + body);
        }
    }

    /**
     * Marks the MCP request (as a filter of the outer request would), then
     * dispatches the call named by the {@code to} header as many times as the
     * {@code times} header says, and answers with what came back.
     */
    private static final class ForwardingTransport extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            request.setAttribute(MARKER, "outer");
            StringJoiner replies = new StringJoiner(" | ", "transport saw ", "");
            for (int i = 0; i < Integer.parseInt(request.getHeader("times")); i++) {
                ApiReply reply = new InProcessApiDispatcher().dispatch(request, new ApiCall("POST",
                        request.getHeader("to"), Map.of("q", List.of("a+b")), "{\"title\":\"t\"}"));
                replies.add(reply.status() + " " + reply.contentType() + " [" + reply.body() + "]");
            }
            response.setContentType("text/plain");
            response.getWriter().write(replies.toString());
        }
    }

    @BeforeAll
    static void startTomcat() throws Exception {
        tomcatLogLevel = TOMCAT_LOG.getLevel();
        TOMCAT_LOG.setLevel(Level.WARNING);
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        Context context = tomcat.addContext("/roller", baseDir.toString());
        Tomcat.addServlet(context, "api", new EchoApi()).addMapping("/api/*");
        McpEndpointServlet endpoint = new McpEndpointServlet(new ForwardingTransport(),
                new ApiProblemWriter(JsonMapper.builder().build()));
        Wrapper mcpServlet = Tomcat.addServlet(context, "mcp", new HttpServlet() {
            private static final long serialVersionUID = 1L;

            /** Stands in for apiSecurityFilterChain: the request is a token's. */
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, IOException {
                UsernamePasswordAuthenticationToken auth =
                        UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
                auth.setDetails(new ApiPrincipal("alice", null, ApiToken.Role.ADMIN, "tok-1"));
                SecurityContextHolder.getContext().setAuthentication(auth);
                try {
                    endpoint.service(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        });
        mcpServlet.addMapping("/api/mcp");
        tomcat.getConnector();
        tomcat.start();
        mcpUrl = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/roller/api/mcp";
    }

    @AfterAll
    static void stopTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
        TOMCAT_LOG.setLevel(tomcatLogLevel);
    }

    private static HttpResponse<String> callMcp(String query, String to, int times) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(mcpUrl + query))
                    .header("Authorization", "Bearer rlr_secret")
                    .header("to", to)
                    .header("times", Integer.toString(times))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static final String THINGS_ECHO = "transport saw 201 application/json"
            + " [POST /roller/api/v1/things params={q=[a+b]} auth=null body={\"title\":\"t\"}]";

    @Test
    void theTransportStillAnswersAfterTheForwardWithWhatTheApiRouteWrote() throws Exception {
        HttpResponse<String> response = callMcp("", "/v1/things", 1);

        assertEquals(200, response.statusCode());
        assertEquals(THINGS_ECHO, response.body());
    }

    @Test
    void theMcpRequestsOwnQueryParametersNeverReachTheApi() throws Exception {
        // Tomcat merges the original request's parameters into a forward's;
        // the API must see the call's query and nothing else.
        HttpResponse<String> response = callMcp("?q=injected&status=PUBLISHED", "/v1/things", 1);

        assertEquals(200, response.statusCode());
        assertEquals(THINGS_ECHO, response.body());
    }

    @Test
    void nothingADispatchSetsOrRemovesOutlivesIt() throws Exception {
        // The first call's actionWeblog must not be what a second call's
        // scope check reads, and a marker the outer request's filters set
        // must stay visible to every call, however one of them treats it.
        HttpResponse<String> response = callMcp("", "/v1/attribute", 2);

        assertEquals(200, response.statusCode());
        assertEquals("transport saw 201 application/json [marker=outer actionWeblog=null]"
                + " | 201 application/json [marker=outer actionWeblog=null]", response.body());
    }

    @Test
    void anApiSendErrorIsAStatusForTheTransportNotAnErrorPage() throws Exception {
        HttpResponse<String> response = callMcp("", "/v1/missing", 1);

        assertEquals(200, response.statusCode());
        assertEquals("transport saw 404 null []", response.body(), "sendError discards what was written before it");
    }
}
