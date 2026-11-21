package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;

/**
 * An update's (or a preview's) {@code edits}: exact-text replacements on the
 * entry's stored text, applied in order, each to the text as the previous
 * ones left it (spec 2026-10-24, section 3). Exact strings, not patterns: a
 * pattern an agent gets subtly wrong edits the wrong place silently, and a
 * {@code find} that must occur exactly once proves each edit lands where it
 * was meant. Every edit that cannot apply is reported, on {@code edits[i]},
 * and skipped; the write is then refused with the other field problems, so
 * nothing is saved and nothing is imported.
 */
final class EntryEdits {

    /** Edits one request may carry. */
    static final int MAX_EDITS = 50;

    private EntryEdits() {
    }

    /** Whether a request carries edits: an empty list is none, as an empty {@code clear} is. */
    static boolean sent(List<EntryDtos.TextEdit> edits) {
        return edits != null && !edits.isEmpty();
    }

    /**
     * The text a write stores: {@code sentText} when there are no edits, else
     * {@code storedText} with the edits applied. Edits with text, or more than
     * {@link #MAX_EDITS}, are refused whole and leave the text as sent or stored.
     */
    static String resolve(FieldErrors errors, String sentText, List<EntryDtos.TextEdit> edits, String storedText) {
        if (!sent(edits)) {
            return sentText;
        }
        if (sentText != null) {
            errors.add("edits", ProblemCode.EDITS_WITH_TEXT);
            return sentText;
        }
        String text = storedText == null ? "" : storedText;
        if (edits.size() > MAX_EDITS) {
            errors.add("edits", ProblemCode.EDITS_TOO_MANY, Integer.toString(MAX_EDITS), Integer.toString(edits.size()));
            return text;
        }
        for (int i = 0; i < edits.size(); i++) {
            text = apply(errors, "edits[" + i + "]", text, edits.get(i));
        }
        return text;
    }

    private static String apply(FieldErrors errors, String field, String text, EntryDtos.TextEdit edit) {
        if (edit == null) {
            errors.add(field, ProblemCode.EDITS_EMPTY, field, "{find, replace}");
            return text;
        }
        if (edit.find() == null || edit.find().isEmpty()) {
            errors.add(field, ProblemCode.EDITS_NO_FIND, field);
            return text;
        }
        if (edit.replace() == null) {
            errors.add(field, ProblemCode.EDITS_NO_REPLACE, field);
            return text;
        }
        int at = text.indexOf(edit.find());
        if (at < 0) {
            errors.add(field, ProblemCode.EDITS_FIND_NOT_MATCHED, field);
            return text;
        }
        int matches = matches(text, edit.find(), at);
        if (matches > 1) {
            errors.add(field, ProblemCode.EDITS_AMBIGUOUS, field, Integer.toString(matches));
            return text;
        }
        return text.substring(0, at) + edit.replace() + text.substring(at + edit.find().length());
    }

    /**
     * Occurrences of {@code find} in {@code text} from the first, at {@code
     * first}. Overlapping ones count: "aa" in "aaa" could mean either place.
     */
    private static int matches(String text, String find, int first) {
        int count = 0;
        for (int at = first; at >= 0; at = text.indexOf(find, at + 1)) {
            count++;
        }
        return count;
    }
}
