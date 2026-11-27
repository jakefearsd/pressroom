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
package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * A 40-character commit hash scrolled the footer sideways at phone width.
     * The seven-character cut now lives in {@code Colophon.shortRevision}
     * (one place for the JSP and Velocity footers), so the footer prints that.
     */
    @Test
    void theFooterShowsAShortRevision() {
        String src = jsp("tiles/footer.jsp");
        assertTrue(src.contains("colophon.shortRevision"),
                "the footer prints the short (7-character) revision from the Colophon");
        assertFalse(src.contains("pressroomRevision"), "no second, raw revision path");
    }

    /** AC4: every full-page admin layout carries the footer tile. */
    @Test
    void everyFullPageLayoutIncludesTheFooterTile() throws IOException {
        // No layout is chrome-free now: the modal and iframe layouts went with the media screens.
        java.util.Set<String> chromeless = java.util.Set.of();
        String resolver = Files.readString(
                Path.of("src/main/java/com/wikantik/pressroom/ui/controllers/PressroomViewResolver.java"),
                StandardCharsets.UTF_8);
        int checked = 0;
        try (var layouts = Files.list(JSPS.resolve("tiles"))) {
            for (Path layout : layouts.filter(f -> f.getFileName().toString().matches("tiles-.*\\.jsp")).toList()) {
                String file = layout.getFileName().toString();
                if (chromeless.contains(file)) {
                    continue;
                }
                checked++;
                assertTrue(Files.readString(layout, StandardCharsets.UTF_8).contains("${tile_footer}"),
                        file + " must include ${tile_footer}");
                String name = file.substring(0, file.length() - ".jsp".length());
                int at = resolver.indexOf("addDefinition(\"." + name + "\"");
                assertTrue(at >= 0, "no base definition for ." + name);
                String definition = resolver.substring(at, resolver.indexOf("));", at));
                assertTrue(definition.contains("\"footer\"") && definition.contains("tiles/footer.jsp"),
                        "." + name + " must map footer to tiles/footer.jsp");
            }
        }
        assertEquals(4, checked, "the full-page layouts: simple, login, install, error");
    }

    /** The footer renders the Colophon through the shared footer.* keys, escaped. */
    @Test
    void footerRendersColophon() {
        String src = jsp("tiles/footer.jsp");
        for (String key : new String[] {"footer.version.revision", "footer.version", "footer.released",
                "footer.privacy", "footer.copyright.default"}) {
            assertTrue(src.contains("\"" + key + "\""), "footer.jsp uses " + key);
        }
        assertTrue(src.contains("class=\"colophon\""));
        assertTrue(src.contains("colophon-copyright"));
        assertTrue(src.contains("colophon-meta"));
        assertTrue(src.contains("fn:escapeXml(colophon.copyrightText)"));
        assertTrue(src.contains("fn:escapeXml(colophon.copyrightHolder)"));
        assertTrue(src.contains("fn:escapeXml(colophon.privacyUrl)"));
        assertFalse(src.contains("footer.productName"), "the old 'Powered by' keys are gone");
        // A holder may contain commas: spring:message splits a string
        // "arguments" attribute on them, so arguments are nested tags.
        assertFalse(src.contains("arguments="), "arguments go in spring:argument, never a comma-split string");
    }

    /**
     * AC9: the footer is a display path. Colophon.of never throws, so it must
     * be the only business-tier call the JSP makes besides locating Weblogger.
     */
    @Test
    void footerRendersWhenRuntimeConfigThrows() {
        String src = jsp("tiles/footer.jsp");
        assertTrue(src.contains("Colophon.of("));
        // Before bootstrap the provider bean throws: the lookup must be guarded
        // so the footer still renders (version from the packaged properties).
        assertTrue(src.contains("catch (RuntimeException"), "the Weblogger lookup is guarded");
        String scriptlets = src.replaceAll("(?s)<%--.*?--%>", "");
        assertFalse(scriptlets.contains("WebloggerRuntimeConfig"), "config is read inside Colophon.of only");
        assertFalse(scriptlets.contains("getWeblogManager"));
        assertFalse(scriptlets.contains("getVersion()"));
        assertFalse(scriptlets.contains("getRevision()"));
        assertFalse(scriptlets.contains("getBuildTime()"));
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

    /**
     * Every other layout puts the page title inside the content card; the
     * simple page (Create weblog, Account, the site directory) left it
     * floating above.
     */
    @Test
    void theSimplePageTitleSitsInsideTheCard() {
        String src = jsp("tiles/tiles-simplepage.jsp");
        int card = src.indexOf("<div class=\"card-body\">");
        int title = src.indexOf("<h1 class=\"pressroom-page-title\">");
        assertTrue(card >= 0 && title > card, "the h1 is rendered outside the card");
    }

    /**
     * The reader's account page: section headings were {@code h2.h5}
     * (Bootstrap's 20px, page-title size) rather than the caps-label role,
     * its form was the only one with labels beside fields, the comment body
     * carried an inline style, and Delete was an outline button where every
     * other list uses the row-action icon.
     */
    @Test
    void theAccountPageRidesTheSharedRoles() {
        String src = jsp("core/Account.jsp");
        assertFalse(src.contains("class=\"h5\""), "a section heading still renders at 20px");
        assertEquals(4, src.split("<h2 class=\"section-head\">", -1).length - 1,
                "four caps-label section heads: name, comments, email, delete");
        assertTrue(src.contains("id=\"accountNameForm\" class=\"form-stacked\""), "the name form stacks its label");
        assertFalse(src.contains("style=\""), "inline style left on the account page");
        assertTrue(src.contains("class=\"pre-wrap\""));
        assertTrue(src.contains("<td class=\"row-actions\">"), "the comment's delete is a row action");
    }

    /**
     * The sign-in page: "Forgot your password?" (and the SSO button) sat
     * outside the centred form column, flush with the page edge, and the
     * placeholders only repeated the labels above them.
     */
    @Test
    void theSignInLinksSitInTheFormColumnAndNoPlaceholderRepeatsALabel() {
        String src = jsp("core/Login.jsp");
        int column = src.indexOf("<div class=\"form-signin\">");
        int forgot = src.indexOf("loginPage.forgotPassword");
        int end = src.indexOf("</div><%-- .form-signin --%>");
        assertTrue(column >= 0 && forgot > column && end > forgot,
                "the forgot-password link is outside the form column");
        assertFalse(src.contains("placeholder="), "a placeholder repeats its label");
    }

    /**
     * A JSP with the default {@code session="true"} calls
     * {@code request.getSession()} just to render, so the public site root
     * set a JSESSIONID on every anonymous reader and crawler. Every JSP the
     * site directory renders opts out.
     */
    @Test
    void theSiteDirectoryRendersWithoutASession() {
        for (String page : java.util.List.of("core/SiteDirectory.jsp", "tiles/tiles-simplepage.jsp",
                "tiles/head.jsp", "tiles/bannerStatus.jsp", "tiles/messages.jsp", "tiles/footer.jsp",
                "tiles/empty.jsp")) {
            assertTrue(jsp(page).contains("<%@ page session=\"false\" %>"), page + " creates a session");
        }
    }

    /**
     * A controller that sets {@code metaDescription} gets a description in
     * the head: the site directory is a public page a search engine reads
     * (Bing site scan, 2026-11-11). It is admin-typed text, so escaped.
     */
    @Test
    void theHeadEmitsAMetaDescriptionWhenTheControllerSetsOne() {
        String src = jsp("tiles/head.jsp");
        assertTrue(src.contains("<c:if test=\"${not empty metaDescription}\">"), src);
        assertTrue(src.contains("<meta name=\"description\" content=\"${fn:escapeXml(metaDescription)}\">"), src);
    }
}
