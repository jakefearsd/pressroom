package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;

/** Task 1's spike: three hand-wired tools proving in-process dispatch. Deleted in Task 4. */
final class SpikeTools {

    private SpikeTools() {
    }

    static List<SyncToolSpecification> all(ApiDispatcher dispatcher) {
        return List.of(
                tool(dispatcher, "whoami", Map.of("type", "object", "properties", Map.of()),
                        args -> new ApiCall("GET", "/v1/me", Map.of(), null)),
                tool(dispatcher, "spike_create_draft",
                        Map.of("type", "object", "properties", Map.of(
                                "weblog", Map.of("type", "string"), "title", Map.of("type", "string"))),
                        args -> new ApiCall("POST", "/v1/weblogs/" + args.get("weblog") + "/entries", Map.of(),
                                "{\"title\":\"" + args.get("title") + "\",\"text\":\"spike\",\"status\":\"DRAFT\"}")),
                tool(dispatcher, "spike_trash",
                        Map.of("type", "object", "properties", Map.of(
                                "weblog", Map.of("type", "string"), "id", Map.of("type", "string"))),
                        args -> new ApiCall("DELETE",
                                "/v1/weblogs/" + args.get("weblog") + "/entries/" + args.get("id"), Map.of(), null)));
    }

    private static SyncToolSpecification tool(ApiDispatcher dispatcher, String name, Map<String, Object> schema,
            java.util.function.Function<Map<String, Object>, ApiCall> toCall) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description(name).inputSchema(schema).build();
        return new SyncToolSpecification(tool, (ctx, request) -> {
            HttpServletRequest http = (HttpServletRequest) ctx.get(McpEndpointServlet.REQUEST_KEY);
            try {
                ApiReply reply = dispatcher.dispatch(http, toCall.apply(request.arguments()));
                return McpSchema.CallToolResult.builder()
                        .addTextContent(reply.body().isBlank() ? "{}" : reply.body())
                        .isError(!reply.isSuccess())
                        .build();
            } catch (java.io.IOException | jakarta.servlet.ServletException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
