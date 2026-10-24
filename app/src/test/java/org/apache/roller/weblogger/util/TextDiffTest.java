/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.util;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.roller.weblogger.util.TextDiff.Kind;
import org.apache.roller.weblogger.util.TextDiff.Line;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line diff behind the entry revision view.
 */
class TextDiffTest {

    @Test
    void identicalTextProducesNoDiffAtAll() {
        assertTrue(TextDiff.diff("one\ntwo", "one\ntwo").isEmpty(),
                "an unchanged entry must not render a diff full of unchanged lines");
    }

    @Test
    void aChangedLineShowsAsARemovalFollowedByAnAddition() {
        List<Line> diff = TextDiff.diff("keep\nold line\nkeep too", "keep\nnew line\nkeep too");

        assertEquals(List.of(
                new Line(Kind.SAME, "keep"),
                new Line(Kind.REMOVED, "old line"),
                new Line(Kind.ADDED, "new line"),
                new Line(Kind.SAME, "keep too")), diff);
    }

    @Test
    void anInsertedLineIsTheOnlyChange() {
        List<Line> diff = TextDiff.diff("first\nthird", "first\nsecond\nthird");

        assertEquals(List.of(
                new Line(Kind.SAME, "first"),
                new Line(Kind.ADDED, "second"),
                new Line(Kind.SAME, "third")), diff);
    }

    @Test
    void aDeletedLineIsTheOnlyChange() {
        List<Line> diff = TextDiff.diff("first\nsecond\nthird", "first\nthird");

        assertEquals(List.of(
                new Line(Kind.SAME, "first"),
                new Line(Kind.REMOVED, "second"),
                new Line(Kind.SAME, "third")), diff);
    }

    /**
     * A revision of an entry that never had a summary is the common case for
     * the summary pane, and null must read as "added" rather than throwing.
     */
    @Test
    void nullIsTreatedAsEmpty() {
        assertEquals(List.of(new Line(Kind.ADDED, "written")), TextDiff.diff(null, "written"));
        assertEquals(List.of(new Line(Kind.REMOVED, "deleted")), TextDiff.diff("deleted", null));
        assertTrue(TextDiff.diff(null, null).isEmpty());
    }

    @Test
    void lineEndingsDoNotThemselvesCountAsChanges() {
        assertTrue(TextDiff.diff("a\r\nb", "a\nb").isEmpty(),
                "a file saved on another platform must not read as entirely rewritten");
    }

    /**
     * The guard that keeps a pathological entry from allocating an
     * n-by-m table while its author waits: past the cap the diff degrades to
     * "all of it went, all of this arrived", which is still true, just coarse.
     */
    @Test
    void oversizedInputDegradesToAWholesaleReplacement() {
        String big = "line\n".repeat(TextDiff.MAX_LINES + 1);
        List<Line> diff = TextDiff.diff(big, "one line");

        assertTrue(diff.size() > TextDiff.MAX_LINES);
        assertTrue(diff.stream().noneMatch(line -> line.kind() == Kind.SAME),
                "the degraded form makes no claim about which lines survived");
        assertEquals("one line", diff.get(diff.size() - 1).text());
        assertEquals(Kind.ADDED, diff.get(diff.size() - 1).kind());
    }

    @Test
    void aWhollyDifferentTextHasNothingInCommon() {
        String rendered = TextDiff.diff("alpha\nbeta", "gamma\ndelta").stream()
                .map(line -> line.kind() + ":" + line.text())
                .collect(Collectors.joining(" "));

        assertEquals("REMOVED:alpha REMOVED:beta ADDED:gamma ADDED:delta", rendered);
    }

    // ------------------------------------------------------------------
    // unified(): the API's rendering of the same diff (spec 2026-10-24, §5)
    // ------------------------------------------------------------------

    /** Lines l1..ln joined by newlines, with no trailing newline. */
    private static String numbered(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> "l" + i)
                .collect(Collectors.joining("\n"));
    }

    private static String changing(String text, int line, String replacement) {
        List<String> lines = new java.util.ArrayList<>(List.of(text.split("\n")));
        lines.set(line - 1, replacement);
        return String.join("\n", lines);
    }

    @Test
    void identicalTextIsAnEmptyUnifiedDiff() {
        assertEquals("", TextDiff.unified("one\ntwo", "one\ntwo", "a", "b"));
        assertEquals("", TextDiff.unified(null, "", "a", "b"));
    }

    @Test
    void aChangedLineIsOneHunkWithThreeLinesOfContextEachSide() {
        String before = numbered(10);

        assertEquals("""
                --- revision r1
                +++ current
                @@ -2,7 +2,7 @@
                 l2
                 l3
                 l4
                -l5
                +five changed
                 l6
                 l7
                 l8
                """, TextDiff.unified(before, changing(before, 5, "five changed"), "revision r1", "current"));
    }

    @Test
    void changesFarApartAreSeparateHunks() {
        String before = numbered(20);
        String after = changing(changing(before, 3, "x3"), 17, "x17");

        assertEquals("""
                --- a
                +++ b
                @@ -1,6 +1,6 @@
                 l1
                 l2
                -l3
                +x3
                 l4
                 l5
                 l6
                @@ -14,7 +14,7 @@
                 l14
                 l15
                 l16
                -l17
                +x17
                 l18
                 l19
                 l20
                """, TextDiff.unified(before, after, "a", "b"));
    }

    /**
     * Two changes separated by at most twice the context share one hunk, as
     * GNU diff -u joins them; one line further apart, they are two.
     */
    @Test
    void changesWithinTwiceTheContextShareAHunk() {
        String before = numbered(20);

        String joined = TextDiff.unified(before, changing(changing(before, 3, "x3"), 10, "x10"), "a", "b");
        assertEquals(1, joined.lines().filter(l -> l.startsWith("@@")).count(), joined);
        assertTrue(joined.contains("@@ -1,13 +1,13 @@\n"), joined);

        String split = TextDiff.unified(before, changing(changing(before, 3, "x3"), 11, "x11"), "a", "b");
        assertEquals(2, split.lines().filter(l -> l.startsWith("@@")).count(), split);
    }

    /** An empty side is "line 0, no lines", and a one-line range drops its count. */
    @Test
    void emptyAndOneLineRangesAreWrittenAsDiffWritesThem() {
        assertEquals("--- a\n+++ b\n@@ -0,0 +1,2 @@\n+x\n+y\n", TextDiff.unified(null, "x\ny", "a", "b"));
        assertEquals("--- a\n+++ b\n@@ -1 +0,0 @@\n-x\n", TextDiff.unified("x", "", "a", "b"));
        assertEquals("--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n", TextDiff.unified("x", "y", "a", "b"));
    }

    @Test
    void theUnifiedDiffIsTheSameWhicheverLineEndingsTheTextsUse() {
        assertEquals(TextDiff.unified("a\nb\nc", "a\nB\nc", "x", "y"),
                TextDiff.unified("a\r\nb\r\nc", "a\nB\r\nc", "x", "y"));
    }

    /** Past MAX_LINES the unified form is still a well-formed diff: one hunk, everything replaced. */
    @Test
    void oversizedInputIsOneWholesaleHunk() {
        String big = "line\n".repeat(TextDiff.MAX_LINES + 1);

        String unified = TextDiff.unified(big, "one line", "a", "b");

        List<String> lines = unified.lines().toList();
        assertEquals("@@ -1," + (TextDiff.MAX_LINES + 2) + " +1 @@", lines.get(2));
        assertTrue(lines.stream().skip(3).noneMatch(l -> l.startsWith(" ")), "no line is claimed unchanged");
        assertEquals("+one line", lines.get(lines.size() - 1));
    }
}
