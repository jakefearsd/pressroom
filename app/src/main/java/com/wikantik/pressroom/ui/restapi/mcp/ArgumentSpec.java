package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.Map;

/**
 * One tool argument derived from a route: its MCP name, where it goes in the
 * API request, the name the API knows it by, its JSON schema (with the API
 * type's own description when it has one), and whether the route requires it.
 */
public record ArgumentSpec(String name, Location location, String sourceName,
        Map<String, Object> schema, boolean required) {

    public enum Location { PATH, QUERY, BODY, BODY_MAP }

    public ArgumentSpec {
        schema = Map.copyOf(schema);
    }
}
