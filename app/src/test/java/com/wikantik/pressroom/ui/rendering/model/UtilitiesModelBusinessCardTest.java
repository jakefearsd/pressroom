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
package com.wikantik.pressroom.ui.rendering.model;

import java.util.Map;

import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which name and locality line the footer card shows. Per the spec, a blog
 * with a place shows the place's name (the blog name) and its locality line;
 * otherwise the business name and area served. "Has a place" means a place
 * type is set -- the same test the JSON-LD place node uses -- so the card
 * never shows a locality the structured data does not describe.
 */
class UtilitiesModelBusinessCardTest {

    private final UtilitiesModel utils = new UtilitiesModel();

    private static Business business(String name, String areaServed) {
        Business b = new Business();
        b.setName(name);
        b.setAreaServed(areaServed);
        return b;
    }

    private static Weblog weblog(String name) {
        Weblog w = new Weblog();
        w.setHandle("cardblog");
        w.setName(name);
        return w;
    }

    @Test
    void withAPlaceTheCardShowsThePlaceNameAndLocalityNotTheBusiness() {
        Weblog w = weblog("Sea View Rooms");
        w.setBusiness(business("Casa Azul Group", "Azores"));
        w.setPlaceType("LodgingBusiness");
        w.setPlaceLocality("Ponta Delgada");
        w.setPlaceRegion("Sao Miguel");

        Map<String, String> card = utils.businessCard(w, null);

        assertEquals("Sea View Rooms", card.get("name"));
        assertEquals("Ponta Delgada, Sao Miguel", card.get("locality"));
    }

    @Test
    void withoutAPlaceTheCardShowsTheBusinessNameAndAreaServed() {
        Weblog w = weblog("Sea View Rooms");
        w.setBusiness(business("Casa Azul Group", "Azores"));

        Map<String, String> card = utils.businessCard(w, null);

        assertEquals("Casa Azul Group", card.get("name"));
        assertEquals("Azores", card.get("locality"));
    }

    @Test
    void aLocalityWithoutAPlaceTypeIsNotAPlace() {
        Weblog w = weblog("Sea View Rooms");
        w.setBusiness(business("Casa Azul Group", "Azores"));
        w.setPlaceLocality("Ponta Delgada");
        w.setPlaceRegion("Sao Miguel");

        Map<String, String> card = utils.businessCard(w, null);

        assertEquals("Casa Azul Group", card.get("name"));
        assertEquals("Azores", card.get("locality"),
                "no place node in the JSON-LD, so no place locality on the card");
    }

    @Test
    void aLocalityWithoutAPlaceTypeOrBusinessGivesNoCard() {
        Weblog w = weblog("Sea View Rooms");
        w.setPlaceLocality("Ponta Delgada");

        assertEquals(Map.of(), utils.businessCard(w, null));
    }

    @Test
    void aPlaceWithoutABusinessShowsThePlace() {
        Weblog w = weblog("Sea View Rooms");
        w.setPlaceType("LodgingBusiness");
        w.setPlaceLocality("Ponta Delgada");

        Map<String, String> card = utils.businessCard(w, null);

        assertEquals(Map.of("name", "Sea View Rooms", "locality", "Ponta Delgada"), card);
    }

    private Map<String, String> cardWithPhone(String phone) {
        Weblog w = weblog("Sea View Rooms");
        Business b = business("Casa Azul Group", "Azores");
        b.setTelephone(phone);
        w.setBusiness(b);
        return utils.businessCard(w, null);
    }

    /**
     * An extension is RFC 3966 ";ext=": folding its digits into the number
     * dials a wrong line. The text shown stays exactly as typed.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "+1 555 123 4567 ext. 89|tel:+15551234567;ext=89",
            "+1 555 123 4567 EXT 89|tel:+15551234567;ext=89",
            "+1 555 123 4567 extension 89|tel:+15551234567;ext=89",
            "(555) 123-4567 x89|tel:5551234567;ext=89",
            "555.123.4567 X 89|tel:5551234567;ext=89",
            "+1 555 123 4567|tel:+15551234567",
            "(555) 123-4567|tel:5551234567"})
    void aTelephoneExtensionBecomesAnRfc3966ExtParameter(String typed, String href) {
        Map<String, String> card = cardWithPhone(typed);

        assertEquals(href, card.get("telHref"));
        assertEquals(typed, card.get("telephone"));
    }

    @Test
    void anExtensionWithNoDigitsIsDroppedNotEmitted() {
        assertEquals("tel:5551234567", cardWithPhone("555 123 4567 ext.").get("telHref"));
    }
}
