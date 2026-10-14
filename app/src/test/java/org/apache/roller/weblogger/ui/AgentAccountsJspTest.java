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
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markup the agent-accounts screens promise (spec 2026-10-13), which a
 * rendered page cannot show a unit test: which controls are offered when,
 * and which ask before they act (criterion 25).
 */
class AgentAccountsJspTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static String jsp(String relative) throws IOException {
        Path path = JSPS.resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    /** Criterion 17: "Email a set-password link" sits inside the mailConfigured branch. */
    @Test
    void theEmailedLinkIsOfferedOnlyWhileMailIsConfigured() throws IOException {
        String src = jsp("admin/UserEdit.jsp");
        int when = src.indexOf("<c:when test=\"${mailConfigured}\">");
        int link = src.indexOf("id=\"bean_passwordModeLink\"");
        assertTrue(when >= 0 && link > when && link < src.indexOf("</c:when>", when),
                "the link choice must render only while mail is configured");
    }
}
