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
package com.wikantik.pressroom.business.jsonld;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The business and place JSON-LD nodes: shape, sparseness, escaping, URLs. */
class BusinessJsonLdTest {

    private static final String SITE = "https://site.example/";
    private static final String BLOG = "https://blog.example/guide";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Business business(String name) {
        Business b = new Business();
        b.setName(name);
        return b;
    }

    private static Weblog place(Business b) {
        Weblog w = new Weblog();
        w.setName("Casa do Mar");
        w.setBusiness(b);
        w.setPlaceType("LodgingBusiness");
        w.setPlaceLocality("Ponta Delgada");
        w.setPlaceRegion("Azores");
        w.setPlaceCountry("PT");
        w.setPlaceLat(new BigDecimal("37.7412"));
        w.setPlaceLng(new BigDecimal("-25.6756"));
        return w;
    }

    private static Set<String> keys(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    @Test
    void fullProfessionalService() {
        Business b = business("Maiia Photography");
        b.setBusinessType(Business.BusinessType.ProfessionalService);
        b.setWebsiteUrl("https://maiia.example");
        b.setTelephone("+351 912 345 678");
        b.setEmail("hi@maiia.example");
        b.setLogoUrl("https://maiia.example/logo.png");
        b.setSameAs("https://b.example/y\nhttps://a.example/x");
        b.setAreaServed("Azores");
        b.setDescription("Photographer.");
        Weblog w = new Weblog();
        w.setBusiness(b);

        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(w, BLOG, SITE));

        assertEquals(Set.of("@context", "@type", "@id", "name", "url", "telephone",
                "email", "logo", "sameAs", "areaServed", "description"), keys(node));
        assertEquals("ProfessionalService", node.path("@type").asString());
        assertEquals(SITE + "#business-" + b.getId(), node.path("@id").asString());
        assertEquals("https://maiia.example", node.path("url").asString());
        List<String> sameAs = new ArrayList<>();
        node.path("sameAs").forEach(n -> sameAs.add(n.asString()));
        assertEquals(List.of("https://b.example/y", "https://a.example/x"), sameAs);
    }

    @Test
    void scriptBreakoutIsEscaped() {
        String name = "Ana </script><b>\"x\"</b>";
        Weblog w = new Weblog();
        w.setBusiness(business(name));

        String json = BusinessJsonLd.forWeblog(w, BLOG, SITE);

        assertFalse(json.contains("</script>"), json);
        assertEquals(name, MAPPER.readTree(json).path("name").asString());
    }

    /**
     * Inside a script element, "<!--<script" moves the HTML parser into the
     * script-data double-escaped state, where the closing </script> no longer
     * ends the block and the rest of the page is swallowed. No '<' reaches
     * the page at all: it is written as the JSON escape u003c.
     */
    @Test
    void anOpeningAngleBracketNeverReachesThePage() {
        String name = "Ana <!--<script> x";
        Weblog w = new Weblog();
        w.setBusiness(business(name));
        w.setName("Sea View"); // Weblog.setName strips markup itself
        w.setPlaceType("LodgingBusiness");
        w.setPlaceLocality("<!--<script>");

        String json = BusinessJsonLd.forWeblog(w, BLOG, SITE);

        assertFalse(json.contains("<"), json);
        JsonNode node = MAPPER.readTree(json);
        assertEquals("<!--<script>", node.path("address").path("addressLocality").asString());
        assertEquals(name, node.path("parentOrganization").path("name").asString());
    }

    @Test
    void sparseBusiness() {
        Weblog w = new Weblog();
        w.setBusiness(business("Only A Name"));

        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(w, BLOG, SITE));

        assertEquals(Set.of("@context", "@type", "@id", "name"), keys(node));
    }

    @Test
    void nonAsciiRoundTrips() {
        Business b = business("São Miguel, Açores");
        Weblog w = place(b);
        w.setPlaceLocality("São Miguel, Açores");

        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(w, BLOG, SITE));

        assertEquals("São Miguel, Açores",
                node.path("parentOrganization").path("name").asString());
        assertEquals("São Miguel, Açores",
                node.path("address").path("addressLocality").asString());
    }

    @Test
    void placeIsLocalityOnly() {
        Business b = business("Casa");
        Weblog w = place(b);

        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(w, BLOG, SITE));

        assertEquals("LodgingBusiness", node.path("@type").asString());
        assertEquals(Set.of("@type", "addressLocality", "addressRegion", "addressCountry"),
                keys(node.path("address")));
        assertEquals(37.74, node.path("geo").path("latitude").asDouble(), 1e-9);
        assertEquals(-25.68, node.path("geo").path("longitude").asDouble(), 1e-9);
        assertEquals(SITE + "#business-" + b.getId(),
                node.path("parentOrganization").path("@id").asString());
    }

    @Test
    void placeWithoutGeoOmitsIt() {
        Weblog w = place(null);
        w.setPlaceLng(null);

        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(w, BLOG, SITE));

        assertFalse(node.has("geo"));
    }

    @Test
    void placeWithoutBusiness() {
        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(place(null), BLOG, SITE));

        assertEquals("LodgingBusiness", node.path("@type").asString());
        assertFalse(node.has("parentOrganization"));
    }

    @Test
    void placeUrlIsTheWeblogUrl() {
        JsonNode node = MAPPER.readTree(BusinessJsonLd.forWeblog(place(null), BLOG, SITE));

        assertEquals(BLOG, node.path("url").asString());
    }

    @Test
    void nothingToSayYieldsNull() {
        Weblog w = new Weblog();

        assertNull(BusinessJsonLd.forWeblog(w, BLOG, SITE));
        assertNull(BusinessJsonLd.publisherFor(w, SITE));
        assertTrue(BusinessJsonLd.businessNode(null, SITE).isEmpty());
        assertTrue(BusinessJsonLd.placeNode(w, BLOG, SITE).isEmpty());
    }

    @Test
    void publisherIsTheBusinessWithoutContext() {
        Business b = business("Casa");
        Weblog w = new Weblog();
        w.setBusiness(b);

        JsonNode node = MAPPER.readTree(BusinessJsonLd.publisherFor(w, SITE));

        assertFalse(node.has("@context"));
        assertEquals("Casa", node.path("name").asString());
        assertNotNull(node.path("@type").asString());
        assertTrue(node.has("@id"));
    }
}
