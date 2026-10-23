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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10/M11 in the virtual-host final review: {@code WeblogConfig.jsp}'s
 * settings rail is a table of contents for the form's own section headings.
 *
 * <p>M11: every {@code #settings-<x>} heading id must have a matching
 * {@code href="#settings-<x>"} link in the rail's {@code #settingsSectionIndex}
 * nav, or the scroll-spy cannot see that section -- generalised across every
 * section rather than pinned to the custom-domain one alone, so a future
 * section that forgets the rail link fails this too.
 *
 * <p>M9/M10: the custom-domain section reused {@code websiteSettings.customDomain}
 * for BOTH its section heading and its field {@code <label>}, so "Custom
 * domain" rendered twice, stacked -- every sibling section already used a
 * distinct {@code *Settings} key for its heading, separate from any field
 * label beneath it.
 */
class WeblogConfigSettingsRailTest {

    private static final Path JSP = Path.of(
            "src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp");

    private static String read() throws IOException {
        return Files.readString(JSP, StandardCharsets.UTF_8);
    }

    @Test
    void everySectionHeadingHasAMatchingRailLink() throws IOException {
        String jsp = read();
        Matcher headings = Pattern.compile(
                "<h2 class=\"section-head\" id=\"(settings-[a-z]+)\"").matcher(jsp);
        List<String> ids = new ArrayList<>();
        while (headings.find()) {
            ids.add(headings.group(1));
        }
        assertTrue(ids.size() >= 5,
                "expected at least the five known settings sections, found: " + ids);

        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!jsp.contains("href=\"#" + id + "\"")) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(),
                "these section headings have no matching link in the settings rail, so the "
                        + "scroll-spy cannot see them: " + missing);
    }

    @Test
    void theCustomDomainSectionHeadingUsesItsOwnMessageCodeNotTheFieldLabels() throws IOException {
        String jsp = read();

        assertTrue(jsp.contains("id=\"settings-customdomain\">"
                        + "<spring:message code=\"websiteSettings.customDomainSettings\"/></h2>"),
                "the custom-domain section heading must use its own *Settings message code, "
                        + "the same way every sibling section heading does, rather than the "
                        + "field label's code -- otherwise \"Custom domain\" renders twice, stacked");

        // Matched by the two things that carry the meaning -- the label's own
        // message code, and the field it binds to -- rather than the exact
        // attribute string, which the label-binding sweep of 2026-08-20
        // rightly changed by adding for="weblog_bean_customDomain".
        assertTrue(jsp.contains("<label class=\"col-sm-3 col-form-label\" "
                        + "for=\"weblog_bean_customDomain\">"
                        + "<spring:message code=\"websiteSettings.customDomain\"/></label>"),
                "the custom-domain field label must still use websiteSettings.customDomain "
                        + "and must still bind to the custom-domain input");
    }
}
