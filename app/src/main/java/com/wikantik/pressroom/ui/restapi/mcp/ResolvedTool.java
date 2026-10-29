package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A declared tool with its actions resolved against the live routes, in declaration order. */
public record ResolvedTool(String name, String description, boolean readOnly, Map<String, ResolvedAction> actions) {

    public ResolvedTool {
        actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
    }

    /** A single-route tool takes no "action" argument. */
    public boolean singleAction() {
        return actions.size() == 1 && actions.containsKey("");
    }
}
