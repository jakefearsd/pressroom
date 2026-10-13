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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parsing/dispatch matrix for {@link ShortcodeExpander}: registered
 * shortcodes expand, unknown ones pass through untouched (forward-compat
 * with shortcodes that ship in later waves), {@code [[...]]} escapes, and
 * malformed or failing shortcodes never damage the surrounding text.
 */
class ShortcodeExpanderTest {

    private final WeblogEntry entry = new WeblogEntry();

    /** Renders [upper]body[/upper] (or self-closing [upper word=x]) as upper-case. */
    private static ShortcodeHandler upper() {
        return new ShortcodeHandler() {
            @Override
            public String getName() {
                return "upper";
            }

            @Override
            public ShortcodeCard getCard() {
                return ShortcodeCard.snippet("upper", "test.label", "[upper]");
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
                String source = body != null ? body : attributes.getOrDefault("word", "");
                return source.toUpperCase();
            }
        };
    }

    /** Echoes its attributes so tests can see exactly what was parsed. */
    private static ShortcodeHandler echoAttrs() {
        return new ShortcodeHandler() {
            @Override
            public String getName() {
                return "echo";
            }

            @Override
            public ShortcodeCard getCard() {
                return ShortcodeCard.snippet("echo", "test.label", "[echo]");
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
                StringBuilder out = new StringBuilder("{");
                attributes.forEach((k, v) -> out.append(k).append('=').append(v).append(';'));
                return out.append('}').toString();
            }
        };
    }

    private final ShortcodeExpander expander =
            new ShortcodeExpander(List.of(upper(), echoAttrs()));

    // ------------------------------------------------------------- expansion

    @Test
    void aSelfClosingShortcodeIsReplaced() {
        assertEquals("before LOUD after",
                expander.expand(entry, "before [upper word=loud] after"));
    }

    @Test
    void aBodyShortcodeFeedsItsBodyToTheHandler() {
        assertEquals("say IT LOUD now",
                expander.expand(entry, "say [upper]it loud[/upper] now"));
    }

    @Test
    void anExplicitlySelfClosedShortcodeDoesNotCaptureALaterCloser() {
        // The trailing slash says "no body", so the stray [/upper] is just text.
        assertEquals("X and [/upper]",
                expander.expand(entry, "[upper word=x /] and [/upper]"));
    }

    @Test
    void shortcodeNamesAreCaseInsensitive() {
        assertEquals("LOUD", expander.expand(entry, "[UPPER word=loud]"));
    }

    @Test
    void multipleShortcodesInOneTextAllExpand() {
        assertEquals("A then B",
                expander.expand(entry, "[upper word=a] then [upper word=b]"));
    }

    @Test
    void differentShortcodesNestInsideABody() {
        assertEquals("BEFORE {A=1;} AFTER",
                expander.expand(entry, "[upper]before [echo a=1] after[/upper]"));
    }

    // --------------------------------------------------- same-name nesting

    @Nested
    class SameNameNesting {

        @Test
        void sameNameShortcodesPairInsideOut() {
            // Regression (review Important #1): the closer search used to grab
            // the FIRST [/upper], corrupting the outer body and leaking the
            // second closer as raw text into the page.
            assertEquals("OUTER INNER TAIL",
                    expander.expand(entry, "[upper]outer [upper]inner[/upper] tail[/upper]"));
        }

        @Test
        void adjacentSameNamePairsDoNotLeakACloser() {
            // The review's exact repro shape.
            String out = expander.expand(entry,
                    "[upper word=outer][upper word=inner][/upper][/upper]");
            assertFalse(out.contains("[/upper]"),
                    "a closer with a matching open must never leak into rendered "
                            + "output: " + out);
            assertFalse(out.contains("[upper"), out);
        }

        @Test
        void anExplicitlySelfClosedInnerDoesNotConsumeTheOuterCloser() {
            assertEquals("A B C",
                    expander.expand(entry, "[upper]a [upper word=b /] c[/upper]"));
        }

        @Test
        void anInnerOpenWithoutItsOwnCloserFallsBackToSelfClosing() {
            // Opens outnumber closers: strict pairing would give the single
            // closer to the inner open and leave the outer unclosed. The
            // documented fallback gives the outer the LAST closer and the
            // inner degrades to self-closing -- the intuitive reading.
            assertEquals("A B C",
                    expander.expand(entry, "[upper]a [upper word=b] c[/upper]"));
        }

        @Test
        void anEscapedSameNameInsideABodyIsJustText() {
            assertEquals("A [UPPER] B",
                    expander.expand(entry, "[upper]a [[upper]] b[/upper]"));
        }

        @Test
        void closerMatchingIsCaseInsensitiveLikeTagNames() {
            assertEquals("BODY", expander.expand(entry, "[upper]body[/UPPER]"));
        }

        @Test
        void sequentialPairsStillPairFirstWithFirst() {
            assertEquals("ONE and TWO",
                    expander.expand(entry, "[upper]one[/upper] and [upper]two[/upper]"));
        }

        @Test
        void aMalformedInnerSameNameTagNeitherConsumesACloserNorDerailsTheOuter() {
            // "[upper \"x b" starts like an open tag but never completes one
            // (unterminated quote, no ]) -- it must count as plain body text,
            // not as an open that eats the outer's closer.
            assertEquals("A [UPPER \"X B",
                    expander.expand(entry, "[upper]a [upper \"x b[/upper]"));
        }
    }

    // ------------------------------------------------- closer look-alikes

    /**
     * Round-2 review finding: the closer scan must see tags, not raw
     * substrings. A [/name]-shaped string in body prose or inside another
     * tag's quoted attribute must never truncate the body and leak the real
     * closer into the page.
     */
    @Nested
    class CloserLookalikes {

        @Test
        void aProseMentionOfTheCloserStaysInsideTheBody() {
            // The body extends to the LAST closer in the run, so the prose
            // mention is kept as literal body text -- not a truncation point
            // that leaks the real closer as raw output.
            assertEquals("READ THE DOCS, E.G. [/UPPER] AS AN EXAMPLE, THEN CLOSE",
                    expander.expand(entry,
                            "[upper]read the docs, e.g. [/upper] as an example, then close[/upper]"));
        }

        @Test
        void aCloserLookalikeInsideAnotherTagsQuotedAttributeIsJustAValue() {
            // The review's second repro: without tag-boundary awareness this
            // truncated the echo tag mid-attribute and leaked broken
            // caption=" fragments plus the real closer into the page.
            assertEquals("X{CAPTION=[/UPPER];}Y",
                    expander.expand(entry, "[upper]x[echo caption=\"[/upper]\"]y[/upper]"));
        }

        @Test
        void aCloserLookalikeInsideAnUnknownTagsQuotedAttributeIsJustAValue() {
            // Same rule for tags with no registered handler: a well-formed
            // tag span is opaque to the closer scan, and the unknown tag
            // itself still passes through untouched inside the body.
            assertEquals("X[GALLERY CAPTION=\"[/UPPER]\"]Y",
                    expander.expand(entry, "[upper]x[gallery caption=\"[/upper]\"]y[/upper]"));
        }

        @Test
        void anEscapedCloserInsideABodyRendersAsTheLiteralCloser() {
            // [[/name]] is the author's precise way to mention a closer in
            // prose: never structural, always rendered as [/name].
            assertEquals("DOCS SAY [/UPPER] HERE",
                    expander.expand(entry, "[upper]docs say [[/upper]] here[/upper]"));
        }

        @Test
        void anEscapedCloserOutsideAnyShortcodeAlsoUnescapes() {
            assertEquals("write [/upper] to close",
                    expander.expand(entry, "write [[/upper]] to close"));
        }

        @Test
        void anEscapedCloserOfAnUnknownNameStaysUntouched() {
            // Like [[unknown]]: it only un-escapes once the name is
            // registered, so future-wave docs keep their exact bytes today.
            String text = "docs say [[/gallery]] here";
            assertSame(text, expander.expand(entry, text));
        }

        @Test
        void sequentialPairsAfterAProseMentionStillPairCorrectly() {
            // The greedy last-closer rule must stop at the next same-name
            // open: the second pair keeps its own closer.
            assertEquals("ONE [/UPPER] MORE and TWO",
                    expander.expand(entry,
                            "[upper]one [/upper] more[/upper] and [upper]two[/upper]"));
        }
    }

    @Test
    void attributesParseQuotedSingleQuotedAndBareValues() {
        assertEquals("{a=one two;b=three;c=4;}",
                expander.expand(entry, "[echo a=\"one two\" b='three' c=4]"));
    }

    @Test
    void attributeNamesAreLowerCased() {
        assertEquals("{key=v;}", expander.expand(entry, "[echo KEY=v]"));
    }

    // ------------------------------------------- brackets in quoted values

    @Nested
    class QuotedAttributeValues {

        @Test
        void aClosingBracketInsideADoubleQuotedValueStaysInTheValue() {
            // Regression (review Important #2): a ] inside a quoted value
            // used to terminate the tag early and leak the truncated tail as
            // raw text.
            assertEquals("before {caption=a]b;} after",
                    expander.expand(entry, "before [echo caption=\"a]b\"] after"));
        }

        @Test
        void aClosingBracketInsideASingleQuotedValueStaysInTheValue() {
            assertEquals("{caption=a]b;}",
                    expander.expand(entry, "[echo caption='a]b']"));
        }

        @Test
        void aWholeShortcodeInsideAQuotedValueIsJustAValue() {
            // The registered name inside the value must be neither expanded
            // nor allowed to derail the enclosing tag's parse.
            assertEquals("{caption=use [upper word=x] here;}",
                    expander.expand(entry, "[echo caption=\"use [upper word=x] here\"]"));
        }

        @Test
        void anUnquotedValueStillEndsAtTheFirstClosingBracket() {
            assertEquals("{a=x;}y]", expander.expand(entry, "[echo a=x]y]"));
        }

        @Test
        void anUnterminatedQuoteLeavesTheTextUntouched() {
            // An unterminated quote means we cannot know where the author
            // intended the tag to end -- pass the whole thing through rather
            // than guessing and truncating.
            String text = "[echo caption=\"a]b] after";
            assertEquals(text, expander.expand(entry, text));
        }
    }

    // ------------------------------------------------- unknown / passthrough

    @Nested
    class Passthrough {

        @Test
        void anUnknownShortcodePassesThroughUnchanged() {
            String text = "keep [gallery ids=\"1,2\"]my images[/gallery] intact";
            assertEquals(text, expander.expand(entry, text),
                    "unknown shortcodes belong to future waves; today's expander "
                            + "must not touch them");
        }

        @Test
        void anEscapedUnknownShortcodeAlsoPassesThroughUnchanged() {
            // [[gallery]] only un-escapes once "gallery" is a registered name.
            String text = "docs say [[gallery ids=\"1\"]]";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void textWithoutShortcodesComesBackAsTheSameInstance() {
            String text = "plain <b>html</b> with no brackets";
            assertSame(text, expander.expand(entry, text),
                    "the no-shortcode fast path must not copy every entry on every render");
        }

        @Test
        void nullTextStaysNull() {
            assertNull(expander.expand(entry, null));
        }

        @Test
        void emptyTextStaysEmpty() {
            assertEquals("", expander.expand(entry, ""));
        }
    }

    // -------------------------------------------------------------- escaping

    @Nested
    class Escaping {

        @Test
        void aDoubledBracketRendersTheLiteralShortcode() {
            assertEquals("write [upper word=x] to shout",
                    expander.expand(entry, "write [[upper word=x]] to shout"),
                    "[[...]] is how authors document a shortcode without running it");
        }

        @Test
        void anEscapedShortcodeNextToARealOneOnlySuppressesItself() {
            assertEquals("[upper word=a] vs B",
                    expander.expand(entry, "[[upper word=a]] vs [upper word=b]"));
        }

        @Test
        void aDoubledOpeningBracketWithoutADoubledCloserStillExpands() {
            // "[[upper word=x]" is a stray literal bracket followed by a real
            // shortcode, matching how the escape form is defined ([[...]]).
            assertEquals("[X", expander.expand(entry, "[[upper word=x]"));
        }
    }

    // ------------------------------------------------------------- malformed

    @Nested
    class Malformed {

        @Test
        void anUnterminatedTagIsLeftAlone() {
            String text = "oops [upper word=x with no close bracket";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void aCloserWithNoOpenerIsLeftAlone() {
            String text = "stray [/upper] closer";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void aBodyShortcodeMissingItsCloserFallsBackToSelfClosing() {
            assertEquals("LOUD and the rest stays",
                    expander.expand(entry, "[upper word=loud] and the rest stays"));
        }

        @Test
        void bracketsThatAreNotShortcodesAtAllAreLeftAlone() {
            String text = "arrays[0] and [1,2,3] and [ spaced ]";
            assertEquals(text, expander.expand(entry, text));
        }

        /**
         * "[Note that" and then a long paragraph with no closing bracket is
         * ordinary prose. The tag pattern used to recurse once per character
         * of a would-be attribute section, so a couple of thousand of them
         * overflowed a request thread's stack: every page showing the entry
         * failed with a StackOverflowError instead of rendering it.
         */
        @Test
        void anUnclosedBracketBeforeALongParagraphIsLeftAlone() {
            String text = "[Note that " + "the rest of this paragraph runs on. ".repeat(3_000);
            assertEquals(text, expander.expand(entry, text));
        }

        /** The same recursion, reached through quoted values inside a well-formed tag. */
        @Test
        void aTagWithThousandsOfQuotedValuesStillExpands() {
            String text = "[upper word=x" + " a=\"b\"".repeat(20_000) + "]";
            assertEquals("X", expander.expand(entry, text));
        }
    }

    // ---------------------------------------------------------- failure modes

    @Nested
    class HandlerFailures {

        private final ShortcodeExpander failing = new ShortcodeExpander(List.of(
                new ShortcodeHandler() {
                    @Override
                    public String getName() {
                        return "boom";
                    }

                    @Override
                    public ShortcodeCard getCard() {
                        return ShortcodeCard.snippet("boom", "test.label", "[boom]");
                    }

                    @Override
                    public String render(Map<String, String> attributes, String body,
                            ShortcodeContext content) {
                        throw new IllegalStateException("boom");
                    }
                },
                new ShortcodeHandler() {
                    @Override
                    public String getName() {
                        return "shy";
                    }

                    @Override
                    public ShortcodeCard getCard() {
                        return ShortcodeCard.snippet("shy", "test.label", "[shy]");
                    }

                    @Override
                    public String render(Map<String, String> attributes, String body,
                            ShortcodeContext content) {
                        return null;
                    }
                },
                upper()));

        @Test
        void aThrowingHandlerLeavesItsShortcodeAsWritten() {
            assertEquals("a [boom id=1] b",
                    failing.expand(entry, "a [boom id=1] b"),
                    "a broken handler must leave the author's text visible, not "
                            + "take out the page");
        }

        @Test
        void aNullReturningHandlerLeavesItsShortcodeAsWrittenIncludingBody() {
            assertEquals("a [shy]body[/shy] b",
                    failing.expand(entry, "a [shy]body[/shy] b"),
                    "null is the handler's 'cannot render' signal and must preserve "
                            + "the original text verbatim");
        }

        @Test
        void aFailingShortcodeDoesNotStopItsNeighborsFromExpanding() {
            assertEquals("[boom] X [shy]",
                    failing.expand(entry, "[boom] [upper word=x] [shy]"));
        }
    }

    // --------------------------------------------------------- default registry

    /**
     * {@code ImageShortcodeTest} already pins "an explicit {@code alt=""}
     * wins over the stored/filename fallbacks" at the HANDLER level, calling
     * {@link ImageShortcode#render} directly with a pre-parsed attribute map.
     * That leaves the {@link ShortcodeExpander#ATTRIBUTE} regex itself
     * unpinned: nothing proved that {@code alt=""} in raw entry text actually
     * parses into an attribute map containing the key {@code "alt"} with an
     * empty value, as opposed to, say, being dropped as if absent. This
     * exercises the real default registry end to end, the way both render
     * seams (see {@code WeblogEntry.render()}) actually call it.
     */
    @Nested
    class DefaultRegistry {

        @Test
        void anExplicitlyEmptyAltSurvivesParsingThroughTheDefaultExpander() throws Exception {
            Weblog weblog = new Weblog();
            weblog.setHandle("blog");

            MediaFile photo = new MediaFile();
            photo.setId("x");
            photo.setWeblog(weblog);
            photo.setName("hawk.jpg");
            photo.setAltText("A red kite riding the thermal");
            photo.setContentType("image/jpeg");

            MediaFileManager mediaFileManager = mock(MediaFileManager.class);
            when(mediaFileManager.getMediaFile("x")).thenReturn(photo);

            URLStrategy urls = mock(URLStrategy.class);
            when(urls.getMediaFileURL(weblog, "x", true)).thenReturn("http://example.com/x");

            Weblogger weblogger = mock(Weblogger.class);
            when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
            when(weblogger.getUrlStrategy()).thenReturn(urls);

            WeblogEntry imageEntry = new WeblogEntry();
            imageEntry.setWebsite(weblog);

            String out = ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                    .expand(imageEntry, "[image id=\"x\" alt=\"\"]");

            assertTrue(out.contains("alt=\"\""),
                    "alt=\"\" in the raw shortcode text must parse through and be honoured "
                            + "verbatim, not be dropped by the attribute regex and fall back to "
                            + "the stored alt text or filename: " + out);
            assertFalse(out.contains("A red kite riding the thermal"), out);
            assertFalse(out.contains("hawk.jpg"), out);
        }
    }
}
