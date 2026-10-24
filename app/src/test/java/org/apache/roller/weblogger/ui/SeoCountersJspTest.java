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
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry editor's SEO drawer counts the characters of the meta title and
 * the search description against {@code SeoLimits}, the numbers the audits
 * use. Source-level, like {@link EditorSurfaceJspTest}: the JSP is not
 * rendered here.
 */
class SeoCountersJspTest {

    private static String jsp() throws IOException {
        return Files.readString(
                Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp"), StandardCharsets.UTF_8);
    }

    private static String element(String jsp, String id) {
        int at = jsp.indexOf("id=\"" + id + "\"");
        assertTrue(at > 0, id + " must exist");
        return jsp.substring(jsp.lastIndexOf('<', at), jsp.indexOf('>', at) + 1);
    }

    @Test
    void eachFieldHasAPoliteLiveCountCarryingItsLimitsFromTheModel() throws IOException {
        String jsp = jsp();
        String title = element(jsp, "seo_metaTitle_count");
        assertTrue(title.contains("aria-live=\"polite\""), title);
        assertTrue(title.contains("data-max=\"${seoTitleMax}\""), title);
        assertTrue(title.contains("data-weblog-name=\"${fn:escapeXml(actionWeblog.name)}\""), title);

        String desc = element(jsp, "seo_metaDescription_count");
        assertTrue(desc.contains("aria-live=\"polite\""), desc);
        assertTrue(desc.contains("data-max=\"${seoDescriptionMax}\""), desc);
        assertTrue(desc.contains("data-min=\"${seoDescriptionMin}\""), desc);
    }

    @Test
    void theInputsAreDescribedByTheirCounts() throws IOException {
        String jsp = jsp();
        assertTrue(element(jsp, "seo_metaTitle").contains("aria-describedby=\"seo_metaTitle_count\""));
        assertTrue(element(jsp, "seo_metaDescription").contains("aria-describedby=\"seo_metaDescription_count\""));
    }

    @Test
    void noLimitIsALiteralAndTheWarningsAreMessages() throws IOException {
        String jsp = jsp();
        int from = jsp.indexOf("function updateSeoCounts");
        assertTrue(from > 0, "the count script must exist");
        String js = jsp.substring(from, jsp.indexOf("$(document).ready", from));
        assertFalse(js.contains("60") || js.contains("160") || js.contains("50"),
                "limits come from data attributes, never literals: " + js);
        assertTrue(js.contains("data('max')") && js.contains("data('min')"), js);
        for (String key : new String[]{"weblogEdit.seoCount.over", "weblogEdit.seoCount.short",
                "weblogEdit.seoCount.title", "weblogEdit.seoCount.description"}) {
            assertTrue(jsp.contains(key), key);
        }
        assertFalse(jsp.contains("onclick=\"return confirm"), "no inline handlers");
    }
}
