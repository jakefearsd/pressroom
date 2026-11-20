package com.wikantik.pressroom.ui.restapi.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What one resource's {@code clear} list may name, and how each name is
 * cleared: the one declaration that the validation and the application both
 * read, so the list of clearable fields and the code that clears them cannot
 * disagree. Names keep their declaration order, which is the order the
 * "Clearable: ..." message prints.
 *
 * @param <T> the object a cleared field is removed from
 */
public final class ClearPolicy<T> {

    private final Map<String, Consumer<T>> clearers;
    private final List<List<String>> pairs;

    private ClearPolicy(Map<String, Consumer<T>> clearers, List<List<String>> pairs) {
        this.clearers = clearers;
        this.pairs = pairs;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** The clearable names, in declaration order. */
    public List<String> names() {
        return List.copyOf(clearers.keySet());
    }

    /** As {@link ClearFields#validate}, against this policy's names and pairs. */
    public Set<String> validate(List<String> clear, Record body) {
        return ClearFields.validate(clear, body, names(), pairs);
    }

    /** Clears each named field on {@code target}; a name this policy does not declare is a bug. */
    public void apply(T target, Set<String> cleared) {
        for (String name : cleared) {
            Consumer<T> clearer = clearers.get(name);
            if (clearer == null) {
                throw new IllegalArgumentException("Not clearable: " + name);
            }
            clearer.accept(target);
        }
    }

    public static final class Builder<T> {
        private final Map<String, Consumer<T>> clearers = new LinkedHashMap<>();
        private final List<List<String>> pairs = new ArrayList<>();

        private Builder() {
        }

        public Builder<T> field(String name, Consumer<T> clearer) {
            clearers.put(name, clearer);
            return this;
        }

        /** Two fields that are cleared together or not at all. */
        public Builder<T> pair(String first, String second) {
            pairs.add(List.of(first, second));
            return this;
        }

        public ClearPolicy<T> build() {
            return new ClearPolicy<>(new LinkedHashMap<>(clearers), List.copyOf(pairs));
        }
    }
}
