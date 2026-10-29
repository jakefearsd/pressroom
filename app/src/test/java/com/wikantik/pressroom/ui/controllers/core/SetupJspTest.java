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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text scan (same style as {@code MaintenanceJspTest}) pinning that
 * {@code Setup.jsp}'s front-page form actually reflects the currently stored
 * configuration rather than always rendering at its defaults. Without this,
 * every re-save of the form silently reverts the front-page weblog to
 * whichever weblog happens to be first in the {@code <select>}, and clears
 * the aggregated flag, because nothing on the page shows what is currently
 * configured.
 */
class SetupJspTest {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/core/Setup.jsp");

    @Test
    void theFrontpageSelectHasACorrectlyGuardedDisabledPlaceholderFirst() throws IOException {
        assertTrue(Files.isRegularFile(JSP), "Expected " + JSP.toAbsolutePath());
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        int selectStart = source.indexOf("<select name=\"frontpageBlog\"");
        assertTrue(selectStart >= 0, "Expected <select name=\"frontpageBlog\"> in " + JSP);
        int selectEnd = source.indexOf("</select>", selectStart);
        assertTrue(selectEnd > selectStart, "Unterminated <select> in " + JSP);
        String selectBody = source.substring(selectStart, selectEnd);

        int firstOptionStart = selectBody.indexOf("<option");
        assertTrue(firstOptionStart >= 0, "The frontpage select has no <option> at all");
        int firstOptionEnd = selectBody.indexOf("</option>", firstOptionStart);
        assertTrue(firstOptionEnd > firstOptionStart, "Unterminated first <option> in the frontpage select");
        String firstOption = selectBody.substring(firstOptionStart, firstOptionEnd);

        assertTrue(firstOption.contains("value=\"\""),
                "The FIRST <option> in the frontpage select must be the empty-value placeholder, "
                        + "not a real weblog -- a <select> with no explicitly selected option "
                        + "defaults to its first one. Found: " + firstOption);
        assertTrue(firstOption.contains("disabled=\"disabled\""),
                "The placeholder must be disabled so it can never be re-selected once a real "
                        + "weblog is chosen. Found: " + firstOption);
        assertTrue(
                firstOption.contains(
                        "<c:if test=\"${empty frontpageWeblogHandle}\">selected=\"selected\"</c:if>"),
                "The placeholder's selected=\"selected\" must be conditional on no frontpage weblog "
                        + "being configured yet, not unconditional. Found: " + firstOption);

        // Confirm this really is the FIRST option -- a real weblog option
        // rendered before the placeholder would defeat the entire point.
        int secondOptionStart = selectBody.indexOf("<option", firstOptionEnd);
        if (secondOptionStart >= 0) {
            assertTrue(secondOptionStart > firstOptionEnd,
                    "A second <option> appears to start before the first one ends -- "
                            + "the scan itself is confused, not the markup");
        }

        assertTrue(selectBody.contains(
                        "<c:if test=\"${frontpageWeblogHandle == w.handle}\">selected=\"selected\"</c:if>"),
                "Each real weblog <option> must mark itself selected when it matches the stored "
                        + "front-page handle, or the select never reflects a saved configuration.");
    }

    @Test
    void theAggregatedCheckboxReflectsTheStoredFlag() throws IOException {
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        int checkboxStart = source.indexOf("name=\"aggregated\"");
        assertTrue(checkboxStart >= 0, "Expected name=\"aggregated\" checkbox in " + JSP);
        int inputEnd = source.indexOf("/>", checkboxStart);
        assertTrue(inputEnd > checkboxStart, "Unterminated aggregated <input> in " + JSP);
        String checkboxMarkup = source.substring(checkboxStart, inputEnd);

        assertTrue(checkboxMarkup.contains("<c:if test=\"${frontpageAggregated}\">checked=\"checked\"</c:if>"),
                "The aggregated checkbox must reflect the stored flag with a checked=\"checked\" "
                        + "gated on the model attribute, or a re-save silently clears it. Found: "
                        + checkboxMarkup);
    }
}
