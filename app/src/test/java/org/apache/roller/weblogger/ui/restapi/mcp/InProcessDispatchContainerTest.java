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
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static Tomcat tomcat;
    private static String mcpUrl;
    private static Level tomcatLogLevel;

    /** Echoes the request it was forwarded, as JSON-ish text; 404s through sendError on /v1/missing. */
    private static final class EchoApi extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if ("/v1/missing".equals(request.getPathInfo())) {
                response.sendError(404);
                return;
            }
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            response.setStatus(201);
            response.setContentType("application/json");
            response.getWriter().write(request.getMethod() + " " + request.getRequestURI()
                    + " q=" + request.getParameter("q")
                    + " auth=" + request.getHeader("Authorization")
                    + " body=" + body);
        }
    }

    /** Dispatches the call named by the {@code to} header, then answers with what came back. */
    private static final class ForwardingTransport extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            ApiReply reply = new InProcessApiDispatcher().dispatch(request, new ApiCall("POST",
                    request.getHeader("to"), Map.of("q", List.of("a+b")), "{\"title\":\"t\"}"));
            response.setContentType("text/plain");
            response.getWriter().write("transport saw " + reply.status() + " " + reply.contentType()
                    + " [" + reply.body() + "]");
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
        Tomcat.addServlet(context, "api", new EchoApi());
        context.addServletMappingDecoded("/api/*", "api");
        Tomcat.addServlet(context, "mcp", new McpEndpointServlet(new ForwardingTransport()));
        context.addServletMappingDecoded("/api/mcp", "mcp");
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

    private static HttpResponse<String> callMcp(String to) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(mcpUrl))
                    .header("Authorization", "Bearer rlr_secret")
                    .header("to", to)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theTransportStillAnswersAfterTheForwardWithWhatTheApiRouteWrote() throws Exception {
        HttpResponse<String> response = callMcp("/v1/things");

        assertEquals(200, response.statusCode());
        assertEquals("transport saw 201 application/json"
                + " [POST /roller/api/v1/things q=a+b auth=null body={\"title\":\"t\"}]", response.body());
    }

    @Test
    void anApiSendErrorIsAStatusForTheTransportNotAnErrorPage() throws Exception {
        HttpResponse<String> response = callMcp("/v1/missing");

        assertEquals(200, response.statusCode());
        assertEquals("transport saw 404 null []", response.body());
    }
}
