package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    /** The one line per tool call goes to this logger. */
    public static final String LOG_NAME = "roller.mcp";
    private static final Logger calls = LoggerFactory.getLogger(LOG_NAME);
    private static final String ACTION = "action";
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ApiDispatcher dispatcher;
    private final JsonMapper json = JsonMapper.builder().build();

    public McpToolExecutor(ApiDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * One tool call, logging exactly one line (who, which tool and action,
     * which weblog, the status, how long) on every exit -- never an argument
     * value, which may be entry text.
     */
    public CallToolResult call(HttpServletRequest mcpRequest, ResolvedTool tool, Map<String, Object> arguments) {
        long started = System.currentTimeMillis();
        Outcome outcome = new Outcome(error(problem(500, null)), 500, "-", "-");
        try {
            outcome = execute(mcpRequest, tool, arguments);
        } catch (RuntimeException e) {
            // never let the SDK answer with the exception's message
            log.error("MCP `{}` failed unexpectedly", tool.name(), e);
        } finally {
            logCall(tool.name(), outcome, started);
        }
        return outcome.result();
    }

    /** What a call produced, and what its log line says about it. */
    private record Outcome(CallToolResult result, int status, String action, String weblog) { }

    private Outcome execute(HttpServletRequest mcpRequest, ResolvedTool tool, Map<String, Object> arguments) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((k, v) -> {
                if (v != null) {
                    args.put(k, v);
                }
            });
        }
        String weblog = loggableWeblog(args.get("weblog"));
        ResolvedAction action;
        if (tool.singleAction()) {
            action = tool.actions().get("");
        } else {
            Object named = args.remove(ACTION);
            action = named instanceof String s ? tool.actions().get(s) : null;
            if (action == null) {
                return refusal("-", weblog, tool.name() + " has no action "
                        + (named == null ? "(none given)" : "'" + named + "'")
                        + "; it is one of " + String.join(", ", tool.actions().keySet()) + ".");
            }
        }
        String label = tool.singleAction() ? "`" + tool.name() + "`" : "`" + tool.name() + "` `" + action.name() + "`";
        for (String given : args.keySet()) {
            if (!action.argumentNames().contains(given)) {
                return refusal(action.name(), weblog, "`" + given + "` does not apply to " + label + ".");
            }
        }
        ResolvedAction.Variant variant = action.select(args);
        for (ArgumentSpec spec : variant.arguments()) {
            if (spec.required() && !args.containsKey(spec.name())) {
                return refusal(action.name(), weblog, label + " needs `" + spec.name() + "`.");
            }
        }
        ApiCall call;
        try {
            call = toCall(variant, args);
        } catch (IllegalArgumentException e) {
            return refusal(action.name(), weblog, e.getMessage());
        }
        try {
            ApiReply reply = dispatcher.dispatch(mcpRequest, call);
            return new Outcome(toResult(reply), reply.status(), action.name(), weblog);
        } catch (IOException | ServletException | RuntimeException e) {
            log.error("MCP {} could not be dispatched", label, e);
            return new Outcome(error(problem(500, null)), 500, action.name(), weblog);
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
     * A path value must stay one segment. The container, when it forwards,
     * decodes an encoded "%2F" into a real "/", strips ";params" and
     * normalizes dot-segments, so encoding does not keep a value in its
     * segment: refuse the values that could re-route the call. Real handles,
     * ids and user names never contain these.
     */
    private static String pathSegment(String name, Object value) {
        String segment = scalar(value);
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf(';') >= 0) {
            throw new IllegalArgumentException("`" + name
                    + "` must be a single path segment: not empty, \".\" or \"..\", and without \"/\", \"\\\" or \";\".");
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

    /**
     * The weblog as the log line may carry it: a handle-shaped string, "?" for
     * anything else (the value is the caller's and may be content or a forged
     * line), "-" when absent.
     */
    private static String loggableWeblog(Object weblog) {
        if (weblog == null) {
            return "-";
        }
        return weblog instanceof String s && HANDLE.matcher(s).matches() ? s : "?";
    }

    private Outcome refusal(String action, String weblog, String detail) {
        return new Outcome(error(problem(400, detail)), 400, action, weblog);
    }

    private static void logCall(String tool, Outcome outcome, long started) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String tokenId = auth != null && auth.getDetails() instanceof ApiPrincipal p ? p.tokenId() : "-";
        String user = auth != null ? auth.getName() : "-";
        // token label and user name are attacker-influenced: only ever {} arguments
        calls.info("token={} user={} tool={} action={} weblog={} status={} ms={}",
                tokenId, user, tool, outcome.action().isEmpty() ? "-" : outcome.action(), outcome.weblog(),
                outcome.status(), System.currentTimeMillis() - started);
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
