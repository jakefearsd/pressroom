package com.wikantik.pressroom.ui.restapi.dto;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClearFieldsTest {

    public record Body(String about, Double lat, Double lng, List<String> clear) { }

    private static final List<String> CLEARABLE = List.of("about", "lat", "lng");
    private static final List<List<String>> PAIRS = List.of(List.of("lat", "lng"));

    private static Set<String> validate(Body body) {
        return ClearFields.validate(body.clear(), body, CLEARABLE, PAIRS);
    }

    private static String refusal(Body body) {
        ApiException e = assertThrows(ApiException.class, () -> validate(body));
        assertEquals(400, e.getStatus());
        return e.getMessage();
    }

    @Test
    void nullOrEmptyClearsNothing() {
        assertEquals(Set.of(), validate(new Body("x", null, null, null)));
        assertEquals(Set.of(), validate(new Body("x", null, null, List.of())));
    }

    @Test
    void aFieldSentAsNullAndClearedIsCleared() {
        // null is "absent", so naming the field in clear is not "both set and cleared"
        assertEquals(Set.of("about"), validate(new Body(null, null, null, List.of("about"))));
    }

    @Test
    void duplicatesAndSpacesAreTolerated() {
        assertEquals(Set.of("about"), validate(new Body(null, null, null, List.of("about", " about "))));
    }

    @Test
    void aNameThatCannotBeClearedIsRefusedWithTheClearableList() {
        assertTrue(refusal(new Body(null, null, null, List.of("title"))).contains("Clearable: about, lat, lng."));
    }

    @Test
    void aNullNameIsRefusedNotAnNpe() {
        refusal(new Body(null, null, null, Arrays.asList((String) null)));
    }

    @Test
    void halfAPairIsRefused() {
        assertEquals("lat and lng are cleared together.", refusal(new Body(null, null, null, List.of("lat"))));
    }

    @Test
    void aFieldBothSetAndClearedIsRefused() {
        assertEquals("about is both set and cleared.", refusal(new Body("x", null, null, List.of("about"))));
    }

    @Test
    void aWholePairClears() {
        assertEquals(Set.of("lat", "lng"), validate(new Body(null, null, null, List.of("lat", "lng"))));
    }

    @Test
    void everyRefusalNamesTheClearField() {
        record Body(String a, String b, List<String> clear) { }
        for (List<String> clear : List.of(List.of("nope"), List.of("a"))) {
            ApiException ex = assertThrows(ApiException.class, () -> ClearFields.validate(clear,
                    new Body(null, null, clear), List.of("a", "b"), List.of(List.of("a", "b"))));
            assertEquals("clear", ex.getFieldErrors().get(0).field());
        }
        ApiException both = assertThrows(ApiException.class, () -> ClearFields.validate(List.of("a", "b"),
                new Body("set", null, List.of("a", "b")), List.of("a", "b"), List.of()));
        assertEquals("clear", both.getFieldErrors().get(0).field());
    }
}
