package com.wikantik.pressroom.ui.restapi.v1;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;

/**
 * The {@code fields} parameter of the entry routes (spec 2026-10-24, section
 * 2): which {@link EntryDtos.EntryView} properties a response carries, so an
 * agent scanning metadata is not sent every entry's text. Names are the
 * view's JSON property names (its record components; Jackson is configured
 * with no naming strategy and the view renames nothing). {@code id} always
 * comes back. None asked is the full view, the very object the route returned
 * before {@code fields} existed.
 *
 * <p>A projection is a map of the asked components' values, in the view's
 * order, without the null ones, exactly as {@code @JsonInclude(NON_NULL)}
 * omits them from the full view; the values themselves are written by the
 * same message converter as the full view's, so a projected field reads
 * exactly as it does unprojected.
 */
final class EntryFields {

    /** One text for every route, since one MCP property serves them all. */
    static final String DESCRIPTION = "The entry fields to return, for example [\"anchor\", \"searchDescription\"]; "
            + "id always comes back, and a list projects each item. Without it, the whole entry, text included.";

    private static final String ID = "id";

    private static final RecordComponent[] COMPONENTS = EntryDtos.EntryView.class.getRecordComponents();

    /** Every name {@code fields} may hold, in the view's order. */
    static final List<String> NAMES = Arrays.stream(COMPONENTS).map(RecordComponent::getName).toList();

    private static final EntryFields ALL = new EntryFields(Set.of());

    /** The names to keep, id among them; empty for the full view. */
    private final Set<String> shown;

    private EntryFields(Set<String> shown) {
        this.shown = shown;
    }

    /** {@code fields} as sent; a name the view does not have is a 400 on {@code fields}. */
    static EntryFields of(List<String> fields) {
        FieldErrors errors = new FieldErrors();
        EntryFields parsed = of(fields, errors);
        errors.throwIfAny();
        return parsed;
    }

    /**
     * As {@link #of(List)}, for a write that reports every problem in one 400:
     * an unknown name is recorded in {@code errors} (and the full view
     * returned meanwhile) rather than thrown. Null, empty and blank names
     * ask for nothing, so {@code ?fields=} is the full view.
     */
    static EntryFields of(List<String> fields, FieldErrors errors) {
        Set<String> asked = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        if (fields != null) {
            for (String field : fields) {
                String name = field == null ? "" : field.trim();
                if (NAMES.contains(name)) {
                    asked.add(name);
                } else if (!name.isEmpty()) {
                    unknown.add("'" + name + "'");
                }
            }
        }
        if (!unknown.isEmpty()) {
            errors.add("fields", (unknown.size() == 1 ? "Unknown field " : "Unknown fields ")
                    + String.join(", ", unknown) + "; a field is one of: " + String.join(", ", NAMES) + ".");
            return ALL;
        }
        if (asked.isEmpty()) {
            return ALL;
        }
        asked.add(ID);
        return new EntryFields(Set.copyOf(asked));
    }

    /** The view itself when every field is shown, else the asked fields' non-null values. */
    Object project(EntryDtos.EntryView view) {
        if (shown.isEmpty()) {
            return view;
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (RecordComponent component : COMPONENTS) {
            if (shown.contains(component.getName())) {
                Object value = valueOf(component, view);
                if (value != null) {
                    projected.put(component.getName(), value);
                }
            }
        }
        return projected;
    }

    /** The page itself when every field is shown, else the same envelope with each item projected. */
    Object project(EntryDtos.EntryPage page) {
        if (shown.isEmpty()) {
            return page;
        }
        return new Page(page.items().stream().map(this::project).toList(),
                page.offset(), page.limit(), page.hasMore());
    }

    private static Object valueOf(RecordComponent component, EntryDtos.EntryView view) {
        try {
            return component.getAccessor().invoke(view);
        } catch (ReflectiveOperationException e) {
            // A record's accessors are public and never throw: unreachable.
            throw new IllegalStateException("EntryView." + component.getName() + " could not be read", e);
        }
    }

    /** {@link EntryDtos.EntryPage}'s envelope around projected items. */
    record Page(List<Object> items, int offset, int limit, boolean hasMore) {
    }
}
