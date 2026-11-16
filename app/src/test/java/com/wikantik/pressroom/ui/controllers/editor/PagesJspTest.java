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
package com.wikantik.pressroom.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans over the Pages screen's reorder controls, in the manner of
 * {@link PageEditJspTest}: the markup cannot fail at compile time, and an
 * undeclared control or an unescaped title renders as literal text or a
 * hole, not an error.
 */
class PagesJspTest {

    private static final Path PAGES = Paths.get("src/main/webapp/WEB-INF/jsps/editor/Pages.jsp");

    private static String jsp() throws IOException {
        return Files.readString(PAGES, StandardCharsets.UTF_8);
    }

    @Test
    void everyRowCarriesUpDownAndADragHandle() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("<tr class=\"page-row\" draggable=\"true\""), "each row is a reorderable page-row");
        assertTrue(jsp.contains("draggable=\"true\""), "rows drag as a shortcut for the buttons");
        assertTrue(jsp.contains("page-order-up") && jsp.contains("page-order-down"),
                "up and down buttons on every row, whatever its status or nav flag");
        assertTrue(jsp.contains("page-order-handle"), "a drag handle");
        assertTrue(jsp.contains("weblogPagesForm.moveUp") && jsp.contains("weblogPagesForm.moveDown"));
        // Not inside the status/showInNav conditional: no c:if around the row controls.
        int row = jsp.indexOf("<c:forEach items=\"${pages}\" var=\"p\">");
        int up = jsp.indexOf("page-order-up", row);
        assertTrue(row > 0 && up > row, "the buttons are inside the per-page loop");
    }

    @Test
    void theOrderIsPostedAsRepeatedPageIdsFromOneSeparateForm() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("id=\"pagesOrderForm\""), "the order form is its own form");
        assertTrue(jsp.contains("pressroom-ui/authoring/pagesOrder.rol"));
        assertTrue(jsp.contains("name=\"pageIds\" form=\"pagesOrderForm\" value=\"${fn:escapeXml(p.id)}\""),
                "each row's hidden id belongs to the order form (forms cannot nest), in row order");
        assertTrue(jsp.contains("<sec:csrfInput/>"));
        Matcher save = Pattern.compile("<button[^>]*id=\"pagesOrderSave\"[^>]*>").matcher(jsp);
        assertTrue(save.find(), "a Save order button");
        assertTrue(save.group().contains("hidden"), "hidden until the order differs from the loaded one");
        assertTrue(jsp.contains("weblogPagesForm.saveOrder"));
    }

    @Test
    void theRemoveFormAndItsRoutesMarkerSurvive() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("id=\"pageRemoveForm\""), "Routes pins pages.rol to this marker");
        assertTrue(jsp.contains("pageRemove.rol"));
    }

    @Test
    void titlesInLabelsAreEscapedAndNeverBare() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("${fn:escapeXml(moveUpLabel)}: ${fn:escapeXml(p.title)}"),
                "the up button's label names the page, escaped");
        assertTrue(jsp.contains("${fn:escapeXml(moveDownLabel)}: ${fn:escapeXml(p.title)}"));
        assertFalse(jsp.replace("<c:out value=\"${p.title}\"/>", "").contains("${p.title}"),
                "no bare title expression outside fn:escapeXml and c:out");
    }

    /**
     * The visible nav-order number must follow a move, or it shows the old value until
     * the page reloads: the cell is addressable and the move hook renumbers it from
     * the new row positions (the numbers Save order will store).
     */
    @Test
    void theNavOrderNumberFollowsAMove() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("<td class=\"data page-nav-order\">${p.navOrder}</td>"), "a numbered cell");
        assertTrue(jsp.contains("querySelectorAll(\".page-nav-order\")"), "onMove renumbers the cells");
    }

    @Test
    void theSharedReorderScriptDrivesTheRows() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("/theme/scripts/pressroom-reorder.js"));
        assertTrue(jsp.contains("PressroomReorder.install"));
        assertTrue(jsp.contains("rowSelector") && jsp.contains("upSelector") && jsp.contains("downSelector"));
    }

    /** The Pages screen says which page is the blog's home page, and so does that page's delete prompt. */
    @Test
    void theHomePageIsMarkedAndItsDeletePromptSaysSo() throws IOException {
        String jsp = jsp();
        assertTrue(jsp.contains("<c:if test=\"${actionWeblog.frontPageId == p.id}\">"
                        + "<span class=\"status-pill page-home-marker\"><spring:message code=\"weblogPagesForm.homePage\"/></span></c:if>"),
                "a quiet marker beside the home page's title");
        assertTrue(jsp.contains("<spring:message code=\"pages.deleteHomeConfirm\" var=\"homeConfirm\"/>"));
        assertTrue(jsp.contains("<c:if test=\"${actionWeblog.frontPageId == p.id}\"> ${fn:escapeXml(homeConfirm)}</c:if>\""),
                "the delete prompt of the home page ends with what deleting it does");
    }

    /**
     * The table has a real header section, so the caps-label header style belongs to it. The older
     * rule (the first row of the body, for the tables with no header section) styled the first page
     * as a header: 0.12.0 shipped that.
     */
    @Test
    void theHeaderStyleGoesToTheHeaderSectionNotTheFirstPage() throws IOException {
        String css = Files.readString(Paths.get("src/main/webapp/pressroom-ui/styles/pressroom.css"),
                StandardCharsets.UTF_8);
        assertTrue(jsp().contains("<thead>"), "Pages.jsp declares its header section");
        assertTrue(css.contains("table.pressroomtable > thead > tr > th,"), "the header section is styled");
        assertTrue(css.contains("table.pressroomtable:not(:has(> thead)) > tbody > tr:first-child > th,")
                        && css.contains("table.pressroomtable:not(:has(> thead)) > tbody > tr:first-child > td {"),
                "the first-row rule applies only where there is no header section");
        assertFalse(css.contains("table.pressroomtable > tbody > tr:first-child"),
                "no first-row header rule that ignores the header section");
    }
}
