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
package com.wikantik.pressroom.ui.rendering;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery headline's label in both templates: "Most read lately" only
 * when the headline was ranked by traffic, "Newest" when it is merely the
 * newest post. The real-tier renders ({@code DiscoveryFrontPageRenderingTest})
 * have no Umami and so only ever see the unranked label; this evaluates each
 * template alone, with a hand-built {@code $discovery}, to reach both.
 * Macros and references the templates need beyond the front page stay
 * unresolved and print as text, which no assertion here reads.
 */
class DiscoveryHeadlineLabelTest {

    private static final String RANKED = "Most read lately";
    private static final String NEWEST = "Newest";

    @ParameterizedTest
    @ValueSource(strings = {"src/main/webapp/themes/frontpage/discovery.vm",
            "src/main/webapp/WEB-INF/velocity/templates/weblog/discovery.vm"})
    void aRankedHeadlineIsMostRead(String template) throws Exception {
        String lead = headlineSection(render(template, true));

        assertTrue(lead.contains(">" + RANKED + "</h2>"), template + ":\n" + lead);
        assertFalse(lead.contains(NEWEST), template + ":\n" + lead);
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/webapp/themes/frontpage/discovery.vm",
            "src/main/webapp/WEB-INF/velocity/templates/weblog/discovery.vm"})
    void anUnrankedHeadlineIsNewest(String template) throws Exception {
        String lead = headlineSection(render(template, false));

        assertTrue(lead.contains(">" + NEWEST + "</h2>"), template + ":\n" + lead);
        assertFalse(lead.contains(RANKED), template + ":\n" + lead);
    }

    private static String headlineSection(String body) {
        int start = body.indexOf("class=\"fd-discovery-headline\"");
        assertTrue(start >= 0, "no headline section:\n" + body);
        return body.substring(start, body.indexOf("</section>", start));
    }

    private static String render(String template, boolean ranked) throws Exception {
        Map<String, Object> blog = Map.of("absoluteURL", "https://example.test/blog/", "name", "A blog");
        Map<String, Object> headline = Map.of("title", "The lead", "permalink",
                "https://example.test/blog/entry/lead", "website", blog);
        Map<String, Object> frontPage = new HashMap<>();
        frontPage.put("headline", headline);
        frontPage.put("headlineRanked", ranked);
        frontPage.put("popular", List.of());
        frontPage.put("blogs", List.of());
        frontPage.put("different", List.of());
        frontPage.put("allBlogs", List.of(blog));

        VelocityEngine engine = new VelocityEngine();
        engine.init();
        VelocityContext context = new VelocityContext();
        context.put("discovery", Map.of("frontPage", frontPage));
        context.put("text", new StubText());
        context.put("utils", new StubUtils());
        StringWriter out = new StringWriter();
        engine.evaluate(context, out, template, Files.readString(Path.of(template), StandardCharsets.UTF_8));
        return out.toString();
    }

    /** {@code $text}: the bundle, no arguments. */
    public static final class StubText {
        private final ResourceBundle bundle = ResourceBundle.getBundle("ApplicationResources");

        public String get(String key) {
            return bundle.getString(key);
        }
    }

    /** {@code $utils}: just the escaping the sections use. */
    public static final class StubUtils {
        public String escapeHTML(String value) {
            return StringEscapeUtils.escapeHtml4(value);
        }
    }
}
