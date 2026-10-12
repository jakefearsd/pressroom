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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The business section of the blog settings form, checked on the JSP source:
 * what it posts when the business list could not load, and the input limits
 * that match the place columns.
 */
class WeblogConfigBusinessJspTest {

    private static final Path JSP = Path.of(
            "src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp");

    private static String read() throws IOException {
        return Files.readString(JSP, StandardCharsets.UTF_8);
    }

    /**
     * With no list to choose from, a select offering only "None" would post
     * an empty id and the save would unlink the business. The form instead
     * carries the current id in a hidden input and disables the select.
     */
    @Test
    void whenTheBusinessListIsUnavailableTheFormCarriesTheCurrentBusiness() throws IOException {
        Matcher branch = Pattern.compile(
                "<c:when test=\"\\$\\{businessesUnavailable}\">(.*?)</c:when>\\s*\\n\\s*<c:otherwise>",
                Pattern.DOTALL)
                .matcher(read());
        assertTrue(branch.find(), "the business field needs a businessesUnavailable branch");
        String body = branch.group(1);
        assertTrue(body.contains("<input type=\"hidden\" name=\"bean.businessId\" "
                        + "value=\"${fn:escapeXml(bean.businessId)}\"/>"),
                "the branch must post the current business id: " + body);
        assertTrue(body.contains("disabled=\"disabled\""), "the select must be disabled: " + body);
        assertTrue(!body.contains("name=\"bean.businessId\" class"),
                "only the hidden input may post bean.businessId: " + body);
    }

    @Test
    void localityAndRegionAreLimitedToTheirColumnLength() throws IOException {
        String jsp = read();
        for (String field : new String[] {"placeLocality", "placeRegion"}) {
            Matcher input = Pattern.compile("<input id=\"weblog_bean_" + field + "\"[^>]*>").matcher(jsp);
            assertTrue(input.find(), field + " input not found");
            assertTrue(input.group().contains("maxlength=\"128\""), input.group());
        }
    }

    @Test
    void theSettingsFormOffersTheTwoCommentCheckboxes() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("name=\"bean.commentsEnabled\""), "commentsEnabled checkbox");
        assertTrue(jsp.contains("name=\"bean.commentNotify\""), "commentNotify checkbox");
        assertTrue(jsp.contains("code=\"weblogSettings.commentsEnabled\""));
        assertTrue(jsp.contains("code=\"weblogSettings.commentNotify\""));
    }
}
