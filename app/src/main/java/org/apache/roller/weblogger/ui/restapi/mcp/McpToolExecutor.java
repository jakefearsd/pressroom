package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Turns one tool call into one API call. Before anything is dispatched it
 * checks the action, refuses an argument the action does not take (never
 * dropping it silently) and requires the route's required arguments; then it
 * builds the path, query and JSON body from the derived argument specs,
 * dispatches, and returns the API's own body -- as an error result when the
 * API refused. Validation of the values themselves is the API's.
 */
public final class McpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);
    private static final String ACTION = "action";

    private final ApiDispatcher dispatcher;
    private final JsonMapper json = JsonMapper.builder().build();

    public McpToolExecutor(ApiDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public CallToolResult call(HttpServletRequest mcpRequest, ResolvedTool tool, Map<String, Object> arguments) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((k, v) -> {
                if (v != null) {
                    args.put(k, v);
                }
            });
        }
        ResolvedAction action;
        if (tool.singleAction()) {
            action = tool.actions().get("");
        } else {
            Object named = args.remove(ACTION);
            action = named instanceof String s ? tool.actions().get(s) : null;
            if (action == null) {
                return refused(tool.name() + " has no action " + (named == null ? "(none given)" : "'" + named + "'")
                        + "; it is one of " + String.join(", ", tool.actions().keySet()) + ".");
            }
        }
        String label = tool.singleAction() ? "`" + tool.name() + "`" : "`" + tool.name() + "` `" + action.name() + "`";
        for (String given : args.keySet()) {
            if (!action.argumentNames().contains(given)) {
                return refused("`" + given + "` does not apply to " + label + ".");
            }
        }
        ResolvedAction.Variant variant = action.select(args);
        for (ArgumentSpec spec : variant.arguments()) {
            if (spec.required() && !args.containsKey(spec.name())) {
                return refused(label + " needs `" + spec.name() + "`.");
            }
        }
        ApiCall call;
        try {
            call = toCall(variant, args);
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        try {
            return toResult(dispatcher.dispatch(mcpRequest, call));
        } catch (IOException | ServletException | RuntimeException e) {
            log.error("MCP {} could not be dispatched", label, e);
            return error(problem(500, null));
        }
    }

    private ApiCall toCall(ResolvedAction.Variant variant, Map<String, Object> args) {
        String path = variant.route().pathTemplate();
        Map<String, List<String>> query = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        Object settings = null;
        for (ArgumentSpec spec : variant.arguments()) {
            Object value = args.get(spec.name());
            if (value == null) {
                continue;
            }
            switch (spec.location()) {
                case PATH -> path = path.replace("{" + spec.sourceName() + "}",
                        UriUtils.encodePathSegment(pathSegment(spec.name(), value), UTF_8));
                case QUERY -> query.put(spec.sourceName(), values(value));
                case BODY -> body.put(spec.sourceName(), value);
                case BODY_MAP -> {
                    if (!(value instanceof Map)) {
                        throw new IllegalArgumentException("`" + spec.name() + "` must be an object.");
                    }
                    settings = value;
                }
            }
        }
        String jsonBody = null;
        if (variant.takesBody()) {
            jsonBody = json.writeValueAsString(settings != null ? settings : body);
        }
        return new ApiCall(variant.route().method(), path, query, jsonBody);
    }

    /**
     * The container normalizes dot-segments when it forwards, so an empty,
     * "." or ".." value could re-route the call to a different route; refuse it.
     */
    private static String pathSegment(String name, Object value) {
        String segment = scalar(value);
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("`" + name + "` must be a real value, not empty, \".\" or \"..\".");
        }
        return segment;
    }

    private static List<String> values(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> values.add(scalar(item)));
        } else {
            values.add(scalar(value));
        }
        return values;
    }

    private static String scalar(Object value) {
        if (value instanceof Double d && !d.isInfinite() && Double.compare(d, Math.rint(d)) == 0) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(value);
    }

    private CallToolResult toResult(ApiReply reply) {
        if (reply.isSuccess()) {
            return CallToolResult.builder()
                    .addTextContent(reply.body() == null || reply.body().isBlank() ? "{}" : reply.body())
                    .isError(false).build();
        }
        boolean jsonBody = reply.contentType() != null && reply.contentType().contains("json")
                && reply.body() != null && !reply.body().isBlank();
        return error(jsonBody ? reply.body() : problem(reply.status(), null));
    }

    private CallToolResult refused(String detail) {
        return error(problem(400, detail));
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private String problem(int status, String detail) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("status", status);
        HttpStatus known = HttpStatus.resolve(status);
        problem.put("title", known == null ? "Error" : known.getReasonPhrase());
        if (detail != null) {
            problem.put("detail", detail);
        }
        try {
            return json.writeValueAsString(problem);
        } catch (JacksonException e) {
            return "{\"status\":" + status + "}";
        }
    }
}
