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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins a hazard no controller-level unit test can see. {@code
 * MaintenanceControllerTest} asserts {@code selectedWeblog == null} on the
 * MODEL, but a browser does not read the model -- it reads the rendered
 * {@code <select>}. Per the HTML spec, a {@code <select>} with no {@code
 * <option selected>} defaults its FIRST option to selected, full stop; that
 * is not a quirk to work around, it is what "no option marked selected"
 * means to a browser. {@code Maintenance.jsp}'s weblog list comes from
 * {@code getWeblogs(true, null, null, null, 0, -1)} (ordered by {@code
 * dateCreated DESC}), so without a placeholder, an admin who opens the page
 * and clicks an action button without ever touching the dropdown would
 * silently submit the most-recently-created weblog on the install -- no
 * error, no visual cue a choice was ever made. That is exactly the failure
 * this test exists to keep out.
 *
 * <p>This is a source-text scan in the same style as {@link
 * org.apache.roller.weblogger.ui.EditorJspEscapingTest}: it reads the raw
 * JSP rather than rendering it (there is no lightweight JSP-rendering
 * harness in this codebase), so it proves the placeholder markup is present,
 * first, and correctly guarded -- not what a real browser actually submits
 * for it. That other half matters and is NOT "posts weblogId=\"\"", despite
 * what an earlier version of this comment claimed: per WHATWG HTML's
 * form-entry-list construction, an {@code <option>} that is both {@code
 * disabled} and selected contributes NO entry to the submitted form at all
 * when the placeholder is left untouched -- not an empty one. That is why
 * {@code MaintenanceController}'s three POST handlers declare their {@code
 * weblogId} {@code @RequestParam} as {@code required = false}, and why
 * {@link MaintenanceControllerRequestBindingTest} exists: a plain source
 * scan of this JSP, or a controller test that calls the handler method
 * directly with {@code ""}, cannot see a defect that only exists in how
 * Spring MVC binds (or fails to bind) an ACTUAL missing request parameter.
 */
class MaintenanceJspTest {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/admin/Maintenance.jsp");

    @Test
    void theWeblogSelectHasACorrectlyGuardedDisabledPlaceholderFirst() throws IOException {
        assertTrue(Files.isRegularFile(JSP), "Expected " + JSP.toAbsolutePath());
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        int selectStart = source.indexOf("<select name=\"weblogId\"");
        assertTrue(selectStart >= 0, "Expected <select name=\"weblogId\"> in " + JSP);
        int selectEnd = source.indexOf("</select>", selectStart);
        assertTrue(selectEnd > selectStart, "Unterminated <select> in " + JSP);
        String selectBody = source.substring(selectStart, selectEnd);

        int firstOptionStart = selectBody.indexOf("<option");
        assertTrue(firstOptionStart >= 0, "The weblog select has no <option> at all");
        int firstOptionEnd = selectBody.indexOf("</option>", firstOptionStart);
        assertTrue(firstOptionEnd > firstOptionStart, "Unterminated first <option> in the weblog select");
        String firstOption = selectBody.substring(firstOptionStart, firstOptionEnd);

        assertTrue(firstOption.contains("value=\"\""),
                "The FIRST <option> in the weblog select must be the empty-value placeholder, "
                        + "not a real weblog -- a <select> with no explicitly selected option "
                        + "defaults to its first one. Found: " + firstOption);
        assertTrue(firstOption.contains("disabled=\"disabled\""),
                "The placeholder must be disabled so it can never be re-selected once a real "
                        + "weblog is chosen. Found: " + firstOption);
        assertTrue(
                firstOption.contains(
                        "<c:if test=\"${selectedWeblog == null}\">selected=\"selected\"</c:if>"),
                "The placeholder's selected=\"selected\" must be conditional on no weblog being "
                        + "chosen yet, not unconditional -- an unconditional one here would fight "
                        + "a real option's own conditional selected and could mark two options "
                        + "selected in the same rendered <select>. Found: " + firstOption);
        assertTrue(firstOption.contains("code=\"maintenance.select.placeholder\""),
                "The placeholder's label must come from a message key, not a literal string, "
                        + "like every other label on this page. Found: " + firstOption);

        // Confirm this really is the FIRST option -- a getWeblogs() entry
        // rendered before the placeholder would defeat the entire point.
        int secondOptionStart = selectBody.indexOf("<option", firstOptionEnd);
        if (secondOptionStart >= 0) {
            assertTrue(secondOptionStart > firstOptionEnd,
                    "A second <option> appears to start before the first one ends -- "
                            + "the scan itself is confused, not the markup");
        }
    }

    /**
     * The two long operations confirm and name their weblog, but the name
     * was baked in at render time from {@code selectedWeblog}, which is null
     * on a first visit: the prompt read "Rebuild the search index for ? ...",
     * and it kept that (or a previous) name after the admin chose another
     * weblog in the select. Each button now carries a template, the live
     * {@code data-confirm} is rendered only once a weblog is selected, and a
     * change listener on the select rewrites it from the chosen option.
     */
    @Test
    void theConfirmPromptsFollowTheSelectedWeblog() throws IOException {
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        for (String key : new String[] {"maintenance.confirm.index",
                "maintenance.confirm.regenerateRenditions"}) {
            int at = source.indexOf("code=\"" + key + "\"");
            assertTrue(at >= 0, "Expected " + key + " in " + JSP);
        }
        assertEquals(2, count(source, "data-confirm-template="),
                "Both long-operation buttons must carry a data-confirm-template the select can "
                        + "rewrite their prompt from");
        assertFalse(source.contains("selectedWeblogLabel"),
                "The prompt must not be built from a render-time label: it is empty on a first "
                        + "visit (\"for ?\") and goes stale when the admin picks another weblog");
        for (String live : liveConfirms(source)) {
            assertTrue(live.contains("selectedWeblog != null"),
                    "A rendered data-confirm must be conditional on a weblog being selected, or "
                            + "the first visit prompts \"for ?\". Found: " + live);
        }
        assertTrue(source.contains("data-handle=\"${fn:escapeXml(w.handle)}\""),
                "Each weblog option must expose its handle for the prompt");
        assertTrue(source.contains("getElementById(\"maintenanceWeblog\")")
                        && source.contains("\"change\""),
                "A change listener on #maintenanceWeblog must rewrite the prompts");
    }

    /**
     * The three action buttons were live before any weblog was chosen, so a
     * click could only earn "No such weblog". The script that names the
     * weblog in the prompts also keeps every action disabled until one is.
     */
    @Test
    void theActionsWaitForAWeblog() throws IOException {
        String source = Files.readString(JSP, StandardCharsets.UTF_8);
        assertTrue(source.contains("querySelectorAll(\"button[type=submit]\")"),
                "the script reaches every action button");
        assertTrue(source.contains(".disabled = !handle"),
                "an action stays disabled while no weblog is chosen");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** The lines that render a live data-confirm attribute. */
    private static java.util.List<String> liveConfirms(String source) {
        java.util.List<String> found = new java.util.ArrayList<>();
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("data-confirm=")) {
                found.add(lines[i]);
            }
        }
        return found;
    }
}
