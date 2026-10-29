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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;

import static com.wikantik.pressroom.business.jsonld.JsonLdWriter.put;

/**
 * Builds the schema.org node for a blog's business and its optional place.
 *
 * <p>A blog that declares a place ({@code LodgingBusiness}) emits the place,
 * nesting its business as {@code parentOrganization}; a blog with only a
 * business emits the business; a blog with neither emits nothing. The same
 * business is also the {@code publisher} of every BlogPosting.
 *
 * <p>Blank fields are omitted, never written as empty strings or empty
 * arrays. The place carries locality-level address fields only, because a
 * guesthouse's street address is not what the blog is publishing. Values are
 * handed to {@link JsonLdWriter}, the single place they are escaped, so they
 * must be RAW here.
 */
public final class BusinessJsonLd {

    private static final String CONTEXT = "https://schema.org";

    private BusinessJsonLd() {
        // static use only
    }

    /** The {@code @id} of a business node, shared by every page that names it. */
    private static String idOf(Business b, String siteUrl) {
        return siteUrl + "#business-" + b.getId();
    }

    /** Business node, or an empty map when weblog has no business. siteUrl = absolute site URL. */
    static Map<String, Object> businessNode(Business b, String siteUrl) {
        if (b == null) {
            return Map.of();
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", CONTEXT);
        node.put("@type", b.getBusinessType() == null
                ? Business.BusinessType.Organization.name() : b.getBusinessType().name());
        node.put("@id", idOf(b, siteUrl));
        put(node, "name", StringUtils.trimToNull(b.getName()));
        put(node, "url", StringUtils.trimToNull(b.getWebsiteUrl()));
        put(node, "telephone", StringUtils.trimToNull(b.getTelephone()));
        put(node, "email", StringUtils.trimToNull(b.getEmail()));
        put(node, "logo", StringUtils.trimToNull(b.getLogoUrl()));
        List<String> sameAs = b.getSameAsList();
        if (!sameAs.isEmpty()) {
            node.put("sameAs", sameAs);
        }
        put(node, "areaServed", StringUtils.trimToNull(b.getAreaServed()));
        put(node, "description", StringUtils.trimToNull(b.getDescription()));
        return node;
    }

    /** Place node with parentOrganization, or an empty map when placeType is blank. */
    static Map<String, Object> placeNode(Weblog w, String weblogAbsoluteUrl, String siteUrl) {
        String type = StringUtils.trimToNull(w.getPlaceType());
        if (type == null) {
            return Map.of();
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", CONTEXT);
        node.put("@type", type);
        put(node, "name", StringUtils.trimToNull(w.getName()));
        put(node, "url", StringUtils.trimToNull(weblogAbsoluteUrl));

        Map<String, Object> address = new LinkedHashMap<>();
        put(address, "addressLocality", StringUtils.trimToNull(w.getPlaceLocality()));
        put(address, "addressRegion", StringUtils.trimToNull(w.getPlaceRegion()));
        put(address, "addressCountry", StringUtils.trimToNull(w.getPlaceCountry()));
        if (!address.isEmpty()) {
            Map<String, Object> postal = new LinkedHashMap<>();
            postal.put("@type", "PostalAddress");
            postal.putAll(address);
            node.put("address", postal);
        }
        if (w.getPlaceLat() != null && w.getPlaceLng() != null) {
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("@type", "GeoCoordinates");
            geo.put("latitude", w.getPlaceLat());
            geo.put("longitude", w.getPlaceLng());
            node.put("geo", geo);
        }
        Map<String, Object> parent = businessNode(w.getBusiness(), siteUrl);
        if (!parent.isEmpty()) {
            parent.remove("@context");
            node.put("parentOrganization", parent);
        }
        return node;
    }

    /** Complete JSON for the non-entry block: place if present, else business, else null. */
    public static String forWeblog(Weblog w, String weblogAbsoluteUrl, String siteUrl) {
        Map<String, Object> node = placeNode(w, weblogAbsoluteUrl, siteUrl);
        if (node.isEmpty()) {
            node = businessNode(w.getBusiness(), siteUrl);
        }
        return node.isEmpty() ? null : JsonLdWriter.write(node);
    }

    /** JSON object (no @context) for BlogPosting.publisher, or null. */
    public static String publisherFor(Weblog w, String siteUrl) {
        Map<String, Object> node = businessNode(w.getBusiness(), siteUrl);
        if (node.isEmpty()) {
            return null;
        }
        node.remove("@context");
        return JsonLdWriter.write(node);
    }
}
