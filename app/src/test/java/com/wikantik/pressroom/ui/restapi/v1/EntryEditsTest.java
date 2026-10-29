package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblem;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of an update's {@code edits} (spec 2026-10-24, section 3). */
class EntryEditsTest {

    private final FieldErrors errors = new FieldErrors();

    private static EntryDtos.TextEdit edit(String find, String replace) {
        return new EntryDtos.TextEdit(find, replace);
    }

    private String resolve(String storedText, EntryDtos.TextEdit... edits) {
        return EntryEdits.resolve(errors, null, Arrays.asList(edits), storedText);
    }

    private List<ApiProblem.FieldError> refused() {
        return assertThrows(ApiException.class, errors::throwIfAny).getFieldErrors();
    }

    private static List<String> fields(List<ApiProblem.FieldError> found) {
        return found.stream().map(ApiProblem.FieldError::field).toList();
    }

    @Test
    void editsApplyInOrderEachToTheTextThePreviousOnesLeft() {
        assertEquals("12 three", resolve("one two three", edit("one", "1"), edit("1 two", "12")));
        assertTrue(errors.isEmpty());
    }

    @Test
    void anEmptyReplaceDeletesTheTextFound() {
        assertEquals("one three", resolve("one two three", edit(" two", "")));
        assertTrue(errors.isEmpty());
    }

    @Test
    void everyFailingEditIsReportedOnItsOwnIndex() {
        resolve("ok a a", edit("missing", "x"), edit("ok", "fine"), edit("a", "b"));

        List<ApiProblem.FieldError> found = refused();
        assertEquals(List.of("edits[0]", "edits[2]"), fields(found));
        assertEquals("edits[0] not found in the entry's text.", found.get(0).message());
        assertEquals("edits[2] found 2 times; include more surrounding text so it matches once.",
                found.get(1).message());
    }

    /** "aa" in "aaa" could mean either place, so it does not match once. */
    @Test
    void overlappingMatchesCountAsSeparateMatches() {
        resolve("aaa", edit("aa", "b"));

        assertEquals("edits[0] found 2 times; include more surrounding text so it matches once.",
                refused().get(0).message());
    }

    @Test
    void anEditCanMatchWhatAnEarlierEditWrote() {
        assertEquals("c", resolve("a", edit("a", "b"), edit("b", "c")));
        assertTrue(errors.isEmpty());
    }

    @Test
    void anEmptyOrMissingFindIsRefused() {
        resolve("text", edit("", "x"), edit(null, "x"));

        assertEquals(List.of("edits[0]", "edits[1]"), fields(refused()));
    }

    /** A missing replace is refused rather than read as a deletion: deleting is "". */
    @Test
    void aMissingReplaceIsRefusedNotReadAsADeletion() {
        resolve("text", edit("text", null));

        List<ApiProblem.FieldError> found = refused();
        assertEquals(List.of("edits[0]"), fields(found));
        assertTrue(found.get(0).message().contains("\"\""), found.get(0).message());
    }

    @Test
    void aNullEditIsRefused() {
        resolve("text", (EntryDtos.TextEdit) null);

        assertEquals(List.of("edits[0]"), fields(refused()));
    }

    @Test
    void fiftyEditsAreAcceptedAndFiftyOneAreNot() {
        List<EntryDtos.TextEdit> fifty = new ArrayList<>(Collections.nCopies(EntryEdits.MAX_EDITS, edit("a", "a")));
        assertEquals("a", EntryEdits.resolve(errors, null, fifty, "a"));
        assertTrue(errors.isEmpty());

        fifty.add(edit("a", "a"));
        EntryEdits.resolve(errors, null, fifty, "a");
        List<ApiProblem.FieldError> found = refused();
        assertEquals(List.of("edits"), fields(found));
        assertTrue(found.get(0).message().contains("50"), found.get(0).message());
    }

    @Test
    void editsWithTextAreRefusedOnEdits() {
        EntryEdits.resolve(errors, "sent", List.of(edit("a", "b")), "a");

        assertEquals(List.of("edits"), fields(refused()));
    }

    @Test
    void noEditsMeansTheSentTextAsItIs() {
        assertEquals("sent", EntryEdits.resolve(errors, "sent", null, "stored"));
        assertEquals("sent", EntryEdits.resolve(errors, "sent", List.of(), "stored"));
        assertTrue(errors.isEmpty());
        assertFalse(EntryEdits.sent(null));
        assertFalse(EntryEdits.sent(List.of()));
        assertTrue(EntryEdits.sent(List.of(edit("a", "b"))));
    }

    /** A stored text of null (never written) is empty, so nothing matches in it. */
    @Test
    void aNullStoredTextIsEmpty() {
        resolve(null, edit("x", "y"));

        assertEquals("edits[0] not found in the entry's text.", refused().get(0).message());
    }
}
