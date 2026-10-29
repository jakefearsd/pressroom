/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.business.BookingLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The field rules the Businesses admin screen and the blog place form share. */
class BusinessRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/a?b=c#d", "https://b.com/x?ref=a#dates"})
    void acceptsHttpAndHttpsUrls(String url) {
        assertTrue(BusinessRules.isHttpUrl(url));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.com", "example.com", "data:text/html,x",
            "https://", "not a url", "//example.com"})
    void rejectsAnythingElseAsAUrl(String url) {
        assertFalse(BusinessRules.isHttpUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.com", "first.last@sub.com.com"})
    void acceptsEmail(String email) {
        assertTrue(BusinessRules.isEmail(email));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"nope", "a@", "@b.com", "a b@c.com"})
    void rejectsBadEmail(String email) {
        assertFalse(BusinessRules.isEmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+351 912 345 678", "+351 912 345.678", "(555) 123-4567", "123",
            "+1 555 123 4567 ext. 89", "555-1234 x12", "555-1234 X 12", "555 1234 extension 123456",
            "555 1234ext9"})
    void acceptsTelephone(String phone) {
        assertTrue(BusinessRules.isTelephone(phone));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12", "call me", "+1 555 <script>", "1234567890123456789012345678901234",
            "555 ext", "555-CALL", "555 1234 ext. ", "555 1234 x1234567", "555 1234 ext 1a", "ext 12", "555\tx12", "555 1234\fx12", "555\u000Bx12",
            "12345678901234567890123 ext 12345"})
    void rejectsBadTelephone(String phone) {
        assertFalse(BusinessRules.isTelephone(phone));
    }

    @Test
    void sameAsLinesAreTrimmedAndBlanksDropped() {
        assertEquals(List.of("https://a.com", "https://b.com"),
                BusinessRules.sameAsLines("  https://a.com \r\n\n   \nhttps://b.com\n"));
    }

    @Test
    void sameAsLinesOfNothingIsEmpty() {
        assertTrue(BusinessRules.sameAsLines(null).isEmpty());
        assertTrue(BusinessRules.sameAsLines("  \n ").isEmpty());
    }

    @Test
    void sameAsCapIsTen() {
        assertEquals(10, BusinessRules.MAX_SAME_AS);
    }

    /**
     * Characterisation test, written before BusinessRules.isHttpUrl was made
     * to delegate to BookingLink.isHttpUrl and expected to pass against both
     * the old duplicate validator and the delegation: the settings form and
     * the Businesses screen must accept exactly the URLs a booking link
     * resolves to, no more and no fewer.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://book.example.com/stay", "http://casa.com", "HTTPS://CASA.COM/x?y=1#z",
            "https://b.example/x", "ftp://casa.com", "javascript:alert(1)", "//casa.com",
            "https://", "casa.com", "https://casa.com/a b", "mailto:hi@casa.com"})
    void agreesWithTheBookingLinkRule(String url) {
        assertEquals(BookingLink.isHttpUrl(url), BusinessRules.isHttpUrl(url), url);
    }
}
