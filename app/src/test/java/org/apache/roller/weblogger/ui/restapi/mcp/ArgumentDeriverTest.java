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
}
