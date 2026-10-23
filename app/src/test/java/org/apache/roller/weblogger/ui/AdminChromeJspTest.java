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
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans of the shared admin chrome (Main Menu, the footer, the
 * flash-message tile, the site directory), from the 2026-10 fit-and-finish
 * pass. Same style as {@link AdminJspHygieneTest}: no JSP-rendering harness
 * exists, so these pin markup shape.
 */
class AdminChromeJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    private static String jsp(String name) {
        try {
            return Files.readString(JSPS.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The "N awaiting approval" chip was a stock Bootstrap {@code
     * text-bg-warning} badge: saturated yellow with black text, untokened in
     * dark mode, the one loud colour on the screen. It is the quiet pending
     * status pill the lists use.
     */
    @Test
    void mainMenuShowsPendingCommentsAsTheQuietStatusPill() {
        String src = jsp("core/MainMenu.jsp");
        assertFalse(src.contains("badge"), "core/MainMenu.jsp still renders a Bootstrap badge");
        int at = src.indexOf("mainMenu.pendingComments");
        assertTrue(at >= 0);
        String before = src.substring(Math.max(0, at - 200), at);
        assertTrue(before.contains("status-pill status-pending"),
                "the pending count rides .status-pill.status-pending: " + before);
    }

    /** A 40-character commit hash scrolled the footer sideways at phone width. */
    @Test
    void theFooterShowsAShortRevision() {
        String src = jsp("tiles/footer.jsp");
        assertTrue(src.contains("fn:substring(rollerRevision, 0, 7)"),
                "the footer prints the short (7-character) revision");
    }

    /**
     * {@code ALERT_END} marked the end of the flash messages for an AJAX
     * error scraper that no longer exists; the comment shipped in every page.
     */
    @Test
    void theMessagesTileCarriesNoDeadAjaxMarker() {
        assertFalse(jsp("tiles/messages.jsp").contains("ALERT_END"));
    }

    /**
     * {@code p.lead} is Bootstrap's 20px, the page title's size: emphasis by
     * size, which the design system rules out.
     */
    @Test
    void theSiteDirectoryHasNoLeadParagraph() {
        assertFalse(jsp("core/SiteDirectory.jsp").contains("lead"));
    }
}
