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
import com.wikantik.pressroom.ui.controllers.admin.BusinessBean;
import com.wikantik.pressroom.ui.controllers.admin.CreateUserBean;
import com.wikantik.pressroom.ui.controllers.editor.EntryBean;
import com.wikantik.pressroom.ui.controllers.editor.PageBean;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The field-parity gate (spec 2026-11-08 agent API parity, G1-G4; re-anchored
 * by spec 2026-11-20 jsp-rules-and-view-models, Task 7). The anchor is
 * {@link EditableFieldManifest}, which names no form bean:
 * <ul>
 * <li>A (outlives the JSP): every manifest field is a component of the API
 * body, so an API field cannot be dropped silently.</li>
 * <li>B (outlives the JSP): every body component is a manifest field or an
 * API-only field with a reason, so an API field cannot be added without
 * deciding whether the screen offers it.</li>
 * <li>C (the JSP half; delete a row with its JSP): each form bean's
 * properties map onto the manifest through renames and exclusions, and every
 * manifest field is reached by some bean property.</li>
 * </ul>
 * A bean property is a public getter ({@code getX}/{@code isX}) with a
 * matching public one-argument setter: what a form binds. Read-only helpers
 * ({@code EntryBean.isDraft}) are not form fields.
 */
class FormBodyParityTest {

    /** The JSP half for one resource. Renames map a bean property to its manifest name. */
    record Parity(String name, Class<?> bean, EditableFieldManifest.Resource resource,
                  Map<String, String> renames, Map<String, String> exclusions) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Parity> parities() {
        return Stream.of(
                new Parity("EntryBean -> entry", EntryBean.class, EditableFieldManifest.entry(),
                        Map.of("tagsAsString", "tags",
                                "categoryId", "category",
                                "pubTimeLocal", "pubTime",
                                "eventStartLocal", "eventStart",
                                "eventEndLocal", "eventEnd",
                                "strandPageId", "strand"),
                        Map.of("id", "The entry's id is the update route's path variable; create assigns one.")),
                new Parity("WeblogConfigBean -> weblogSettings", WeblogConfigBean.class,
                        EditableFieldManifest.weblogSettings(),
                        Map.of("frontPageId", "frontPage"),
                        Map.of("handle", "The weblog's handle is the route's path variable and never changes.")),
                new Parity("PageBean -> page", PageBean.class, EditableFieldManifest.page(),
                        Map.of("content", "text"),
                        Map.of("id", "The page's id is the update route's path variable; create assigns one.",
                                "partIds", "The Parts panel's ordered list reaches the API as its own route, "
                                        + "PUT .../pages/{id}/parts (PartsWrite.entries), not the page body.",
                                "shownPartIds", "The rows the Parts panel was built with, so a browser form "
                                        + "keeps parts appended meanwhile; the API's set_parts states the "
                                        + "whole list instead.")),
                new Parity("CreateUserBean -> user", CreateUserBean.class, EditableFieldManifest.user(),
                        Map.of(),
                        Map.of("id", "The account is addressed by user name, the route's path variable.",
                                "userName", "The user name is the route's path variable and never changes.",
                                "list", "Not a field: the account list the screen binds beside the form.",
                                "password", "No API takes a password; a set-password link is mailed instead "
                                        + "(POST .../password-link).",
                                "passwordMode", "Chooses between a typed password and a mailed link on create; "
                                        + "the API always mails the link.",
                                "administrator", "The admin role is session-only (SA-18); no token may grant or "
                                        + "revoke it.")),
                new Parity("BusinessBean -> business", BusinessBean.class, EditableFieldManifest.business(),
                        Map.of(),
                        Map.of("id", "The business's id is the update route's path variable; create assigns "
                                + "one.")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.wikantik.pressroom.ui.restapi.EditableFieldManifest#resources")
    void everyManifestFieldIsABodyField(EditableFieldManifest.Resource resource) {
        assertEquals(List.of(), droppedFromBody(resource),
                resource + ": manifest fields the API body no longer carries");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.wikantik.pressroom.ui.restapi.EditableFieldManifest#resources")
    void everyBodyFieldIsDecided(EditableFieldManifest.Resource resource) {
        assertEquals(List.of(), undecided(resource),
                resource + ": body fields neither on the manifest nor API-only with a reason");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.wikantik.pressroom.ui.restapi.EditableFieldManifest#resources")
    void everyApiOnlyEntryIsABodyFieldOffTheManifest(EditableFieldManifest.Resource resource) {
        assertEquals(List.of(), badApiOnly(resource),
                resource + ": API-only entries that name no body field, or a field the screens also edit");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parities")
    void everyFormFieldReachesTheManifest(Parity parity) {
        assertEquals(List.of(), missing(parity),
                parity + ": form properties with no manifest field, rename or exclusion");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parities")
    void everyManifestFieldIsReachedByTheForm(Parity parity) {
        assertEquals(List.of(), unreached(parity), parity + ": manifest fields no form property reaches");
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

    private static final EditableFieldManifest.Resource FAKE = new EditableFieldManifest.Resource("fake",
            FakeBody.class, Set.of("kept"), Map.of());

    @Test
    void aFormFieldTheManifestLacksIsReported() {
        Parity parity = new Parity("fake", FakeBean.class, FAKE, Map.of(), Map.of());
        assertEquals(List.of("forgotten"), missing(parity));
    }

    @Test
    void aStaleRenameOrExclusionIsReported() {
        Parity parity = new Parity("fake", FakeBean.class, FAKE,
                Map.of("gone", "kept", "forgotten", "nowhere"),
                Map.of("vanished", "a property since removed", "kept", "but the body has it"));
        assertEquals(List.of(
                        "exclusion 'kept' names a field the body now carries",
                        "exclusion 'vanished' names no bean property",
                        "rename 'forgotten' -> 'nowhere' names no body field",
                        "rename 'gone' names no bean property"),
                stale(parity));
    }

    @Test
    void aManifestFieldMissingFromTheBodyFailsA() {
        var resource = new EditableFieldManifest.Resource("fake", FakeBody.class, Set.of("kept", "promised"),
                Map.of());
        assertEquals(List.of("promised"), droppedFromBody(resource));
    }

    @Test
    void anUnexplainedBodyFieldFailsB() {
        var resource = new EditableFieldManifest.Resource("fake", FakeBodyPlus.class, Set.of("kept"), Map.of());
        assertEquals(List.of("extra"), undecided(resource));
        var explained = new EditableFieldManifest.Resource("fake", FakeBodyPlus.class, Set.of("kept"),
                Map.of("extra", "an agent-only convenience"));
        assertEquals(List.of(), undecided(explained));
    }

    @Test
    void aBeanPropertyOutsideTheManifestAndAnUnreachedManifestFieldEachFailC() {
        var resource = new EditableFieldManifest.Resource("fake", FakeBody.class, Set.of("kept", "orphan"),
                Map.of());
        Parity parity = new Parity("fake", FakeBean.class, resource, Map.of(), Map.of());
        assertEquals(List.of("forgotten"), missing(parity));
        assertEquals(List.of("orphan"), unreached(parity));
    }

    @Test
    void droppingTheBeanRowLeavesAAndBRunningForThatResource() {
        var resource = new EditableFieldManifest.Resource("fake", FakeBodyPlus.class, Set.of("kept", "promised"),
                Map.of());
        // No Parity row exists for it (the JSP is gone): A and B still see the resource.
        assertEquals(List.of("promised"), droppedFromBody(resource));
        assertEquals(List.of("extra"), undecided(resource));
    }

    record FakeBodyPlus(String kept, String extra) {
    }

    @Test
    void anApiOnlyEntryNamingNoBodyFieldOrAManifestFieldIsReported() {
        var resource = new EditableFieldManifest.Resource("fake", FakeBodyPlus.class, Set.of("kept"),
                Map.of("extra", "an agent-only convenience", "kept", "also on the screens",
                        "ghost", "a component since removed"));
        assertEquals(List.of("api-only 'ghost' names no body field", "api-only 'kept' is also a manifest field"),
                badApiOnly(resource));
        var sound = new EditableFieldManifest.Resource("fake", FakeBodyPlus.class, Set.of("kept"),
                Map.of("extra", "an agent-only convenience"));
        assertEquals(List.of(), badApiOnly(sound));
    }

    // ---------------------------------------------------------- the rules

    static List<String> droppedFromBody(EditableFieldManifest.Resource resource) {
        Set<String> body = components(resource.body());
        return resource.fields().stream().filter(f -> !body.contains(f)).sorted().toList();
    }

    static List<String> undecided(EditableFieldManifest.Resource resource) {
        return components(resource.body()).stream()
                .filter(c -> !resource.fields().contains(c) && !resource.apiOnly().containsKey(c))
                .sorted().toList();
    }

    static List<String> badApiOnly(EditableFieldManifest.Resource resource) {
        Set<String> body = components(resource.body());
        List<String> problems = new ArrayList<>();
        resource.apiOnly().keySet().forEach(name -> {
            if (!body.contains(name)) {
                problems.add("api-only '" + name + "' names no body field");
            } else if (resource.fields().contains(name)) {
                problems.add("api-only '" + name + "' is also a manifest field");
            }
        });
        problems.sort(null);
        return problems;
    }

    static List<String> missing(Parity parity) {
        Set<String> fields = parity.resource().fields();
        return properties(parity.bean()).stream()
                .filter(p -> !parity.exclusions().containsKey(p))
                .filter(p -> !fields.contains(parity.renames().getOrDefault(p, p)))
                .toList();
    }

    static List<String> unreached(Parity parity) {
        Set<String> reached = properties(parity.bean()).stream()
                .filter(p -> !parity.exclusions().containsKey(p))
                .map(p -> parity.renames().getOrDefault(p, p)).collect(Collectors.toSet());
        return parity.resource().fields().stream().filter(f -> !reached.contains(f)).sorted().toList();
    }

    static List<String> stale(Parity parity) {
        Set<String> bean = properties(parity.bean());
        Set<String> body = components(parity.resource().body());
        Set<String> fields = parity.resource().fields();
        List<String> problems = new ArrayList<>();
        parity.renames().forEach((from, to) -> {
            if (!bean.contains(from)) {
                problems.add("rename '" + from + "' names no bean property");
            } else if (!fields.contains(to)) {
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
