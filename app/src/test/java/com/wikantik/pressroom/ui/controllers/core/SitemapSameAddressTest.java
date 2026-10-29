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
package com.wikantik.pressroom.ui.controllers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sitemap's "is this canonical the item itself" comparison, case by case. */
class SitemapSameAddressTest {

    @Test
    void spellingDifferencesThatNameTheSamePageAgree() {
        assertTrue(SeoController.sameAddress("https://b.example.com/x/", "http://b.example.com/x"));
        assertTrue(SeoController.sameAddress("https://B.example.com:443/x", "http://b.example.com:80/x"));
        assertTrue(SeoController.sameAddress("http://b.example.com/%78", "http://b.example.com/x"));
        assertTrue(SeoController.sameAddress("http://b.example.com", "http://b.example.com/"));
    }

    @Test
    void aDifferentHostPortPathOrQueryIsElsewhere() {
        assertFalse(SeoController.sameAddress("http://c.example.com/x", "http://b.example.com/x"));
        assertFalse(SeoController.sameAddress("http://b.example.com:8080/x", "http://b.example.com/x"));
        assertFalse(SeoController.sameAddress("http://b.example.com/y", "http://b.example.com/x"));
        assertFalse(SeoController.sameAddress("http://b.example.com/x?p=1", "http://b.example.com/x"));
    }

    @Test
    void aValueThatDoesNotParseIsComparedAsWritten() {
        assertFalse(SeoController.sameAddress("http://b.example.com/a b", "http://b.example.com/a%20b"));
        assertTrue(SeoController.sameAddress("http://b.example.com/a b", "http://b.example.com/a b"));
    }
}
