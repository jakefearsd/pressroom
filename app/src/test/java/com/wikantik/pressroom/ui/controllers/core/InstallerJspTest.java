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
package com.wikantik.pressroom.ui.controllers.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans of the installer's pages, in the style of
 * {@code MaintenanceJspTest}: there is no JSP-rendering harness here.
 *
 * <p>The tables-ready pages offered two controls for one action (a "Click
 * here" GET link and a "Start Pressroom" button), the startup log rendered with
 * every line indented by the JSP's own whitespace inside {@code <pre>}, and
 * the "tables present, Pressroom not started" state showed "An unknown error
 * has occurred" although nothing had gone wrong.
 */
class InstallerJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps/core");
    private static final String BOOTSTRAP_FORM = "install!bootstrap.rol\" method=\"post\"";
    private static final String START_INCLUDE = "<jsp:include page=\"/WEB-INF/jsps/core/InstallStart.jsp\"/>";

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void theTablesReadyPagesOfferOneStartButtonAndNoClickHereLink() throws IOException {
        for (String page : List.of("CreateDatabase.jsp", "UpgradeDatabase.jsp")) {
            String jsp = read(JSPS.resolve(page));
            assertFalse(jsp.contains("installer.tryBootstrapping"),
                    page + " still renders the \"Click here\" link beside the button");
            assertEquals(1, jsp.split(Pattern.quote(START_INCLUDE), -1).length - 1,
                    page + " must offer the start button exactly once, from the shared fragment");
            assertFalse(jsp.contains(BOOTSTRAP_FORM), page + " builds its own start form beside the fragment");
        }
        String fragment = read(JSPS.resolve("InstallStart.jsp"));
        assertTrue(fragment.contains(BOOTSTRAP_FORM), "the fragment is one POST form that starts Pressroom");
        assertTrue(fragment.contains("installer.readyToStart"), "and says the tables are ready, without a link");
    }

    @Test
    void theStartupLogIsNotIndentedByTheJspsOwnWhitespace() throws IOException {
        Pattern indented = Pattern.compile("<pre>\\r?\\n?[ \\t]+<c:forEach");
        for (String page : List.of("CreateDatabase.jsp", "UpgradeDatabase.jsp")) {
            assertFalse(indented.matcher(read(JSPS.resolve(page))).find(),
                    page + ": whitespace between <pre> and its lines is rendered verbatim");
        }
    }

    @Test
    void theNotStartedStateHasItsOwnPageWithTheStartButton() throws IOException {
        String resolver = read(Path.of(
                "src/main/java/com/wikantik/pressroom/ui/controllers/PressroomViewResolver.java"));
        int at = resolver.indexOf("\".Bootstrap\"");
        assertTrue(at >= 0, "the .Bootstrap view is registered");
        String definition = resolver.substring(at, resolver.indexOf("));", at));
        assertTrue(definition.contains("/WEB-INF/jsps/core/Bootstrap.jsp"),
                ".Bootstrap must render its own page, not UnknownError.jsp: " + definition);

        String jsp = read(JSPS.resolve("Bootstrap.jsp"));
        assertTrue(jsp.contains(START_INCLUDE), "Bootstrap.jsp offers the Start Pressroom button");
        assertTrue(jsp.contains("installer.startFailedExplanation"), "and points at the server log after a failure");
        assertFalse(jsp.contains("installer.unknownError"), "nothing on it is an unknown error");
    }
}
