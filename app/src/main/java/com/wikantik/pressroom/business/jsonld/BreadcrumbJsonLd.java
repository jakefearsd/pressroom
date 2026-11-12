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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Builds the schema.org {@code BreadcrumbList} a post page emits: where the
 * post sits, from the site's front page down to the post itself.
 *
 * <p>Built in Java for the reasons {@link EntryJsonLd} gives: the trail has a
 * variable number of steps (a template would comma-join them by hand), and
 * every name and address goes through {@link JsonLdWriter}'s one escape, so a
 * title holding a quote or {@code </script>} cannot end its string or the
 * script element. Callers hand over RAW text, never pre-escaped.
 */
public final class BreadcrumbJsonLd {

    /** One step of the trail: its visible name and its absolute address, both raw. */
    public record Crumb(String name, String url) { }

    private BreadcrumbJsonLd() {
        // static use only
    }

    /**
     * The trail as one JSON object, or null when fewer than two steps are
     * left: a trail of one says nothing. A step without a name or an address
     * is left out, and so is a step at the same address as the one before it
     * (a blog that is itself the site's front page is one step, not two);
     * positions count the steps that remain.
     */
    public static String build(List<Crumb> trail) {
        List<Object> items = new ArrayList<>();
        String previousUrl = null;
        for (Crumb crumb : trail == null ? List.<Crumb>of() : trail) {
            if (crumb == null || StringUtils.isBlank(crumb.name()) || StringUtils.isBlank(crumb.url())
                    || crumb.url().equals(previousUrl)) {
                continue;
            }
            previousUrl = crumb.url();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "ListItem");
            item.put("position", items.size() + 1);
            item.put("name", crumb.name().trim());
            item.put("item", crumb.url());
            items.add(item);
        }
        if (items.size() < 2) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", "https://schema.org");
        node.put("@type", "BreadcrumbList");
        node.put("itemListElement", items);
        return JsonLdWriter.write(node);
    }
}
