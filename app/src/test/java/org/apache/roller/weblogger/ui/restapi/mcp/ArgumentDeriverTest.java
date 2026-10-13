package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentDeriverTest {

    private static final ApiRouteTable TABLE = ApiRouteTables.real();

    private static Map<String, ArgumentSpec> args(String key) {
        return ArgumentDeriver.derive(TABLE.find(key).orElseThrow()).stream()
                .collect(Collectors.toMap(ArgumentSpec::name, a -> a));
    }

    @Test
    void entryCreateTakesTheWeblogAndEveryEntryWriteFieldIncludingTheLocation() {
        Map<String, ArgumentSpec> a = args("POST /v1/weblogs/{handle}/entries");

        assertEquals(ArgumentSpec.Location.PATH, a.get("weblog").location());
        assertEquals("handle", a.get("weblog").sourceName());
        assertTrue(a.get("weblog").required());
        for (String field : List.of("title", "text", "status", "jsonLdType", "geoLatitude", "geoLongitude", "tags")) {
            assertEquals(ArgumentSpec.Location.BODY, a.get(field).location(), field);
            assertFalse(a.get(field).required(), field);
        }
        assertEquals("number", a.get("geoLatitude").schema().get("type"));
        assertEquals("array", a.get("tags").schema().get("type"));
        assertEquals(Map.of("type", "string"), a.get("tags").schema().get("items"));
        assertFalse(a.containsKey("id"));
        assertTrue(ArgumentDeriver.takesBody(TABLE.find("POST /v1/weblogs/{handle}/entries").orElseThrow()));
    }

    @Test
    void entryListTakesItsQueryParametersWithTheirTypes() {
        Map<String, ArgumentSpec> a = args("GET /v1/weblogs/{handle}/entries");

        assertEquals(ArgumentSpec.Location.QUERY, a.get("status").location());
        assertEquals("array", a.get("tags").schema().get("type"));
        assertEquals("integer", a.get("limit").schema().get("type"));
        assertFalse(a.get("limit").required(), "limit has a default");
        assertFalse(ArgumentDeriver.takesBody(TABLE.find("GET /v1/weblogs/{handle}/entries").orElseThrow()));
    }

    @Test
    void theConfigPatchBodyIsOneSettingsObject() {
        Map<String, ArgumentSpec> a = args("PATCH /v1/admin/config");

        assertEquals(ArgumentSpec.Location.BODY_MAP, a.get("settings").location());
        assertEquals("object", a.get("settings").schema().get("type"));
        assertTrue(a.get("settings").required());
    }

    @Test
    void pathVariablesOtherThanHandleKeepTheirNames() {
        assertEquals(ArgumentSpec.Location.PATH, args("PATCH /v1/admin/users/{userName}").get("userName").location());
        assertEquals(ArgumentSpec.Location.QUERY,
                args("DELETE /v1/weblogs/{handle}/categories/{id}").get("moveTo").location());
    }

    @Test
    void anArgumentsDescriptionIsTheApiTypesOwnSchemaDescription() throws Exception {
        Schema onComponent = EntryDtos.EntryWrite.class.getMethod("pubTime").getAnnotation(Schema.class);
        assertNotNull(onComponent, "EntryWrite.pubTime carries springdoc's @Schema description");

        assertEquals(onComponent.description(),
                args("POST /v1/weblogs/{handle}/entries").get("pubTime").schema().get("description"));
    }

    // ---------------------------------------------------------- fixtures

    record Point(Double x, Double y) { }

    record ThreePoint(Double x, Double y, Double z) { }

    static final class Fixture {
        public void points(@RequestBody Point body) { }

        public void threePoints(@RequestBody ThreePoint body) { }

        public void unknown(@RequestParam("when") java.time.Duration when) { }

        public void twice(@PathVariable("id") String id, @RequestParam("id") String alsoId) { }
    }

    private static ApiRoute fixture(String method, Class<?>... types) throws Exception {
        return new ApiRoute("POST", "/v1/fixture",
                new HandlerMethod(new Fixture(), Fixture.class.getMethod(method, types)));
    }

    @Test
    void aBodyTypeGainingAComponentGainsAnArgument() throws Exception {
        assertEquals(List.of("x", "y"),
                ArgumentDeriver.derive(fixture("points", Point.class)).stream().map(ArgumentSpec::name).toList());
        assertEquals(List.of("x", "y", "z"),
                ArgumentDeriver.derive(fixture("threePoints", ThreePoint.class)).stream()
                        .map(ArgumentSpec::name).toList());
    }

    @Test
    void anUnknownJavaTypeStopsStartup() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArgumentDeriver.derive(fixture("unknown", java.time.Duration.class)));
        assertTrue(ex.getMessage().contains("Duration"), ex.getMessage());
    }

    @Test
    void anArgumentNameUsedTwiceStopsStartup() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArgumentDeriver.derive(fixture("twice", String.class, String.class)));
        assertTrue(ex.getMessage().contains("id"), ex.getMessage());
    }

    // ---------------------------------------------------- derivation branches

    record Inner(String label, Integer count) { }

    record Wide(String name, Boolean flag, long size, float ratio, String[] names, List<Inner> inners,
            Inner inner) { }

    static final class Plain { }

    static final class Branches {
        public void infrastructure(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response, java.security.Principal who,
                java.util.Locale locale, @org.springframework.web.bind.annotation.RequestAttribute("a") Object a) { }

        public void unbound(Object stray) { }

        public void nonRecord(@RequestBody Plain body) { }

        public void mapBody(@RequestBody Map<String, String> body) { }

        public void wide(@RequestBody Wide body) { }

        public void queries(
                @RequestParam("q") @io.swagger.v3.oas.annotations.Parameter(description = "Search text") String q,
                @RequestParam(name = "page", required = false) Integer page,
                @RequestParam(name = "size", defaultValue = "10") int size,
                @RequestParam("wanted") boolean wanted) { }

        public void namedPath(@PathVariable(name = "slug") String slug) { }

        public void unnamedPath(@PathVariable String slug) { }

        public void noPathBinding() { }
    }

    private static ApiRoute branch(String template, String method, Class<?>... types) throws Exception {
        return new ApiRoute("GET", template, new HandlerMethod(new Branches(), Branches.class.getMethod(method, types)));
    }

    private static Map<String, ArgumentSpec> byName(ApiRoute route) {
        return ArgumentDeriver.derive(route).stream().collect(Collectors.toMap(ArgumentSpec::name, a -> a));
    }

    @Test
    void servletInfrastructureParametersAreNotArguments() throws Exception {
        ApiRoute route = branch("/v1/x", "infrastructure", jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class, java.security.Principal.class,
                java.util.Locale.class, Object.class);

        assertTrue(ArgumentDeriver.derive(route).isEmpty());
    }

    @Test
    void aParameterNothingBindsStopsStartupNamingItsPosition() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArgumentDeriver.derive(branch("/v1/x", "unbound", Object.class)));

        assertTrue(ex.getMessage().contains("parameter 0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GET /v1/x"), ex.getMessage());
    }

    @Test
    void aBodyThatIsNotARecordOrMapStopsStartup() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArgumentDeriver.derive(branch("/v1/x", "nonRecord", Plain.class)));

        assertTrue(ex.getMessage().contains("not a record"), ex.getMessage());
    }

    @Test
    void aMapBodyIsOneRequiredSettingsObject() throws Exception {
        ApiRoute route = branch("/v1/x", "mapBody", Map.class);

        ArgumentSpec settings = byName(route).get("settings");

        assertEquals(ArgumentSpec.Location.BODY_MAP, settings.location());
        assertTrue(settings.required());
        assertTrue(ArgumentDeriver.takesBody(route));
    }

    @Test
    void everyJavaTypeAnArgumentCanHaveMapsToItsJsonType() throws Exception {
        Map<String, ArgumentSpec> a = byName(branch("/v1/x", "wide", Wide.class));

        assertEquals("string", a.get("name").schema().get("type"));
        assertEquals("boolean", a.get("flag").schema().get("type"));
        assertEquals("integer", a.get("size").schema().get("type"));
        assertEquals("number", a.get("ratio").schema().get("type"));
        assertEquals(Map.of("type", "array", "items", Map.of("type", "string")), a.get("names").schema());
        assertEquals("array", a.get("inners").schema().get("type"));
        assertEquals("object", ((Map<?, ?>) a.get("inners").schema().get("items")).get("type"));
        Map<?, ?> properties = (Map<?, ?>) a.get("inner").schema().get("properties");
        assertEquals(java.util.Set.of("label", "count"), properties.keySet());
    }

    @Test
    void queryParametersCarryDescriptionAndRequirednessFromTheirAnnotations() throws Exception {
        Map<String, ArgumentSpec> a = byName(branch("/v1/x", "queries", String.class, Integer.class, int.class,
                boolean.class));

        assertEquals("Search text", a.get("q").schema().get("description"));
        assertTrue(a.get("q").required());
        assertFalse(a.get("page").required(), "required=false");
        assertFalse(a.get("size").required(), "a default value makes it optional");
        assertFalse(a.get("wanted").schema().containsKey("description"));
        assertEquals("boolean", a.get("wanted").schema().get("type"));
    }

    @Test
    void aPathVariableNamedThroughNameIsAnArgumentAndTheTemplateVariablesAreAddedOnce() throws Exception {
        Map<String, ArgumentSpec> a = byName(branch("/v1/{handle}/things/{slug}", "namedPath", String.class));

        assertEquals(java.util.Set.of("weblog", "slug"), a.keySet());
        assertEquals("handle", a.get("weblog").sourceName());
        assertTrue(a.get("weblog").schema().get("description").toString().contains("handle"));
        assertEquals(ArgumentSpec.Location.PATH, a.get("slug").location());
    }

    @Test
    void aTemplateVariableTheHandlerNeverBindsIsStillAnArgument() throws Exception {
        Map<String, ArgumentSpec> a = byName(branch("/v1/{handle}/ping", "noPathBinding"));

        assertEquals(java.util.Set.of("weblog"), a.keySet());
        assertFalse(ArgumentDeriver.takesBody(branch("/v1/{handle}/ping", "noPathBinding")));
    }

    @Test
    void anUnnamedPathVariableStopsStartupBecauseTheBuildHasNoParametersFlag() throws Exception {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArgumentDeriver.derive(branch("/v1/{slug}", "unnamedPath", String.class)));

        assertTrue(ex.getMessage().contains("unnamed"), ex.getMessage());
    }
}
