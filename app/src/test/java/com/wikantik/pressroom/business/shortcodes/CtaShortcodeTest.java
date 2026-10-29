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

import java.util.HashMap;
import java.util.Map;

import com.wikantik.pressroom.business.BookingLink;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in {@code [cta]} shortcode: an anchor card with UTM tagging.
 * The href must be absolute http(s) -- the entry-content sanitizer would
 * silently delete any other anchor, so the handler refuses those itself
 * and leaves the shortcode text visible to the author.
 */
class CtaShortcodeTest {

    private final CtaShortcode shortcode = new CtaShortcode();
    private WeblogEntry entry;

    @BeforeEach
    void setUp() {
        Weblog weblog = new Weblog();
        weblog.setHandle("travelblog");
        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("summer-cottage");
    }

    private String render(Map<String, String> attributes) {
        return shortcode.render(attributes, null, entry);
    }

    // ------------------------------------------------------ umami click tracking

    @Test
    void anchorCarriesUmamiClickAttributes() {
        String html = render(Map.of("href", "https://book.example.com/x", "label", "Book"));
        assertTrue(html.contains(" data-umami-event=\"cta-click\""), html);
        assertTrue(html.contains(" data-umami-event-entry=\"summer-cottage\""), html);
        assertTrue(html.contains(" data-umami-event-dest=\"book.example.com\""), html);
    }

    @Test
    void noSlugOmitsEntryButKeepsEventAndDest() {
        entry.setAnchor(null);
        String html = render(Map.of("href", "https://book.example.com/x", "label", "Book"));
        assertTrue(html.contains(" data-umami-event=\"cta-click\""), html);
        assertFalse(html.contains("data-umami-event-entry"), html);
        assertTrue(html.contains(" data-umami-event-dest=\"book.example.com\""), html);
    }

    @Test
    void destHostDropsUserinfoPortPathAndCase() {
        assertEquals("book.example.com",
                BookingLink.destHost("https://User@Book.Example.com:8443/p?q=1"));
    }

    @Test
    void destHostIsNullWhenTheUrlCannotBeParsed() {
        assertNull(BookingLink.destHost("http://exa mple.com"));
        assertNull(BookingLink.destHost("not a url"));
    }

    // -------------------------------------------------------------- happy path

    @Test
    void rendersTheCardAnchorWithLabelNoteAndUtmTags() {
        String html = render(Map.of(
                "href", "https://booking.example.com/cottage",
                "label", "Book this cottage",
                "note", "From EUR 120/night"));

        assertEquals("<a class=\"cta-card\" href=\"https://booking.example.com/cottage"
                + "?utm_source=travelblog&utm_medium=blog&utm_campaign=summer-cottage\""
                + " rel=\"nofollow sponsored noopener\" target=\"_blank\""
                + " data-umami-event=\"cta-click\" data-umami-event-entry=\"summer-cottage\""
                + " data-umami-event-dest=\"booking.example.com\">"
                + "<span class=\"cta-label\">Book this cottage</span>"
                + "<span class=\"cta-note\">From EUR 120/night</span></a>", html);
    }

    @Test
    void theNoteIsOptional() {
        String html = render(Map.of(
                "href", "https://example.com/x", "label", "Print this guide"));
        assertTrue(html.contains("<span class=\"cta-label\">Print this guide</span>"), html);
        assertFalse(html.contains("cta-note"), html);
    }

    // -------------------------------------------------------------- UTM merge

    @Test
    void existingQueryParametersArePreservedAndUtmAppendedWithAmpersands() {
        String html = render(Map.of(
                "href", "https://example.com/book?adults=2&nights=3",
                "label", "Book"));
        assertTrue(html.contains("href=\"https://example.com/book?adults=2&nights=3"
                + "&utm_source=travelblog&utm_medium=blog&utm_campaign=summer-cottage\""),
                html);
    }

    @Test
    void anAuthorSuppliedUtmParameterWinsAndIsNeverDuplicated() {
        String html = render(Map.of(
                "href", "https://example.com/book?utm_source=newsletter",
                "label", "Book"));
        assertTrue(html.contains("?utm_source=newsletter&utm_medium=blog"
                + "&utm_campaign=summer-cottage\""), html);
        assertFalse(html.contains("utm_source=travelblog"), html);
    }

    @Test
    void theFragmentStaysAtTheEndAfterTheUtmParameters() {
        String html = render(Map.of(
                "href", "https://example.com/book?a=1#reviews", "label", "Book"));
        assertTrue(html.contains("&utm_campaign=summer-cottage#reviews\""), html);
        assertFalse(html.contains("#reviews?"), html);
        assertFalse(html.contains("#reviews&"), html);
    }

    @Test
    void aTrailingQuestionMarkDoesNotDoubleTheSeparator() {
        String html = render(Map.of("href", "https://example.com/book?", "label", "Book"));
        assertTrue(html.contains("href=\"https://example.com/book?utm_source=travelblog&"),
                html);
        assertFalse(html.contains("?&"), html);
    }

    @Test
    void utmValuesAreUrlEncoded() {
        entry.getWebsite().setHandle("my blog&co");
        String html = render(Map.of("href", "https://example.com/x", "label", "Go"));
        assertTrue(html.contains("utm_source=my+blog%26co"), html);
    }

    @Test
    void missingWeblogOrAnchorJustSkipsThoseUtmParameters() {
        entry.setAnchor(null);
        String withoutAnchor = render(Map.of("href", "https://example.com/x", "label", "Go"));
        assertTrue(withoutAnchor.contains("?utm_source=travelblog&utm_medium=blog\""),
                withoutAnchor);
        assertFalse(withoutAnchor.contains("utm_campaign"), withoutAnchor);

        String withoutEntry = shortcode.render(
                Map.of("href", "https://example.com/x", "label", "Go"), null, null);
        assertTrue(withoutEntry.contains("?utm_medium=blog\""), withoutEntry);
        assertFalse(withoutEntry.contains("utm_source"), withoutEntry);
    }

    // ---------------------------------------------------------------- escaping

    @Test
    void aScriptPayloadInTheLabelOrNoteIsEscaped() {
        String html = render(new HashMap<>(Map.of(
                "href", "https://example.com/x",
                "label", "\"><script>alert(1)</script>",
                "note", "'onmouseover='x")));
        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("<span class=\"cta-label\">"
                + "&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;</span>"), html);
        assertTrue(html.contains("<span class=\"cta-note\">&#39;onmouseover=&#39;x</span>"),
                html);
    }

    @Test
    void aQuoteSmuggledIntoTheHrefCannotBreakOutOfTheAttribute() {
        // UrlValidator happens to accept a quoted query; the emitter must
        // still encode it so the attribute cannot be broken open
        String html = render(Map.of(
                "href", "https://example.com/x?q=\"onclick=\"alert(1)", "label", "Go"));
        if (html != null) {
            assertFalse(html.contains("q=\"onclick"), html);
        }
    }

    // ------------------------------------------------------- refusal to render

    @Test
    void aMissingHrefOrLabelIsLeftAsWritten() {
        assertNull(render(Map.of("label", "Book")));
        assertNull(render(Map.of("href", "https://example.com/x")));
        assertNull(render(Map.of("href", "  ", "label", "  ")));
    }

    @Test
    void aRelativeHrefIsLeftAsWrittenBecauseTheSanitizerWouldEatTheAnchor() {
        assertNull(render(Map.of("href", "/book", "label", "Book")));
        assertNull(render(Map.of("href", "book.html", "label", "Book")));
    }

    @Test
    void nonHttpSchemesAreLeftAsWritten() {
        assertNull(render(Map.of("href", "javascript:alert(1)", "label", "Book")));
        assertNull(render(Map.of("href", "ftp://example.com/x", "label", "Book")));
        assertNull(render(Map.of("href", "mailto:x@example.com", "label", "Book")));
    }

    @Test
    void aTldLessHostIsLeftAsWrittenExactlyLikeTheSanitizerWould() {
        // UrlValidator rejects "localhost" -- the same rule the sanitizer
        // applies, which is why rendering tests use 127.0.0.1
        assertNull(render(Map.of("href", "http://localhost:8080/x", "label", "Book")));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void theDefaultExpanderShipsWithTheCtaShortcodeRegistered() {
        String rendered = BuiltInExpanders.withMocks().expand(entry,
                "see [cta href=\"https://example.com/book\" label=\"Book now\"] ok");

        assertTrue(rendered.contains("<a class=\"cta-card\""), rendered);
        assertFalse(rendered.contains("[cta"), rendered);
    }

    @Test
    void anInvalidHrefLeavesTheShortcodeTextVisibleThroughTheExpander() {
        String rendered = BuiltInExpanders.withMocks().expand(entry,
                "[cta href=\"/book\" label=\"Book now\"]");

        assertEquals("[cta href=\"/book\" label=\"Book now\"]", rendered,
                "null from the handler is the SPI's visible-failure signal");
    }

    // ------------------------------------------------- default booking link (AC16)

    private Weblog entryWeblog() {
        return entry.getWebsite();
    }

    @Test
    void noHrefUsesTheWeblogsBookingUrl() {
        entryWeblog().setBookingUrl("https://own.example.com/book");
        com.wikantik.pressroom.pojos.Business b = new com.wikantik.pressroom.pojos.Business();
        b.setBookingUrl("https://biz.example.com/book");
        entryWeblog().setBusiness(b);
        String html = render(Map.of("label", "Book"));
        assertTrue(html.contains(
                "href=\"https://own.example.com/book?utm_source=travelblog&utm_medium=blog"
                        + "&utm_campaign=summer-cottage\""), html);
        assertTrue(html.contains(" data-umami-event-dest=\"own.example.com\""), html);
    }

    @Test
    void noHrefFallsBackToTheBusinessBookingUrl() {
        com.wikantik.pressroom.pojos.Business b = new com.wikantik.pressroom.pojos.Business();
        b.setBookingUrl("https://biz.example.com/book");
        entryWeblog().setBusiness(b);
        String html = render(Map.of("label", "Book"));
        assertTrue(html.contains("href=\"https://biz.example.com/book?utm_source=travelblog"), html);
        assertTrue(html.contains(" data-umami-event-dest=\"biz.example.com\""), html);
    }

    @Test
    void noHrefAndNoBookingUrlIsLeftAsWritten() {
        assertNull(render(Map.of("label", "Book")));
    }

    @Test
    void anExplicitHrefBeatsTheDefaultBookingLink() {
        entryWeblog().setBookingUrl("https://own.example.com/book");
        String html = render(Map.of("href", "https://explicit.example.com/x", "label", "Book"));
        assertTrue(html.contains("href=\"https://explicit.example.com/x?"), html);
        assertFalse(html.contains("own.example.com"), html);
    }
}
