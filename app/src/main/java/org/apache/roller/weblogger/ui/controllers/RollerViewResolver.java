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
package org.apache.roller.weblogger.ui.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight replacement for Apache Tiles.  Maps a logical view name
 * (e.g. ".EntryEdit") to a layout JSP plus named content-area JSP paths.
 *
 * <p>During rendering the content-area paths are set as request attributes
 * so the layout JSP can include them via {@code <jsp:include page="${content}"/>}.
 */
public class RollerViewResolver implements ViewResolver, Ordered {

    private final Map<String, ViewDefinition> definitions = new HashMap<>();
    private int order = 0;

    public void setOrder(int order) { this.order = order; }

    @Override
    public int getOrder() { return order; }

    /**
     * Register a base definition (one that declares its own layout template).
     */
    public void addDefinition(String name, String layout, Map<String, String> attributes) {
        definitions.put(name, new ViewDefinition(layout, Map.copyOf(attributes)));
    }

    /**
     * Register an extended definition that inherits from an existing base
     * and overrides specific attributes.
     *
     * @throws IllegalArgumentException if the base definition has not been registered yet
     */
    public void addExtendedDefinition(String name, String extendsName, Map<String, String> overrides) {
        ViewDefinition base = definitions.get(extendsName);
        if (base == null) {
            throw new IllegalArgumentException("Base definition not found: " + extendsName);
        }
        var merged = new HashMap<>(base.attributes());
        merged.putAll(overrides);
        definitions.put(name, new ViewDefinition(base.layout(), Map.copyOf(merged)));
    }

    @Override
    public View resolveViewName(String viewName, Locale locale) {
        ViewDefinition def = definitions.get(viewName);
        if (def == null) {
            return null;
        }
        return new RollerLayoutView(def);
    }

    // ------------------------------------------------------------------ init

    /**
     * Populate every view definition that was previously declared in tiles.xml.
     * Call this method once at startup (e.g. from a {@code @PostConstruct} or
     * Spring {@code @Bean} factory method).
     */
    public void init() {

        // ---- 7 base layout definitions ----

        addDefinition(".tiles-mainmenupage",
                "/WEB-INF/jsps/tiles/tiles-mainmenupage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/bannerStatus.jsp",
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",  "${content}",
                        "sidebar",  "/WEB-INF/jsps/tiles/empty.jsp",
                        "footer",   "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-tabbedpage",
                "/WEB-INF/jsps/tiles/tiles-tabbedpage.jsp",
                mapOf10(
                        "banner",     "/WEB-INF/jsps/tiles/bannerStatus.jsp",
                        "userStatus", "/WEB-INF/jsps/tiles/userStatus.jsp",
                        "head",       "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",     "/WEB-INF/jsps/tiles/empty.jsp",
                        "menu",       "/WEB-INF/jsps/tiles/empty.jsp",
                        "sidemenu",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages",   "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",    "${content}",
                        "sidebar",    "/WEB-INF/jsps/tiles/empty.jsp",
                        "footer",     "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-simplepage",
                "/WEB-INF/jsps/tiles/tiles-simplepage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/bannerStatus.jsp",
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",  "${content}",
                        "footer",   "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-loginpage",
                "/WEB-INF/jsps/tiles/tiles-loginpage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/bannerStatus.jsp",
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",  "${content}",
                        "footer",   "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-installpage",
                "/WEB-INF/jsps/tiles/tiles-installpage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",  "${content}",
                        "footer",   "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-errorpage",
                "/WEB-INF/jsps/tiles/tiles-errorpage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "content",  "${content}",
                        "footer",   "/WEB-INF/jsps/tiles/footer.jsp"
                ));

        addDefinition(".tiles-popuppage",
                "/WEB-INF/jsps/tiles/tiles-popuppage.jsp",
                Map.of(
                        "banner",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp",
                        "styles",   "/WEB-INF/jsps/tiles/empty.jsp",
                        "content",  "${content}"
                ));

        addDefinition(".tiles-barepage",
                "/WEB-INF/jsps/tiles/tiles-barepage.jsp",
                Map.of("content", "${content}"));

        // ---- extended base definition ----

        addExtendedDefinition(".tiles-popuppage-only-content", ".tiles-popuppage",
                Map.of("content", "${content}"));

        // ---- error pages ----

        addExtendedDefinition(".denied", ".tiles-errorpage",
                Map.of("content", "/roller-ui/errors/denied.jsp"));

        // ---- core pages ----

        addExtendedDefinition(".Login", ".tiles-loginpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/Login.jsp",
                        "styles",  "/WEB-INF/jsps/core/Login-css.jsp"
                ));

        addExtendedDefinition(".ForgotPassword", ".tiles-loginpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/ForgotPassword.jsp",
                        "styles",  "/WEB-INF/jsps/core/Login-css.jsp"
                ));

        addExtendedDefinition(".ResetPassword", ".tiles-loginpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/ResetPassword.jsp",
                        "styles",  "/WEB-INF/jsps/core/Login-css.jsp"
                ));


        // ---- setup / install pages ----

        addExtendedDefinition(".Setup", ".tiles-simplepage",
                Map.of("content", "/WEB-INF/jsps/core/Setup.jsp"));

        // Profile is a signed-in screen with no weblog scope, so it has no tabs
        // to render -- but it is still admin chrome, and mainmenupage is the
        // layout for exactly that (the rail with the account context block and
        // no tab groups, which is what MainMenu itself uses). On simplepage --
        // the install/login chrome -- it rendered as a bare card with no rail,
        // so a reader who opened it had nothing to navigate back by.
        addExtendedDefinition(".Profile", ".tiles-mainmenupage",
                Map.of("content", "/WEB-INF/jsps/core/Profile.jsp"));

        // Profile -> API tokens and the one-time reveal: signed-in, un-scoped
        // screens in Profile's own chrome (spec 2026-10-13, section 3).
        addExtendedDefinition(".ProfileTokens", ".tiles-mainmenupage",
                Map.of("content", "/WEB-INF/jsps/core/ProfileTokens.jsp"));

        addExtendedDefinition(".TokenReveal", ".tiles-mainmenupage",
                Map.of("content", "/WEB-INF/jsps/core/TokenReveal.jsp"));

        // The reader's account page is the one admin-UI screen a reader can
        // open, and the rail has nowhere for a reader to go (its account link
        // opens the profile, which refuses readers), so it is simplepage: the
        // card under the top bar, whose home and log-out links a reader can use.
        addExtendedDefinition(".Account", ".tiles-simplepage",
                Map.of("content", "/WEB-INF/jsps/core/Account.jsp"));

        addExtendedDefinition(".CreateWeblog", ".tiles-simplepage",
                Map.of("content", "/WEB-INF/jsps/core/CreateWeblog.jsp"));

        addExtendedDefinition(".GenericError", ".tiles-simplepage",
                Map.of("content", "/WEB-INF/jsps/core/GenericError.jsp"));

        addExtendedDefinition(".SiteDirectory", ".tiles-simplepage",
                Map.of("content", "/WEB-INF/jsps/core/SiteDirectory.jsp"));

        addExtendedDefinition(".MainMenu", ".tiles-mainmenupage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/MainMenu.jsp",
                        "sidebar", "/WEB-INF/jsps/core/MainMenuSidebar.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".CreateDatabase", ".tiles-installpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/CreateDatabase.jsp",
                        "banner",  "/WEB-INF/jsps/tiles/bannerInstallation.jsp"
                ));

        addExtendedDefinition(".UpgradeDatabase", ".tiles-installpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/UpgradeDatabase.jsp",
                        "banner",  "/WEB-INF/jsps/tiles/bannerInstallation.jsp"
                ));

        addExtendedDefinition(".DatabaseError", ".tiles-installpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/DatabaseError.jsp",
                        "banner",  "/WEB-INF/jsps/tiles/bannerInstallation.jsp"
                ));

        addExtendedDefinition(".Bootstrap", ".tiles-installpage",
                Map.of(
                        "content", "/WEB-INF/jsps/core/Bootstrap.jsp",
                        "banner",  "/WEB-INF/jsps/tiles/bannerInstallation.jsp"
                ));

        // ---- global admin pages ----

        addExtendedDefinition(".GlobalConfig", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/GlobalConfig.jsp"));

        addExtendedDefinition(".Businesses", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/Businesses.jsp"));

        addExtendedDefinition(".BusinessEdit", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/BusinessEdit.jsp"));

        addExtendedDefinition(".UserAdmin", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/UserAdmin.jsp"));

        addExtendedDefinition(".UserEdit", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/UserEdit.jsp"));

        addExtendedDefinition(".AgentCreate", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/AgentCreate.jsp"));

        addExtendedDefinition(".Agent", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/Agent.jsp"));

        addExtendedDefinition(".ApiTokens", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/ApiTokens.jsp"));

        addExtendedDefinition(".Maintenance", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/Maintenance.jsp"));

        // ---- weblog editor pages ----

        addExtendedDefinition(".MediaFileAdd", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/MediaFileAdd.jsp",
                        "sidebar", "/WEB-INF/jsps/tiles/empty.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".MediaFileEdit", ".tiles-popuppage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/MediaFileEdit.jsp"
                ));


        addExtendedDefinition(".MediaFileImageChooser", ".tiles-popuppage-only-content",
                Map.of(
                        "head",     "/WEB-INF/jsps/tiles/head.jsp",
                        "content",  "/WEB-INF/jsps/editor/MediaFileImageChooser.jsp",
                        "messages", "/WEB-INF/jsps/tiles/messages.jsp"
                ));

        addExtendedDefinition(".MediaFileAddInclude", ".tiles-popuppage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/MediaFileAdd.jsp"
                ));

        // Bare layout, not popuppage: this document exists only to call
        // parent.onEditSuccess() and is destroyed by the parent's re-submit
        // milliseconds later. Rendering it with the full admin head made the
        // browser abort its webfont fetch mid-flight on every media rename --
        // the GalleryIT "font ERR_ABORTED" flake.
        addExtendedDefinition(".MediaFileEditSuccess", ".tiles-barepage",
                Map.of("content", "/WEB-INF/jsps/editor/MediaFileEditSuccess.jsp"));

        addExtendedDefinition(".MediaFileView", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/MediaFileView.jsp",
                        "sidebar", "/WEB-INF/jsps/editor/MediaFileSidebar.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".MediaFileAddSuccess", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/MediaFileAddSuccess.jsp",
                        "sidebar", "/WEB-INF/jsps/tiles/empty.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".EntryEdit", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/EntryEdit.jsp",
                        "sidebar", "/WEB-INF/jsps/tiles/empty.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Entries", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/Entries.jsp",
                        "sidebar", "/WEB-INF/jsps/editor/EntriesSidebar.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Trash", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/Trash.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Categories", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/Categories.jsp",
                        "sidebar", "/WEB-INF/jsps/editor/CategoriesSidebar.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        // .CategoryEdit and .CategoryRemove used to live here. Their content tiles
        // pointed at empty.jsp because CategoryEdit.jsp and CategoryRemove.jsp were
        // deleted in 8da2fc0b2 when the forms moved into #category-edit-modal on
        // Categories.jsp. The definitions survived, so the routes returned a
        // healthy-looking 200 with no form on the page.

        // ---- weblog admin pages ----

        addExtendedDefinition(".WeblogConfig", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/editor/WeblogConfig.jsp"));

        addExtendedDefinition(".WeblogRemoveConfirm", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/WeblogRemoveConfirm.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".ThemeEdit", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/ThemeEdit.jsp"
                ));

        addExtendedDefinition(".StylesheetEdit", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/StylesheetEdit.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Templates", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/Templates.jsp",
                        "sidebar", "/WEB-INF/jsps/editor/TemplatesSidebar.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".TemplateEdit", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/TemplateEdit.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Pages", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/Pages.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".PageEdit", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/PageEdit.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        // Inquiries is an authoring screen reached from the editor tabs, so it
        // wears the same tabbed chrome the rest of them do. The attribute map
        // is .Trash's -- the minimal tabbed shape: explicit head, the content
        // tile, no page-specific styles, and the base layout's empty menu tile
        // (the tabs come from the navMenu model attribute, not from a tile).
        addExtendedDefinition(".Submissions", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/Submissions.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        // Reader-comment moderation: the same minimal tabbed shape as .Submissions.
        addExtendedDefinition(".Comments", ".tiles-tabbedpage",
                Map.of(
                        "head",    "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/editor/Comments.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));

        addExtendedDefinition(".Members", ".tiles-tabbedpage",
                Map.of(
                        "content", "/WEB-INF/jsps/editor/Members.jsp",
                        "styles",  "/WEB-INF/jsps/tiles/empty.jsp"
                ));
    }

    // ---------------------------------------------------------- helper

    /**
     * Convenience factory for maps with more than 10 entries
     * (Map.of() supports at most 10 key-value pairs).
     */
    private static Map<String, String> mapOf10(
            String k1, String v1, String k2, String v2,
            String k3, String v3, String k4, String v4,
            String k5, String v5, String k6, String v6,
            String k7, String v7, String k8, String v8,
            String k9, String v9, String k10, String v10) {
        var map = new HashMap<String, String>(16);
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        map.put(k8, v8);
        map.put(k9, v9);
        map.put(k10, v10);
        return Map.copyOf(map);
    }

    // ----------------------------------------------------- inner view

    private static class RollerLayoutView implements View {
        private final ViewDefinition definition;

        RollerLayoutView(ViewDefinition definition) {
            this.definition = definition;
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request,
                           HttpServletResponse response) throws Exception {
            // expose tile attribute paths (prefixed with "tile_" to avoid
            // collisions with model attributes like "menu")
            definition.attributes().forEach((key, value) ->
                    request.setAttribute("tile_" + key, value));
            // expose Spring model attributes
            if (model != null) {
                model.forEach(request::setAttribute);
            }
            // forward to the layout JSP
            request.getRequestDispatcher(definition.layout()).forward(request, response);
        }
    }
}
