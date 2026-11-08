package com.wikantik.pressroom.ui.restapi;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.wikantik.pressroom.ui.controllers.editor.EntryBean;
import com.wikantik.pressroom.ui.controllers.editor.PageBean;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The field-parity gate (spec 2026-11-08 agent API parity, G1-G4): every
 * property of an admin form bean reaches the API body that mirrors it, under
 * its own name, an explicit rename, or an exclusion with a reason. The MCP
 * tools are generated from {@code /api/v1} and {@code McpCoverageTest} holds
 * every route to a tool, but nothing held a request body to its form, so the
 * page editor's SEO card never reached the API. A field added to a form now
 * fails here until the API body carries it or this table says why not.
 *
 * <p>A bean property is a public getter ({@code getX}/{@code isX}) with a
 * matching public one-argument setter: what a form binds. Read-only helpers
 * ({@code EntryBean.isDraft}) are not form fields.
 */
class FormBodyParityTest {

    /** One form and the body that mirrors it. Renames map a bean property to its body name. */
    record Parity(String name, Class<?> bean, Class<? extends Record> body,
                  Map<String, String> renames, Map<String, String> exclusions) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Parity> parities() {
        return Stream.of(
                new Parity("EntryBean -> EntryWrite", EntryBean.class, EntryDtos.EntryWrite.class,
                        Map.of("tagsAsString", "tags",
                                "categoryId", "category",
                                "pubTimeLocal", "pubTime",
                                "eventStartLocal", "eventStart",
                                "eventEndLocal", "eventEnd"),
                        Map.of("id", "The entry's id is the update route's path variable; create assigns one.",
                                "locale", "A hidden field the editor round-trips unchanged; no surface edits "
                                        + "an entry's locale.",
                                "pinnedToMain", "Roller's old pin to the main page, which the front page "
                                        + "ignores; promotion will be designed separately.")),
                new Parity("WeblogConfigBean -> WeblogPatch", WeblogConfigBean.class, AdminDtos.WeblogPatch.class,
                        Map.of(),
                        Map.of("handle", "The weblog's handle is the route's path variable and never changes.")),
                new Parity("PageBean -> PageWrite", PageBean.class, PageDtos.PageWrite.class,
                        Map.of("content", "text"),
                        Map.of("id", "The page's id is the update route's path variable; create assigns one.")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parities")
    void everyFormFieldReachesTheApiBody(Parity parity) {
        assertEquals(List.of(), missing(parity),
                parity + ": form properties with no body field, rename or exclusion");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parities")
    void noRenameOrExclusionIsStale(Parity parity) {
        assertEquals(List.of(), stale(parity), parity + ": table entries that name nothing live");
    }

    // ---------------------------------------------- the gate tests itself

    /** A form with one field the body lacks. */
    public static class FakeBean {
        private String kept;
        private String forgotten;

        public String getKept() {
            return kept;
        }

        public void setKept(String kept) {
            this.kept = kept;
        }

        public String getForgotten() {
            return forgotten;
        }

        public void setForgotten(String forgotten) {
            this.forgotten = forgotten;
        }

        /** Read-only: not a form field. */
        public boolean isReadOnly() {
            return true;
        }
    }

    record FakeBody(String kept) {
    }

    @Test
    void aFormFieldTheBodyLacksIsReported() {
        Parity parity = new Parity("fake", FakeBean.class, FakeBody.class, Map.of(), Map.of());
        assertEquals(List.of("forgotten"), missing(parity));
    }

    @Test
    void aStaleRenameOrExclusionIsReported() {
        Parity parity = new Parity("fake", FakeBean.class, FakeBody.class,
                Map.of("gone", "kept", "forgotten", "nowhere"),
                Map.of("vanished", "a property since removed", "kept", "but the body has it"));
        assertEquals(List.of(
                        "exclusion 'kept' names a field the body now carries",
                        "exclusion 'vanished' names no bean property",
                        "rename 'forgotten' -> 'nowhere' names no body field",
                        "rename 'gone' names no bean property"),
                stale(parity));
    }

    // ---------------------------------------------------------- the rules

    static List<String> missing(Parity parity) {
        Set<String> body = components(parity.body());
        return properties(parity.bean()).stream()
                .filter(p -> !parity.exclusions().containsKey(p))
                .filter(p -> !body.contains(parity.renames().getOrDefault(p, p)))
                .toList();
    }

    static List<String> stale(Parity parity) {
        Set<String> bean = properties(parity.bean());
        Set<String> body = components(parity.body());
        List<String> problems = new ArrayList<>();
        parity.renames().forEach((from, to) -> {
            if (!bean.contains(from)) {
                problems.add("rename '" + from + "' names no bean property");
            } else if (!body.contains(to)) {
                problems.add("rename '" + from + "' -> '" + to + "' names no body field");
            }
        });
        parity.exclusions().forEach((property, reason) -> {
            if (!bean.contains(property)) {
                problems.add("exclusion '" + property + "' names no bean property");
            } else if (body.contains(property)) {
                problems.add("exclusion '" + property + "' names a field the body now carries");
            }
        });
        problems.sort(null);
        return problems;
    }

    /** Properties with a public getter and a matching public one-argument setter, sorted. */
    static Set<String> properties(Class<?> bean) {
        Set<String> getters = new TreeSet<>();
        Set<String> setters = new TreeSet<>();
        for (Method m : bean.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
                continue;
            }
            String n = m.getName();
            if (m.getParameterCount() == 0 && n.startsWith("get") && n.length() > 3) {
                getters.add(decapitalize(n.substring(3)));
            } else if (m.getParameterCount() == 0 && n.startsWith("is") && n.length() > 2) {
                getters.add(decapitalize(n.substring(2)));
            } else if (m.getParameterCount() == 1 && n.startsWith("set") && n.length() > 3) {
                setters.add(decapitalize(n.substring(3)));
            }
        }
        getters.retainAll(setters);
        return getters;
    }

    static Set<String> components(Class<? extends Record> body) {
        return Arrays.stream(body.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static String decapitalize(String name) {
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }
}
