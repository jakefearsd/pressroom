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
        assertFalse(src.contains("rollerRevision"), "no second, raw revision path");
    }

    /** AC4: every full-page admin layout carries the footer tile. */
    @Test
    void everyFullPageLayoutIncludesTheFooterTile() throws IOException {
        // popuppage renders inside a modal/iframe, barepage is a transient
        // script-only iframe document: neither is a page a reader lands on.
        java.util.Set<String> chromeless = java.util.Set.of("tiles-popuppage.jsp", "tiles-barepage.jsp");
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
        assertEquals(6, checked, "the full-page layouts: mainmenu, tabbed, simple, login, install, error");
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
     * Row-level edit and delete looked different on every list (labelled
     * Edit/Remove columns, red text links, outline buttons). One pattern now:
     * icon buttons in one unlabelled, right-aligned {@code td.row-actions}.
     */
    @Test
    void theListScreensShareOneRowActionPattern() {
        for (String screen : java.util.List.of("editor/Pages.jsp", "editor/Categories.jsp",
                "admin/Businesses.jsp")) {
            String src = jsp(screen);
            assertTrue(src.contains("<td class=\"row-actions\">"), screen + " has no td.row-actions");
            assertTrue(src.contains("row-action row-action-danger"), screen + "'s delete is not a .row-action");
            assertFalse(src.contains("text-danger"), screen + " still has a red text-link action");
            assertTrue(src.contains("class=\"list-actions\""), screen + "'s New action is not in the card's strip");
        }
        assertFalse(jsp("editor/CategoriesSidebar.jsp").contains("addCategoryButton"),
                "Add category moved into the card; the sidebar must not carry a second one");
    }

    /** "Delete this business?" did not say which; the prompt names it. */
    @Test
    void deletingABusinessNamesIt() {
        assertTrue(jsp("admin/Businesses.jsp").contains(
                "<spring:message code=\"businesses.delete.confirm\" arguments=\"${[b.name]}\""));
    }

    /**
     * A message inside a JS string literal must be JS-escaped: one apostrophe
     * in a translation is a SyntaxError that takes category add, edit and
     * delete down with the whole script block.
     */
    @Test
    void categoriesEscapeEveryMessageInAJsString() {
        String src = jsp("editor/Categories.jsp");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'<spring:message [^>]*/>'").matcher(src);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue(m.group().contains("javaScriptEscape=\"true\""), "not JS-escaped: " + m.group());
        }
        assertTrue(seen > 0, "the scan found no JS-string messages at all");
    }

    /**
     * The permission levels came in a different order on each screen (and
     * Members' own grant form disagreed with its table). One order now:
     * Administrator, Author, Limited -- Author still preselected on a grant.
     */
    @Test
    void permissionLevelsAreAlwaysAdministratorAuthorLimited() {
        java.util.regex.Pattern options = java.util.regex.Pattern.compile(
                "<option value=\"admin\"[^>]*>.*?</option>\\s*<option value=\"post\"[^>]*>.*?</option>"
                        + "\\s*<option value=\"edit_draft\"", java.util.regex.Pattern.DOTALL);
        for (String screen : java.util.List.of("editor/Members.jsp", "admin/UserWeblogs.jsp")) {
            String src = jsp(screen);
            java.util.regex.Matcher m = options.matcher(src);
            int lists = 0;
            while (m.find()) {
                lists++;
            }
            int selects = src.split("<option value=\"admin\"", -1).length - 1;
            assertTrue(selects > 0 && lists == selects,
                    screen + ": " + lists + " of " + selects + " level lists are in the house order");
        }
        assertTrue(jsp("editor/Members.jsp").contains("<option value=\"post\" selected"),
                "a new member is still an Author by default");
    }

    /**
     * The copy says "you are not allowed to demote or remove yourself", but
     * the caller's own row had every radio live; the server then refused the
     * save. The non-admin choices are disabled on that row (the admin radio
     * stays live, so the save still carries the one admin it needs).
     */
    @Test
    void membersCannotPickADemotionForThemselves() {
        String src = jsp("editor/Members.jsp");
        assertTrue(src.contains("<c:set var=\"selfRow\" value=\"${perm.user.userName == authenticatedUser.userName}\"/>"),
                "Members.jsp knows which row is the caller's own");
        assertEquals(3, src.split(java.util.regex.Pattern.quote("<c:if test=\"${selfRow}\">disabled</c:if>"), -1).length - 1,
                "Author, Limited and Remove are disabled on the caller's own row");
        assertTrue(src.contains("<div class=\"table-responsive\">"), "the table scrolls inside its card at phone width");
    }

    /**
     * "No weblogs yet." over "User is a member of no weblogs." said the same
     * thing twice, and the list opened with "...the following weblog(s):".
     */
    @Test
    void theAccountWeblogsSectionSaysThingsOnce() {
        String src = jsp("admin/UserWeblogs.jsp");
        assertFalse(src.contains("userAdmin.userMemberOf"), "the lead-in sentence above the table is gone");
        assertFalse(src.contains("userAdmin.userHasNoWeblogs"), "the empty state's body no longer repeats its title");
        assertTrue(src.contains("userWeblogs.empty.body"), "the empty state points at the add form");
    }

    /**
     * The user editor kept its field help in {@code title=} tooltips (gone on
     * touch, unread by most screen readers) and disabled Save from an
     * {@code onkeyup} check, so autofill or a mouse paste left it disabled
     * with no reason given. The server already answers each field with
     * {@code addFieldError}; the help that matters is visible
     * {@code .form-text}, and Save is always live.
     */
    @Test
    void theUserEditorShowsItsHelpAndNeverDisablesSave() {
        String src = jsp("admin/UserEdit.jsp");
        assertFalse(src.contains("title=\"<spring:message"), "field help is still in a title= tooltip");
        assertFalse(src.contains("formChanged"), "Save is still gated by a keyup check");
        assertFalse(src.contains("prop(\"disabled\""), "Save is still disabled by script");
        for (String tip : java.util.List.of("userAdmin.tip.userName", "userSettings.tip.username",
                "userAdmin.tip.email", "userAdmin.tip.password")) {
            assertTrue(java.util.regex.Pattern.compile("<div class=\"form-text\"[^>]*><spring:message code=\""
                            + java.util.regex.Pattern.quote(tip) + "\"/></div>").matcher(src).find(),
                    tip + " is not visible help under its field");
        }
        assertFalse(src.contains("<p class=\"pagetip\">\n    <c:if"),
                "the edit screen renders an empty pagetip");
    }

    /**
     * Create weblog: the URL preview printed the English word "handle" until
     * the user typed, an empty label sat in front of it, and the email field
     * stopped at 50 characters where every other one allows 255.
     */
    @Test
    void theCreateWeblogFormHasNoPlaceholderWordOrEmptyLabel() {
        String src = jsp("core/CreateWeblog.jsp");
        assertFalse(src.contains("<c:otherwise>handle</c:otherwise>"), "the preview still prints \"handle\"");
        assertFalse(src.contains("<label class=\"col-sm-3\"></label>"), "an empty label is still rendered");
        assertTrue(src.contains("id=\"emailAddress\" cssClass=\"form-control validate-email\" size=\"40\" maxlength=\"255\""),
                "the email field allows 255 characters");
    }

    /**
     * Cancel was built four ways; a navigating Cancel is a link styled as a
     * button, which works without script.
     */
    @Test
    void aNavigatingCancelIsALink() {
        for (String screen : java.util.List.of("core/CreateWeblog.jsp", "core/Profile.jsp")) {
            String src = jsp(screen);
            assertFalse(src.contains("onclick=\"window.location="), screen + " still navigates from an onclick");
            assertTrue(src.contains("class=\"btn btn-secondary\" href=\"<c:url value='/roller-ui/menu.rol'/>\""),
                    screen + "'s Cancel is not a link");
        }
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
        int title = src.indexOf("<h1 class=\"roller-page-title\">");
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
        assertEquals(3, src.split("<h2 class=\"section-head\">", -1).length - 1, "three caps-label section heads");
        assertTrue(src.contains("id=\"accountNameForm\" class=\"form-stacked\""), "the name form stacks its label");
        assertFalse(src.contains("style=\""), "inline style left on the account page");
        assertTrue(src.contains("class=\"pre-wrap\""));
        assertTrue(src.contains("<td class=\"row-actions\">"), "the comment's delete is a row action");
    }

    /**
     * Admin API tokens squeezed ten columns into the card: "Issued by" (a
     * column of its own, usually the owner again) now sits under the owner
     * only when it differs; a never-used token says Never in the UI face
     * rather than leaving a blank; the table scrolls at phone width and its
     * timestamps never wrap.
     */
    @Test
    void theAdminTokenListIsNarrowerAndSaysNever() {
        String src = jsp("admin/ApiTokens.jsp");
        assertFalse(src.contains("<th scope=\"col\"><spring:message code=\"apiTokens.issuedBy\"/></th>"),
                "Issued by is still a column of its own");
        assertTrue(src.contains("row.issuedBy != row.token.user.userName"), "issued-by shows only when it differs");
        assertTrue(src.contains("apiTokens.lastUsed.never"), "a never-used token says so");
        assertTrue(src.contains("<div class=\"table-responsive\">"));
        assertTrue(src.contains("<td class=\"data text-nowrap\">"));
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
     * Weblog settings listed locales as raw codes ("en_US") where Profile and
     * Create weblog show "English (United States)"; and below 900px its
     * section index fell to the bottom of the page, after every field, where
     * it no longer helps (same on Site settings). The index box is marked so
     * the narrow layout can drop it.
     */
    @Test
    void theSettingsPagesNameLocalesAndMarkTheirSectionIndex() {
        String weblog = jsp("editor/WeblogConfig.jsp");
        assertTrue(weblog.contains(">${fn:escapeXml(opt.displayName)}</option>"),
                "Weblog settings shows locale codes, not names");
        for (String screen : java.util.List.of("editor/WeblogConfig.jsp", "admin/GlobalConfig.jsp")) {
            assertTrue(jsp(screen).contains("<div class=\"rail-box settings-rail-index\">"),
                    screen + "'s section index box is not marked");
        }
    }

    /**
     * At phone width the whole left column (the rail, then the page's
     * sidebar forms) rendered above the content: on the entry editor the
     * title field started at y=1327. Below md the column folds behind a
     * Menu button; from md up it is always shown, so desktop is unchanged.
     */
    @Test
    void theLeftColumnFoldsBehindAMenuButtonAtPhoneWidth() {
        for (String layout : java.util.List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = jsp(layout);
            assertTrue(src.contains("<div class=\"collapse d-md-block\" id=\"adminRailColumn\">"),
                    layout + ": the rail and sidebar are not in the narrow-width collapse");
            assertTrue(src.contains("data-bs-target=\"#adminRailColumn\"")
                            && src.contains("class=\"btn btn-secondary btn-sm d-md-none rail-toggle\""),
                    layout + ": no Menu button for the folded column");
            int fold = src.indexOf("id=\"adminRailColumn\"");
            assertTrue(fold >= 0 && src.indexOf("id=\"adminRail\"") > fold && src.indexOf("<main") > fold,
                    layout + ": the rail must sit inside the fold");
        }
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
}
