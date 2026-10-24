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
package org.apache.roller.weblogger.business.jsonld;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the typed JSON-LD emitter behind the SEO card's structured
 * data selector.
 *
 * <p>Everything here PARSES the emitted string rather than matching substrings:
 * a stray comma or an unescaped quote makes crawlers drop the whole block, and
 * a contains-assertion would never notice. {@code SeoHeadRenderingTest} proves
 * the same objects survive the real macro, servlet and theme; this file pins
 * the shapes, the fallbacks and the escaping without a database.
 */
class EntryJsonLdTest {

    /**
     * The media manager a [map auto=..] would resolve its directory through;
     * inert here, since every itinerary in this class is written with inline
     * [pin] tags.
     */
    private static final org.apache.roller.weblogger.business.MediaFileManager MEDIA =
            org.mockito.Mockito.mock(org.apache.roller.weblogger.business.MediaFileManager.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NAME = "Eiffel Tower";
    private static final String DESCRIPTION = "The iron lady of Paris.";
    private static final String IMAGE = "http://example.com/eiffel.jpg";
    private static final String URL = "http://example.com/blog/entry/eiffel";

    private static WeblogEntry entry(JsonLdType type) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setJsonLdType(type);
        return entry;
    }

    private static JsonNode build(WeblogEntry entry) {
        String json = EntryJsonLd.build(entry, NAME, DESCRIPTION, IMAGE, URL, MEDIA);
        assertTrue(json != null, "expected a typed block for " + entry.getJsonLdType());
        return MAPPER.readTree(json);
    }

    // ------------------------------------------------ the no-override cases

    @Test
    void anEntryWithNoTypeGetsNoTypedBlock() {
        assertNull(EntryJsonLd.build(entry(null), NAME, DESCRIPTION, IMAGE, URL, MEDIA),
                "a null type is what every entry written before this field existed"
                        + " carries; it must keep its BlogPosting");
    }

    @Test
    void anExplicitBlogPostingTypeGetsNoTypedBlockEither() {
        // EntryBean.copyTo normalizes a blank selection to BLOG_POSTING, so
        // merely re-saving an old entry stores the enum where null used to be.
        // If BLOG_POSTING emitted anything, every re-saved entry would suddenly
        // sprout a second block duplicating the BlogPosting one.
        assertNull(EntryJsonLd.build(entry(JsonLdType.BLOG_POSTING),
                NAME, DESCRIPTION, IMAGE, URL, MEDIA));
    }

    @Test
    void aTechArticleGetsNoSecondBlockBecauseTheArticleBlockItselfChangesType() {
        assertNull(EntryJsonLd.build(entry(JsonLdType.TECH_ARTICLE),
                NAME, DESCRIPTION, IMAGE, URL, MEDIA));
    }

    @Test
    void aNullEntryIsNotAnError() {
        assertNull(EntryJsonLd.build(null, NAME, DESCRIPTION, IMAGE, URL, MEDIA));
    }

    // ---------------------------------------------------- shared properties

    @Test
    void everyTypedBlockCarriesTheSharedSeoProperties() {
        JsonNode node = build(entry(JsonLdType.TOURIST_ATTRACTION));

        assertEquals("https://schema.org", node.path("@context").asString());
        assertEquals("TouristAttraction", node.path("@type").asString());
        assertEquals(NAME, node.path("name").asString());
        assertEquals(DESCRIPTION, node.path("description").asString());
        assertEquals(IMAGE, node.path("image").asString());
        assertEquals(URL, node.path("url").asString());
        assertEquals(URL, node.path("mainEntityOfPage").asString(),
                "the sibling BlogPosting block claims the same page; matching"
                        + " mainEntityOfPage is how a consumer associates the two");
    }

    @Test
    void blankSharedValuesAreOmittedRatherThanEmitted() {
        String json = EntryJsonLd.build(entry(JsonLdType.TOURIST_ATTRACTION),
                NAME, "  ", "", null, MEDIA);
        JsonNode node = MAPPER.readTree(json);

        assertEquals(NAME, node.path("name").asString());
        assertFalse(node.has("description"), "a blank description must be absent: " + node);
        assertFalse(node.has("image"), "a blank image must be absent: " + node);
        assertFalse(node.has("url"), "a null url must be absent: " + node);
        assertFalse(node.has("mainEntityOfPage"),
                "and so must the page claim built from it: " + node);
    }

    @Test
    void valuesAreEscapedOnceAndCannotBreakOutOfTheScriptElement() {
        // The values arrive RAW from the macro; this class is the only escape
        // point. A quote must not end the JSON string and "</script>" must not
        // end the element the JSON is emitted into.
        String json = EntryJsonLd.build(entry(JsonLdType.TOURIST_ATTRACTION),
                "Chez \"Nous\"", "Ends with </script> and a \\ backslash", IMAGE, URL, MEDIA);

        assertFalse(json.contains("</script>"),
                "the forward slash must be escaped so the block cannot self-terminate: " + json);
        JsonNode node = MAPPER.readTree(json);
        assertEquals("Chez \"Nous\"", node.path("name").asString(),
                "escaping must round-trip exactly once, not twice");
        assertEquals("Ends with </script> and a \\ backslash",
                node.path("description").asString());
    }

    // ------------------------------------------------------ TouristAttraction

    @Test
    void touristAttractionCarriesItsCoordinates() {
        WeblogEntry entry = entry(JsonLdType.TOURIST_ATTRACTION);
        entry.setGeoLatitude(48.8584);
        entry.setGeoLongitude(2.2945);

        JsonNode geo = build(entry).path("geo");

        assertEquals("GeoCoordinates", geo.path("@type").asString());
        assertEquals(48.8584, geo.path("latitude").asDouble());
        assertEquals(2.2945, geo.path("longitude").asDouble());
    }

    @Test
    void touristAttractionWithoutUsableCoordinatesStillEmitsWithoutGeo() {
        // name is the only required property, so a half-filled or out-of-range
        // pair drops the geo node instead of the whole block.
        assertFalse(build(entry(JsonLdType.TOURIST_ATTRACTION)).has("geo"),
                "no coordinates at all means no geo node");

        WeblogEntry halfSet = entry(JsonLdType.TOURIST_ATTRACTION);
        halfSet.setGeoLatitude(48.8584);
        assertFalse(build(halfSet).has("geo"), "a latitude with no longitude is not a point");

        WeblogEntry outOfRange = entry(JsonLdType.TOURIST_ATTRACTION);
        outOfRange.setGeoLatitude(120.0);
        outOfRange.setGeoLongitude(2.2945);
        assertFalse(build(outOfRange).has("geo"), "120 degrees of latitude is not a place");
    }

    // ------------------------------------------------------------ TouristTrip

    @Test
    void touristTripItineraryFollowsTheMapPinsInSourceOrder() {
        WeblogEntry entry = entry(JsonLdType.TOURIST_TRIP);
        entry.setText("Day one.\n"
                + "[map zoom=\"12\"]"
                + "[pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]"
                + "[pin lat=\"48.8606\" lng=\"2.3376\" label=\"Louvre\"]"
                + "[/map]\n"
                + "Day two.\n"
                + "[map][pin lat=\"48.8530\" lng=\"2.3499\" label=\"Notre-Dame\"][/map]");

        JsonNode itinerary = build(entry).path("itinerary");

        assertEquals("ItemList", itinerary.path("@type").asString());
        assertEquals(3, itinerary.path("numberOfItems").asInt());
        JsonNode items = itinerary.path("itemListElement");
        assertEquals(3, items.size(), "every pin of every map, in one list: " + itinerary);
        assertEquals("Eiffel Tower", items.get(0).path("name").asString());
        assertEquals("Louvre", items.get(1).path("name").asString());
        assertEquals("Notre-Dame", items.get(2).path("name").asString(),
                "pins must keep entry-source order across maps: " + itinerary);
        assertEquals("Place", items.get(0).path("@type").asString());
        assertEquals(48.8584, items.get(0).path("geo").path("latitude").asDouble());
        assertEquals(2.2945, items.get(0).path("geo").path("longitude").asDouble());
    }

    @Test
    void anUnlabelledPinKeepsItsPlaceWithoutAnInventedName() {
        WeblogEntry entry = entry(JsonLdType.TOURIST_TRIP);
        entry.setText("[map][pin lat=\"1.5\" lng=\"2.5\"][/map]");

        JsonNode place = build(entry).path("itinerary").path("itemListElement").get(0);

        assertFalse(place.has("name"), "no label means no name, not a name made of numbers");
        assertEquals(1.5, place.path("geo").path("latitude").asDouble());
    }

    @Test
    void aTouristTripWithNoPinsStillEmitsWithoutAnItinerary() {
        WeblogEntry entry = entry(JsonLdType.TOURIST_TRIP);
        entry.setText("Just prose about a trip.");

        JsonNode node = build(entry);

        assertEquals("TouristTrip", node.path("@type").asString());
        assertFalse(node.has("itinerary"), "an itinerary-less trip is still a valid trip");
    }

    @Test
    void aTouristTripWithNoBodyAtAllIsNotAnError() {
        // A draft can be typed before a word of it is written.
        JsonNode node = build(entry(JsonLdType.TOURIST_TRIP));

        assertEquals("TouristTrip", node.path("@type").asString());
        assertFalse(node.has("itinerary"));
    }

    @Test
    void anEscapedMapMentionIsNotAnItinerary() {
        // [[map]] is how the docs show the syntax in prose; it must not turn
        // an article about maps into a trip with a real itinerary.
        WeblogEntry entry = entry(JsonLdType.TOURIST_TRIP);
        entry.setText("Write [[map]][pin lat=\"1.5\" lng=\"2.5\"][[/map]] to add a map.");

        assertFalse(build(entry).has("itinerary"));
    }

    // ---------------------------------------------------------------- Event

    @Test
    void eventCarriesIsoDatesAndANameOnlyPlace() {
        WeblogEntry entry = entry(JsonLdType.EVENT);
        entry.setEventStart(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 19, 30)));
        entry.setEventEnd(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 22, 0)));
        entry.setEventLocation("Champ de Mars");

        JsonNode node = build(entry);

        assertEquals("Event", node.path("@type").asString());
        String start = node.path("startDate").asString();
        assertTrue(start.startsWith("2026-08-02T19:30:00"),
                "startDate must be ISO-8601 local time with an offset, got: " + start);
        assertTrue(start.matches(".*([+-]\\d{2}:?\\d{2}|Z)$"),
                "startDate must carry a zone designator, got: " + start);
        assertTrue(node.path("endDate").asString().startsWith("2026-08-02T22:00:00"));
        assertEquals("Place", node.path("location").path("@type").asString());
        assertEquals("Champ de Mars", node.path("location").path("name").asString());
        assertFalse(node.path("location").has("address"),
                "the editor collects a venue name; inventing an address would be a lie");
    }

    @Test
    void anEventWithNoEndOrVenueOmitsThemRatherThanGuessing() {
        WeblogEntry entry = entry(JsonLdType.EVENT);
        entry.setEventStart(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 19, 30)));

        JsonNode node = build(entry);

        assertFalse(node.has("endDate"));
        assertFalse(node.has("location"));
    }

    @Test
    void anEventWithNoStartDateEmitsNoSecondBlock() {
        // startDate is structurally required; an Event without one cannot
        // validate, and invalid structured data is worse than none -- the
        // permalink still has its BlogPosting block.
        WeblogEntry entry = entry(JsonLdType.EVENT);
        entry.setEventLocation("Champ de Mars");

        assertNull(EntryJsonLd.build(entry, NAME, DESCRIPTION, IMAGE, URL, MEDIA));
    }

    // -------------------------------------------------------------- FAQPage

    @Test
    void faqPageMainEntityMirrorsTheFaqShortcodeContent() {
        WeblogEntry entry = entry(JsonLdType.FAQ_PAGE);
        entry.setText("Intro.\n"
                + "[faq][q]When should I go?[/q][a]Spring or autumn.[/a]"
                + "[q]Is it free?[/q][a]No, tickets are 18 euros.[/a][/faq]");

        JsonNode questions = build(entry).path("mainEntity");

        assertEquals(2, questions.size(), "one Question per [q]/[a] pair");
        assertEquals("Question", questions.get(0).path("@type").asString());
        assertEquals("When should I go?", questions.get(0).path("name").asString());
        assertEquals("Answer", questions.get(0).path("acceptedAnswer").path("@type").asString());
        assertEquals("Spring or autumn.",
                questions.get(0).path("acceptedAnswer").path("text").asString());
        assertEquals("Is it free?", questions.get(1).path("name").asString());
        assertEquals("No, tickets are 18 euros.",
                questions.get(1).path("acceptedAnswer").path("text").asString());
    }

    @Test
    void faqTextIsReducedToPlainTextForTheStructuredData() {
        // The page renders the author's markup inside the <dl>; Question.name
        // may not contain any, and an entity written by the editor must reach
        // the JSON as the character it stands for.
        WeblogEntry entry = entry(JsonLdType.FAQ_PAGE);
        entry.setText("[faq][q]Fish &amp; chips?[/q]"
                + "[a]<p>Yes, <strong>always</strong>.</p>[/a][/faq]");

        JsonNode question = build(entry).path("mainEntity").get(0);

        assertEquals("Fish & chips?", question.path("name").asString());
        assertEquals("Yes, always.", question.path("acceptedAnswer").path("text").asString(),
                "markup never reaches the JSON, and stripping it must not maul the prose");
    }

    @Test
    void aFaqPageWithNoParseablePairsEmitsNoSecondBlock() {
        // FaqBlocks is strict: a malformed block yields nothing, and a FAQPage
        // with an empty mainEntity is invalid.
        WeblogEntry noBlock = entry(JsonLdType.FAQ_PAGE);
        noBlock.setText("Just prose, no FAQ at all.");
        assertNull(EntryJsonLd.build(noBlock, NAME, DESCRIPTION, IMAGE, URL, MEDIA));

        WeblogEntry malformed = entry(JsonLdType.FAQ_PAGE);
        malformed.setText("[faq][q]A question with no answer[/q][/faq]");
        assertNull(EntryJsonLd.build(malformed, NAME, DESCRIPTION, IMAGE, URL, MEDIA),
                "a half-written FAQ must not become half a FAQPage");

        WeblogEntry noText = entry(JsonLdType.FAQ_PAGE);
        assertNull(EntryJsonLd.build(noText, NAME, DESCRIPTION, IMAGE, URL, MEDIA));
    }
}
