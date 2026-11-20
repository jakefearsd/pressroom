package com.wikantik.pressroom.business.shortcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The shared tag grammar: child tags read out of a body, and the expander's walk. */
class ShortcodeSyntaxTest {

    @Test
    void childTagsAreReadInSourceOrderWithTheirAttributes() {
        List<Map<String, String>> tags = ShortcodeSyntax.childTags(
                "x [pin lat=\"1\" lng='2' label=\"A [b]\"] y [PIN lat=3 lng=4 /] [pin/]", "pin");

        assertEquals(List.of(
                Map.of("lat", "1", "lng", "2", "label", "A [b]"),
                Map.of("lat", "3", "lng", "4"),
                Map.<String, String>of()), tags);
    }

    @Test
    void anEscapedOtherNamedOrUnclosedTagIsNotAChild() {
        assertEquals(List.of(), ShortcodeSyntax.childTags(
                "[[pin lat=1 lng=2]] [pins lat=1 lng=2] [pin-x lat=1] [pin lat=1", "pin"));
    }

    @Test
    void walkVisitsEachRegisteredTagWithoutRewritingAnything() {
        ShortcodeExpander expander = new ShortcodeExpander(List.of(new FaqShortcode(), new CtaShortcode()));
        List<String> seen = new ArrayList<>();

        expander.walk(null, "a [faq x=\"1\"]body[/faq] b [cta url=\"u\"] c [unknown]",
                (name, attributes, body) -> seen.add(name + attributes + "|" + body));

        assertEquals(List.of("faq{x=1}|body", "cta{url=u}|null"), seen);
    }
}
