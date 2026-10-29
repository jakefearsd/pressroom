package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.List;
import java.util.Map;

/**
 * One request to the automation API, as a tool call becomes one. {@code path}
 * is relative to {@code /api} ({@code /v1/weblogs/myblog/entries}), already
 * percent-encoded; {@code jsonBody} is null for a route without a body.
 */
public record ApiCall(String method, String path, Map<String, List<String>> query, String jsonBody) {

    public ApiCall {
        query = query == null ? Map.of() : Map.copyOf(query);
    }
}
