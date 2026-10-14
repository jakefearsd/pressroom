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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code docs/design/} -- the committed preview cards for the
 * "Quiet Instrument" design system -- stays whole and stays described.
 *
 * <p>These cards spent their first life in a companion claude.ai/design
 * project and were only mirrored into the repo for the themes and the
 * editor, which left the spec text and the visual source of record in two
 * places that could disagree with nobody noticing. They all live here now
 * and this test is what keeps that true, guarding three failure modes:
 * <ul>
 *   <li>a card loses (or never had) its first-line {@code @dsCard} marker
 *       -- the marker is what the design pane builds its index from, so an
 *       unmarked file is a card that silently stops appearing;</li>
 *   <li>a card is added to the directory but never listed in
 *       {@code design-system.md}, or listed there and never added -- the
 *       exact drift that made the spec's "files to produce" section stale
 *       while every one of those files already existed;</li>
 *   <li>a card reaches out to the network. Cards must render from a
 *       checkout with no build step and no server, which is the property
 *       that lets the repo be the source of record at all.</li>
 * </ul>
 */
public class DesignCardsTest {

    private static final Path DESIGN_DIR = Paths.get("../docs/design");
    private static final Path SPEC = DESIGN_DIR.resolve("design-system.md");

    /** First line of every card, e.g. {@code <!-- @dsCard group="Forms" name="..." subtitle="..." -->}. */
    private static final Pattern DS_CARD =
            Pattern.compile("^<!-- @dsCard group=\"([^\"]+)\" name=\"([^\"]+)\" subtitle=\"([^\"]*)\" -->$");

    /** src/href pointing at another origin -- the one thing a self-contained card may not do. */
    private static final Pattern EXTERNAL_REF =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"'](?:https?:)?//", Pattern.CASE_INSENSITIVE);

    private static List<Path> cards() throws IOException {
        try (Stream<Path> files = Files.walk(DESIGN_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
        }
    }

    @Test
    void thereAreCardsToCheckAtAll() throws IOException {
        // Without this, every other assertion here passes vacuously if the
        // directory is ever moved or emptied.
        // 18 since the comments-moderation card was withdrawn (it was drawn
        // against Comments.jsp, and the comment subsystem was removed in
        // W1), +1 for editor-markdown-surface.html (Task A10).
        // +3 for the agent-accounts cards (tables-users, forms-agent-page,
        // forms-token-reveal; spec 2026-10-13, section 7).
        assertEquals(22, cards().size(),
                "expected the full committed card set; found: " + cards());
    }

    @Test
    void everyCardOpensWithItsDsCardMarker() throws IOException {
        for (Path card : cards()) {
            List<String> lines = Files.readAllLines(card, StandardCharsets.UTF_8);
            assertFalse(lines.isEmpty(), card + " is empty");
            Matcher m = DS_CARD.matcher(lines.get(0).trim());
            assertTrue(m.matches(),
                    card + " must open with a @dsCard marker (group/name/subtitle), got: "
                            + lines.get(0));
            assertFalse(m.group(2).isBlank(), card + " has a blank card name");
        }
    }

    @Test
    void everyCardIsSelfContained() throws IOException {
        for (Path card : cards()) {
            String html = Files.readString(card, StandardCharsets.UTF_8);
            Matcher m = EXTERNAL_REF.matcher(html);
            assertFalse(m.find(),
                    card + " references an external origin (" + (m.hitEnd() ? "" : m.group())
                            + "); cards must render offline from a checkout");
        }
    }

    @Test
    void theSpecListsExactlyTheCardsThatExist() throws IOException {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);

        Set<String> onDisk = new TreeSet<>();
        for (Path card : cards()) {
            onDisk.add(card.getFileName().toString());
        }

        Set<String> missingFromSpec = new TreeSet<>();
        for (String name : onDisk) {
            if (!spec.contains(name)) {
                missingFromSpec.add(name);
            }
        }
        assertTrue(missingFromSpec.isEmpty(),
                "these cards exist but design-system.md never mentions them: " + missingFromSpec
                        + " -- add them to its card list in the same commit");

        // The other direction: a filename the spec names that nobody ships.
        Set<String> namedBySpec = new TreeSet<>();
        Matcher m = Pattern.compile("([a-z0-9-]+\\.html)").matcher(spec);
        while (m.find()) {
            namedBySpec.add(m.group(1));
        }
        namedBySpec.removeAll(onDisk);
        assertTrue(namedBySpec.isEmpty(),
                "design-system.md names cards that do not exist: " + namedBySpec);
    }

    @Test
    void theReadmeIndexPointsAtEveryGroupDirectory() throws IOException {
        String readme = Files.readString(DESIGN_DIR.resolve("README.md"), StandardCharsets.UTF_8);
        Set<String> groupDirs = new TreeSet<>();
        for (Path card : cards()) {
            Path parent = DESIGN_DIR.relativize(card).getParent();
            if (parent != null) {
                groupDirs.add(parent.toString());
            }
        }
        for (String dir : groupDirs) {
            assertTrue(readme.contains(dir + "/"),
                    "docs/design/README.md does not link the " + dir + "/ group");
        }
    }
}
