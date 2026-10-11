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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text scan (the {@code SetupJspTest} style) of the front-page weblog
 * menu on Global Config. Its first option must be an enabled, empty "None":
 * that is the choice that shows the site directory at {@code /}, it keeps the
 * field in the submission when no weblog exists yet, and with nothing stored
 * it is what the browser selects, so saving an unrelated setting can never
 * quietly make the first weblog the front page.
 */
class GlobalConfigJspTest {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/admin/GlobalConfig.jsp");

    @Test
    void theFrontPageMenuOffersAnEnabledNoneFirst() throws IOException {
        String source = Files.readString(JSP, StandardCharsets.UTF_8);
        int selectStart = source.indexOf("<select name=\"${fn:escapeXml(pd.name)}\"");
        assertTrue(selectStart >= 0, "Expected the front-page <select> in " + JSP);
        String select = source.substring(selectStart, source.indexOf("</select>", selectStart));

        int firstOption = select.indexOf("<option");
        assertTrue(firstOption >= 0, "the front-page menu has no <option> at all");
        assertTrue(firstOption < select.indexOf("<c:forEach"),
                "the None option must come before the weblogs, or the browser defaults to a weblog");
        String none = select.substring(firstOption, select.indexOf("</option>", firstOption));
        assertTrue(none.contains("value=\"\""), "the first option must be the empty None: " + none);
        assertFalse(none.contains("disabled"),
                "None is a real choice here (the site directory), and a disabled option is "
                        + "never submitted: " + none);
    }
}
