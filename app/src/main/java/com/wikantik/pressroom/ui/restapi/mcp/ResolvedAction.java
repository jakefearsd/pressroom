package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One action of a tool, resolved against the live routes. Most actions are
 * one route; entries.preview is two (with an id, or without), and the first
 * variant whose path arguments are all supplied is the one dispatched.
 */
public record ResolvedAction(String name, String description, List<Variant> variants) {

    public record Variant(ApiRoute route, List<ArgumentSpec> arguments, boolean takesBody) {
        public Variant {
            arguments = List.copyOf(arguments);
        }
    }

    public ResolvedAction {
        variants = List.copyOf(variants);
    }

    /** Every argument any variant takes. */
    public Set<String> argumentNames() {
        Set<String> names = new LinkedHashSet<>();
        variants.forEach(v -> v.arguments().forEach(a -> names.add(a.name())));
        return names;
    }

    public Variant select(Map<String, Object> args) {
        for (Variant variant : variants) {
            boolean pathComplete = variant.arguments().stream()
                    .filter(a -> a.location() == ArgumentSpec.Location.PATH)
                    .allMatch(a -> args.containsKey(a.name()));
            if (pathComplete) {
                return variant;
            }
        }
        return variants.get(variants.size() - 1);
    }
}
