package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The MCP shape of a resolved tool: an input schema that is the union of its
 * actions' arguments (plus an "action" enum when there is more than one) and
 * a description listing each action and the arguments it takes. Property
 * descriptions are the API types' own text, unchanged, so openapi.json and
 * the tool say the same thing; which actions take an argument lives in the
 * tool description instead.
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static Map<String, Object> inputSchema(ResolvedTool tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!tool.singleAction()) {
            properties.put("action", Map.of("type", "string",
                    "enum", List.copyOf(tool.actions().keySet()),
                    "description", "What to do; see the tool description."));
        }
        tool.actions().values().forEach(action -> action.variants().forEach(variant ->
                variant.arguments().forEach(arg -> {
                    Object existing = properties.putIfAbsent(arg.name(), arg.schema());
                    if (existing != null && !sameType(existing, arg.schema())) {
                        throw new IllegalStateException("MCP " + tool.name() + ": argument '" + arg.name()
                                + "' has two different types across actions");
                    }
                })));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!tool.singleAction()) {
            schema.put("required", List.of("action"));
        }
        schema.put("additionalProperties", Boolean.FALSE);
        return schema;
    }

    public static String description(ResolvedTool tool) {
        StringBuilder text = new StringBuilder(tool.description());
        if (tool.singleAction()) {
            return text.toString();
        }
        text.append("\n\nActions:");
        tool.actions().values().forEach(action -> {
            List<String> args = new ArrayList<>(action.argumentNames());
            text.append("\n- ").append(action.name()).append(": ").append(action.description());
            if (!args.isEmpty()) {
                text.append(" Arguments: ").append(String.join(", ", args)).append('.');
            }
        });
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static boolean sameType(Object a, Map<String, Object> b) {
        return Objects.equals(((Map<String, Object>) a).get("type"), b.get("type"));
    }
}
