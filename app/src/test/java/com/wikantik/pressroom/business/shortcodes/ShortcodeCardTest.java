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
package com.wikantik.pressroom.business.shortcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the editor's insert menu honest about the shortcodes that actually
 * render.
 *
 * <p>The menu is generated from the handler registry, so the two cannot drift
 * by construction -- {@link ShortcodeHandler#getCard()} is abstract, and a
 * sixth shortcode does not compile without a card. What still can drift, and
 * is what these tests hold, is the content of the cards: a snippet whose
 * syntax the expander does not recognise, or a label key with no bundle entry,
 * would offer an author a menu item that quietly does nothing.
 */
class ShortcodeCardTest {

    private static final String BUNDLE = "/ApplicationResources.properties";

    private final List<ShortcodeCard> cards = BuiltInExpanders.withMocks().cards();

    @Test
    void everyShippedShortcodeIsOfferedExactlyOnce() {
        assertEquals(List.of("image", "gallery", "map", "cta", "faq", "video", "contact",
                        "subscribe"),
                cards.stream().map(ShortcodeCard::name).toList(),
                "The insert menu is the registry's card list; if a shortcode was added "
                        + "or removed, update this expectation deliberately.");
    }

    @Test
    void everyCardNamesTheHandlerItInserts() {
        for (ShortcodeCard card : cards) {
            assertNotNull(card.name(), "card with no name");
            assertFalse(card.labelKey().isBlank(), card.name() + " has no label key");
        }
    }

    /**
     * A label key that resolves to nothing renders as the raw key in the menu
     * ("shortcode.faq.label" instead of a name), which looks broken and is
     * easy to miss when the menu item is one of five.
     */
    @Test
    void everyCardsLabelKeyExistsInTheBundle() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = getClass().getResourceAsStream(BUNDLE)) {
            assertNotNull(in, BUNDLE + " not found on the test classpath");
            bundle.load(in);
        }
        for (ShortcodeCard card : cards) {
            assertTrue(bundle.containsKey(card.labelKey()),
                    "No bundle entry for " + card.labelKey() + " (card " + card.name() + ")");
        }
    }

    /**
     * The whole point of a snippet: an author picks the menu item and gets
     * text the expander will recognise. A stub registry stands in for the real
     * handlers so this stays a syntax test -- no weblog, no media files, no
     * database -- and every shortcode in the snippet must be consumed, which
     * covers the child tags ([pin], [q], [a]) the real handlers parse out of
     * their bodies.
     */
    @Test
    void everySnippetParsesAsTheShortcodeItClaimsToBe() {
        for (ShortcodeCard card : cards) {
            if (card.usesMediaChooser()) {
                assertTrue(card.snippet().isEmpty(),
                        card.name() + " opens the chooser, so its snippet is unused and "
                                + "should be empty rather than misleading");
                continue;
            }
            List<String> seen = new ArrayList<>();
            String expanded = recordingExpander(card.name(), seen)
                    .expand(new WeblogEntry(), card.snippet());

            assertEquals(List.of(card.name()), seen,
                    card.name() + "'s snippet did not reach its handler; the expander "
                            + "saw: " + card.snippet());
            assertFalse(expanded.contains("["),
                    card.name() + "'s snippet left unparsed shortcode text behind: " + expanded);
        }
    }

    /**
     * Snippets travel to the browser inside an HTML data attribute. Keeping
     * them free of markup characters is what lets the JSP hand them over with
     * ordinary escaping and no JavaScript string assembly.
     */
    @Test
    void noSnippetCarriesMarkupCharacters() {
        for (ShortcodeCard card : cards) {
            assertFalse(card.snippet().contains("<") || card.snippet().contains("&"),
                    card.name() + "'s snippet contains markup characters: " + card.snippet());
        }
    }

    /**
     * The menu must stay generated. Hand-listing the items in the JSP is the
     * one change that would let the editor and the registry drift apart again,
     * and it would not fail any other test here.
     *
     * <p>The menu lives in {@code EditorSurface.jsp} now -- the writing
     * surface the entry editor and the page editor share -- rather than in
     * {@code EntryEditor.jsp}, which is the entry-shaped wrapper around it.
     */
    @Test
    void theEditorBuildsItsMenuFromTheRegistry() throws IOException {
        Path jsp = Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorSurface.jsp");
        assertTrue(Files.exists(jsp), "Expected " + jsp.toAbsolutePath());
        String source = Files.readString(jsp, StandardCharsets.UTF_8);

        assertTrue(source.contains("${shortcodeCards}"),
                jsp + " no longer loops over the registry's cards; the insert menu must "
                        + "be generated, not hand-listed.");
        for (ShortcodeCard card : cards) {
            assertFalse(source.contains(card.snippet()) && !card.snippet().isEmpty(),
                    jsp + " hard-codes " + card.name() + "'s snippet; it belongs to the handler.");
        }
    }

    /**
     * An expander whose only handler answers to {@code name} and records that
     * it was called. Child tags inside the body ([pin], [q], [a]) are not
     * registered anywhere, so they reach the handler as body text exactly as
     * they do in production.
     */
    private static ShortcodeExpander recordingExpander(String name, List<String> seen) {
        return new ShortcodeExpander(List.of(new ShortcodeHandler() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public ShortcodeCard getCard() {
                return ShortcodeCard.snippet(name, "test.label", "");
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
                seen.add(name);
                return "rendered";
            }
        }));
    }
}
