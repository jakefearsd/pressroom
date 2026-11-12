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
package com.wikantik.pressroom.business.jsonld;

import java.util.Arrays;
import java.util.List;

import com.wikantik.pressroom.business.jsonld.BreadcrumbJsonLd.Crumb;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The post page's BreadcrumbList: shape, sparseness and escaping (0.9.1, criterion 12). */
class BreadcrumbJsonLdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Crumb SITE = new Crumb("Wikantik", "https://site.example/");
    private static final Crumb BLOG = new Crumb("Azores Guide", "https://site.example/azores/");
    private static final Crumb CATEGORY = new Crumb("Hikes", "https://site.example/azores/category/Hikes");
    private static final Crumb POST = new Crumb("Sete Cidades", "https://site.example/azores/entry/sete");

    @Test
    void theTrailIsAnOrderedListOfNamedAbsoluteItems() {
        JsonNode node = MAPPER.readTree(BreadcrumbJsonLd.build(List.of(SITE, BLOG, CATEGORY, POST)));

        assertEquals("https://schema.org", node.path("@context").asString());
        assertEquals("BreadcrumbList", node.path("@type").asString());
        JsonNode items = node.path("itemListElement");
        assertEquals(4, items.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("ListItem", items.get(i).path("@type").asString());
            assertEquals(i + 1, items.get(i).path("position").asInt());
        }
        assertEquals("Wikantik", items.get(0).path("name").asString());
        assertEquals("https://site.example/", items.get(0).path("item").asString());
        assertEquals("Hikes", items.get(2).path("name").asString());
        assertEquals("https://site.example/azores/entry/sete", items.get(3).path("item").asString());
    }

    @Test
    void aCrumbWithoutANameOrUrlIsLeftOutAndTheRestRenumbered() {
        JsonNode items = MAPPER.readTree(BreadcrumbJsonLd.build(Arrays.asList(
                SITE, null, new Crumb(" ", "https://site.example/x"), new Crumb("No link", null), POST)))
                .path("itemListElement");

        assertEquals(2, items.size());
        assertEquals("Sete Cidades", items.get(1).path("name").asString());
        assertEquals(2, items.get(1).path("position").asInt());
    }

    /** A blog that is itself the site's front page is one step, not two. */
    @Test
    void twoNeighbouringCrumbsAtOneAddressAreOneStep() {
        JsonNode items = MAPPER.readTree(BreadcrumbJsonLd.build(List.of(
                SITE, new Crumb("Azores Guide", "https://site.example/"), POST))).path("itemListElement");

        assertEquals(2, items.size());
        assertEquals("Wikantik", items.get(0).path("name").asString());
        assertEquals("Sete Cidades", items.get(1).path("name").asString());
    }

    @Test
    void aTrailOfFewerThanTwoStepsIsNoTrail() {
        assertNull(BreadcrumbJsonLd.build(List.of(POST)));
        assertNull(BreadcrumbJsonLd.build(List.of()));
        assertNull(BreadcrumbJsonLd.build(null));
    }

    @Test
    void aNameCannotBreakOutOfTheScriptElementOrTheString() {
        String hostile = "He said \"hi\" </script><script>alert(1)</script> <!--<script";
        String json = BreadcrumbJsonLd.build(List.of(SITE, new Crumb(hostile, "https://site.example/a?b=\"c")));

        assertFalse(json.contains("</"), json);
        assertFalse(json.contains("<"), json);
        JsonNode last = MAPPER.readTree(json).path("itemListElement").get(1);
        assertEquals(hostile, last.path("name").asString());
        assertEquals("https://site.example/a?b=\"c", last.path("item").asString());
    }
}
