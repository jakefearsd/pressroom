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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared {@code [pin ...]} parser: the single source of truth for both
 * the [map] shortcode's pin payload and the JSON-LD itinerary emission
 * (Wave 3 T4), so the map on the page and the structured data in the head
 * cannot drift. Records come back in source order; malformed pins are
 * skipped, never guessed at.
 */
class MapPinsTest {

    // ---------------------------------------------------------------- parsing

    @Test
    void parsesPinsInSourceOrder() {
        List<MapPins.Pin> pins = MapPins.parse(
                "[pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]\n"
                        + "[pin lat=\"48.8606\" lng=\"2.3376\" label=\"Louvre\"]");

        assertEquals(2, pins.size());
        assertEquals(48.8584, pins.get(0).lat());
        assertEquals(2.2945, pins.get(0).lng());
        assertEquals("Eiffel Tower", pins.get(0).label());
        assertEquals("Louvre", pins.get(1).label());
    }

    @Test
    void aPinDoesNotNeedALabel() {
        List<MapPins.Pin> pins = MapPins.parse("[pin lat=\"1.5\" lng=\"-2.5\"]");
        assertEquals(1, pins.size());
        assertNull(pins.get(0).label());
    }

    @Test
    void selfClosingSingleQuotedAndBareAttributeFormsAllParse() {
        List<MapPins.Pin> pins = MapPins.parse(
                "[pin lat='1.0' lng='2.0' /] [pin lat=3.0 lng=4.0]");
        assertEquals(2, pins.size());
        assertEquals(1.0, pins.get(0).lat());
        assertEquals(4.0, pins.get(1).lng());
    }

    @Test
    void tagNameAndAttributeNamesAreCaseInsensitive() {
        List<MapPins.Pin> pins = MapPins.parse("[PIN LAT=\"1\" Lng=\"2\" Label=\"x\"]");
        assertEquals(1, pins.size());
        assertEquals("x", pins.get(0).label());
    }

    @Test
    void pinsAreFoundAmidSurroundingProseAndMarkup() {
        // the parser's T4 contract: it scans RAW entry text, where pins sit
        // inside a [map] body surrounded by arbitrary other content
        List<MapPins.Pin> pins = MapPins.parse(
                "intro <b>text</b> [map route=\"true\"]\n"
                        + "[pin lat=\"1\" lng=\"2\" label=\"A\"]\n"
                        + "[/map] outro");
        assertEquals(1, pins.size());
        assertEquals("A", pins.get(0).label());
    }

    @Test
    void aBlankLabelBecomesNull() {
        assertNull(MapPins.parse("[pin lat=\"1\" lng=\"2\" label=\"  \"]").get(0).label());
    }

    // ------------------------------------------------------------- malformed

    @Test
    void pinsMissingEitherCoordinateAreSkipped() {
        assertTrue(MapPins.parse("[pin lat=\"1\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lng=\"2\"]").isEmpty());
        assertTrue(MapPins.parse("[pin label=\"no coords\"]").isEmpty());
    }

    @Test
    void pinsWithUnparseableCoordinatesAreSkipped() {
        assertTrue(MapPins.parse("[pin lat=\"north\" lng=\"2\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"1\" lng=\"\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"NaN\" lng=\"2\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"Infinity\" lng=\"2\"]").isEmpty());
    }

    @Test
    void pinsOutsideTheValidCoordinateRangesAreSkipped() {
        assertTrue(MapPins.parse("[pin lat=\"90.1\" lng=\"0\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"-90.1\" lng=\"0\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"0\" lng=\"180.1\"]").isEmpty());
        assertTrue(MapPins.parse("[pin lat=\"0\" lng=\"-180.1\"]").isEmpty());
        // the boundary itself is valid
        assertEquals(1, MapPins.parse("[pin lat=\"90\" lng=\"-180\"]").size());
    }

    @Test
    void aMalformedPinAmongValidOnesIsSkippedNotFatal() {
        List<MapPins.Pin> pins = MapPins.parse(
                "[pin lat=\"1\" lng=\"2\" label=\"good\"]"
                        + "[pin lat=\"oops\" lng=\"2\"]"
                        + "[pin lat=\"3\" lng=\"4\" label=\"also good\"]");
        assertEquals(2, pins.size());
        assertEquals("good", pins.get(0).label());
        assertEquals("also good", pins.get(1).label());
    }

    @Test
    void escapedPinSyntaxMentionedInProseIsNotAPin() {
        // [[pin ...]] is how prose mentions the syntax without invoking it
        assertTrue(MapPins.parse("write [[pin lat=\"1\" lng=\"2\"]] to add a pin").isEmpty());
    }

    @Test
    void nullEmptyAndPinlessTextParseToNoPins() {
        assertTrue(MapPins.parse(null).isEmpty());
        assertTrue(MapPins.parse("").isEmpty());
        assertTrue(MapPins.parse("no pins here, just [gallery dir=\"x\"]").isEmpty());
        // a name that merely starts with "pin" is a different (unknown) tag
        assertTrue(MapPins.parse("[pins lat=\"1\" lng=\"2\"]").isEmpty());
    }
}
