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
package com.wikantik.pressroom.ui.controllers;

import jakarta.servlet.ServletException;
import java.io.IOException;
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
public class PressroomViewResolver implements ViewResolver, Ordered {

    private static final String MAINMENU = ".tiles-mainmenupage";
    private static final String TABBED = ".tiles-tabbedpage";
    private static final String SIMPLE = ".tiles-simplepage";
    private static final String LOGIN = ".tiles-loginpage";
    private static final String INSTALL = ".tiles-installpage";
    private static final String ERROR = ".tiles-errorpage";
    private static final String POPUP = ".tiles-popuppage";
    private static final String BARE = ".tiles-barepage";

    private final Map<String, ViewDefinition> definitions = new HashMap<>();
    private int order;

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

    /**
     * Starts a fluent definition: {@code define(".X").on(TABBED).content(jsp).register()}.
     * Only what differs from the base is stated; {@link ViewBuilder#register}
     * rejects an override that repeats the base's own value.
     */
    ViewBuilder define(String name) {
        return new ViewBuilder(name);
    }

    /** Collects one extended definition's overrides. */
    final class ViewBuilder {
        private final String name;
        private String base;
        private final Map<String, String> overrides = new HashMap<>();

        ViewBuilder(String name) {
            this.name = name;
        }

        ViewBuilder on(String baseName) {
            this.base = baseName;
            return this;
        }

        ViewBuilder content(String jsp) {
            return set("content", jsp);
        }

        ViewBuilder head(String jsp) {
            return set("head", jsp);
        }

        ViewBuilder styles(String jsp) {
            return set("styles", jsp);
        }

        ViewBuilder sidebar(String jsp) {
            return set("sidebar", jsp);
        }

        ViewBuilder banner(String jsp) {
            return set("banner", jsp);
        }

        ViewBuilder messages(String jsp) {
            return set("messages", jsp);
        }

        private ViewBuilder set(String tile, String jsp) {
            overrides.put(tile, jsp);
            return this;
        }

        /** @throws IllegalArgumentException if an override repeats the base's value, or there is no such base */
        void register() {
            ViewDefinition parent = definitions.get(base);
            if (parent == null) {
                throw new IllegalArgumentException("Base definition not found: " + base);
            }
            overrides.forEach((tile, jsp) -> {
                if (jsp.equals(parent.attributes().get(tile))) {
                    throw new IllegalArgumentException(
                            name + " repeats " + base + "'s " + tile + " tile; leave it out");
                }
            });
            addExtendedDefinition(name, base, overrides);
        }
    }

    @Override
    public View resolveViewName(String viewName, Locale locale) {
        ViewDefinition def = definitions.get(viewName);
        if (def == null) {
            return null;
        }
        return new PressroomLayoutView(def);
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

        // ---- error pages ----

        define(".denied").on(ERROR)
                .content("/pressroom-ui/errors/denied.jsp")
                .register();

        // ---- core pages ----

        define(".Login").on(LOGIN)
                .content("/WEB-INF/jsps/core/Login.jsp")
                .styles("/WEB-INF/jsps/core/Login-css.jsp")
                .register();

        define(".ForgotPassword").on(LOGIN)
                .content("/WEB-INF/jsps/core/ForgotPassword.jsp")
                .styles("/WEB-INF/jsps/core/Login-css.jsp")
                .register();

        define(".ResetPassword").on(LOGIN)
                .content("/WEB-INF/jsps/core/ResetPassword.jsp")
                .styles("/WEB-INF/jsps/core/Login-css.jsp")
                .register();


        // ---- install pages ----

        // The reader's account page is the one admin-UI screen a reader can
        // open, and the rail has nowhere for a reader to go (its account link
        // opens the profile, which refuses readers), so it is simplepage: the
        // card under the top bar, whose home and log-out links a reader can use.
        define(".Account").on(SIMPLE)
                .content("/WEB-INF/jsps/core/Account.jsp")
                .register();

        define(".CreateWeblog").on(SIMPLE)
                .content("/WEB-INF/jsps/core/CreateWeblog.jsp")
                .register();

        define(".GenericError").on(SIMPLE)
                .content("/WEB-INF/jsps/core/GenericError.jsp")
                .register();

        define(".SiteDirectory").on(SIMPLE)
                .content("/WEB-INF/jsps/core/SiteDirectory.jsp")
                .register();

        define(".CreateDatabase").on(INSTALL)
                .content("/WEB-INF/jsps/core/CreateDatabase.jsp")
                .banner("/WEB-INF/jsps/tiles/bannerInstallation.jsp")
                .register();

        define(".UpgradeDatabase").on(INSTALL)
                .content("/WEB-INF/jsps/core/UpgradeDatabase.jsp")
                .banner("/WEB-INF/jsps/tiles/bannerInstallation.jsp")
                .register();

        define(".DatabaseError").on(INSTALL)
                .content("/WEB-INF/jsps/core/DatabaseError.jsp")
                .banner("/WEB-INF/jsps/tiles/bannerInstallation.jsp")
                .register();

        define(".Bootstrap").on(INSTALL)
                .content("/WEB-INF/jsps/core/Bootstrap.jsp")
                .banner("/WEB-INF/jsps/tiles/bannerInstallation.jsp")
                .register();

        // ---- weblog editor pages ----

        define(".MediaFileAdd").on(TABBED)
                .content("/WEB-INF/jsps/editor/MediaFileAdd.jsp")
                .register();

        define(".MediaFileEdit").on(POPUP)
                .head("/WEB-INF/jsps/tiles/head.jsp")
                .content("/WEB-INF/jsps/editor/MediaFileEdit.jsp")
                .register();


        define(".MediaFileImageChooser").on(POPUP)
                .head("/WEB-INF/jsps/tiles/head.jsp")
                .content("/WEB-INF/jsps/editor/MediaFileImageChooser.jsp")
                .register();

        define(".MediaFileAddInclude").on(POPUP)
                .head("/WEB-INF/jsps/tiles/head.jsp")
                .content("/WEB-INF/jsps/editor/MediaFileAdd.jsp")
                .register();

        // Bare layout, not popuppage: this document exists only to call
        // parent.onEditSuccess() and is destroyed by the parent's re-submit
        // milliseconds later. Rendering it with the full admin head made the
        // browser abort its webfont fetch mid-flight on every media rename --
        // the GalleryIT "font ERR_ABORTED" flake.
        define(".MediaFileEditSuccess").on(BARE)
                .content("/WEB-INF/jsps/editor/MediaFileEditSuccess.jsp")
                .register();

        define(".MediaFileView").on(TABBED)
                .content("/WEB-INF/jsps/editor/MediaFileView.jsp")
                .sidebar("/WEB-INF/jsps/editor/MediaFileSidebar.jsp")
                .register();

        define(".MediaFileAddSuccess").on(TABBED)
                .content("/WEB-INF/jsps/editor/MediaFileAddSuccess.jsp")
                .register();

        define(".EntryEdit").on(TABBED)
                .content("/WEB-INF/jsps/editor/EntryEdit.jsp")
                .register();

        define(".Entries").on(TABBED)
                .content("/WEB-INF/jsps/editor/Entries.jsp")
                .sidebar("/WEB-INF/jsps/editor/EntriesSidebar.jsp")
                .register();

        define(".Trash").on(TABBED)
                .content("/WEB-INF/jsps/editor/Trash.jsp")
                .register();

        define(".Categories").on(TABBED)
                .content("/WEB-INF/jsps/editor/Categories.jsp")
                .sidebar("/WEB-INF/jsps/editor/CategoriesSidebar.jsp")
                .register();

        // .CategoryEdit and .CategoryRemove used to live here. Their content tiles
        // pointed at empty.jsp because CategoryEdit.jsp and CategoryRemove.jsp were
        // deleted in 8da2fc0b2 when the forms moved into #category-edit-modal on
        // Categories.jsp. The definitions survived, so the routes returned a
        // healthy-looking 200 with no form on the page.

        // ---- weblog admin pages ----

        define(".WeblogConfig").on(TABBED)
                .content("/WEB-INF/jsps/editor/WeblogConfig.jsp")
                .register();

        define(".WeblogRemoveConfirm").on(TABBED)
                .content("/WEB-INF/jsps/editor/WeblogRemoveConfirm.jsp")
                .register();

        define(".ThemeEdit").on(TABBED)
                .content("/WEB-INF/jsps/editor/ThemeEdit.jsp")
                .register();

        define(".StylesheetEdit").on(TABBED)
                .content("/WEB-INF/jsps/editor/StylesheetEdit.jsp")
                .register();

        define(".Templates").on(TABBED)
                .content("/WEB-INF/jsps/editor/Templates.jsp")
                .sidebar("/WEB-INF/jsps/editor/TemplatesSidebar.jsp")
                .register();

        define(".TemplateEdit").on(TABBED)
                .content("/WEB-INF/jsps/editor/TemplateEdit.jsp")
                .register();

        define(".Pages").on(TABBED)
                .content("/WEB-INF/jsps/editor/Pages.jsp")
                .register();

        define(".PagesTrash").on(TABBED)
                .content("/WEB-INF/jsps/editor/PagesTrash.jsp")
                .register();

        define(".PageEdit").on(TABBED)
                .content("/WEB-INF/jsps/editor/PageEdit.jsp")
                .register();

        // Inquiries is an authoring screen reached from the editor tabs, so it
        // wears the same tabbed chrome the rest of them do. The attribute map
        // is .Trash's -- the minimal tabbed shape: explicit head, the content
        // tile, no page-specific styles, and the base layout's empty menu tile
        // (the tabs come from the navMenu model attribute, not from a tile).
        define(".Submissions").on(TABBED)
                .content("/WEB-INF/jsps/editor/Submissions.jsp")
                .register();

        // Reader-comment moderation: the same minimal tabbed shape as .Submissions.
        define(".Comments").on(TABBED)
                .content("/WEB-INF/jsps/editor/Comments.jsp")
                .register();

        define(".Members").on(TABBED)
                .content("/WEB-INF/jsps/editor/Members.jsp")
                .register();
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

    private static class PressroomLayoutView implements View {
        private final ViewDefinition definition;

        PressroomLayoutView(ViewDefinition definition) {
            this.definition = definition;
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request,
                           HttpServletResponse response) throws ServletException, IOException {
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
