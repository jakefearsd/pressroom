package org.apache.roller.weblogger.ui.restapi.dto;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.ui.restapi.ApiException;

/**
 * The {@code clear} list of an update body: the fields to remove. A null
 * field in a body always means "unchanged", so an agent that sends null for
 * every argument it meant to skip never erases anything; removing a value is
 * this explicit list. One rule for every resource that has one.
 */
public final class ClearFields {

    private ClearFields() {
    }

    public static Set<String> validate(List<String> clear, Record body,
                                       List<String> clearable, List<List<String>> pairs) {
        if (clear == null || clear.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String raw : clear) {
            String name = raw == null ? "" : raw.trim();
            if (!clearable.contains(name)) {
                throw ApiException.invalidField("clear", "\"" + abbreviate(name) + "\" cannot be cleared. Clearable: "
                        + String.join(", ", clearable) + ".");
            }
            names.add(name);
        }
        for (List<String> pair : pairs) {
            if (names.contains(pair.get(0)) != names.contains(pair.get(1))) {
                throw ApiException.invalidField("clear", pair.get(0) + " and " + pair.get(1) + " are cleared together.");
            }
        }
        for (String set : setFields(body)) {
            if (names.contains(set)) {
                throw ApiException.invalidField("clear", set + " is both set and cleared.");
            }
        }
        return Set.copyOf(names);
    }

    /** The body's components that carry a value, {@code clear} itself excluded. */
    static Set<String> setFields(Record body) {
        Set<String> set = new HashSet<>();
        for (RecordComponent component : body.getClass().getRecordComponents()) {
            if ("clear".equals(component.getName())) {
                continue;
            }
            try {
                if (component.getAccessor().invoke(body) != null) {
                    set.add(component.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + component.getName(), e);
            }
        }
        return set;
    }

    private static String abbreviate(String name) {
        return name.length() <= 64 ? name : name.substring(0, 64) + "...";
    }
}
