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
package org.apache.roller.it.support;

import java.util.List;

/**
 * The catalogue of every HTTP GET route Roller's Spring MVC controllers expose.
 *
 * <p>This is a plain data class with no test logic so that two things can share
 * it without sharing a build dependency: {@code RouteSweepIT} in this module
 * drives it through a browser, and {@code RouteCoverageTest} in the app module
 * reads <em>this source file as text</em> and fails if a controller grows a
 * {@code @GetMapping} that is not listed here. The app module cannot import
 * from an integration-test module, so text is the only link -- see the comment
 * at the top of RouteCoverageTest for why that is deliberate rather than lazy.
 * The practical consequence: keep every route path in this file written as a
 * single string literal ({@code "/roller-ui/..."}, or the root-level SEO
 * routes {@code /sitemap-....xml} / {@code /robots.txt}), never assembled
 * from fragments, or the coverage guard will not see it.
 *
 * <p>Derived by reading
 * {@code org.apache.roller.weblogger.ui.controllers.**} for {@code @GetMapping}
 * and {@code @RequestMapping(method = GET)},
 * {@code org.apache.roller.weblogger.boot.SecurityConfig} (Spring Boot's
 * Java-config replacement for the old {@code WEB-INF/security.xml}) for the
 * URL-pattern to role mapping, and {@code RollerHandlerInterceptor} plus
 * {@code BaseController} for the {@code weblog=} parameter rule: a controller
 * whose {@code isWeblogRequired()} is true (the {@code BaseController} default)
 * redirects to access-denied unless the request carries a {@code weblog}
 * parameter naming a real weblog.
 */
public final class Routes {

    /** The weblog seeded by {@code src/test/resources/seed-it-data.sql}. */
    public static final String WEBLOG_HANDLE = "it_weblog";

    private Routes() {
    }

    /**
     * The lowest role that can reach a route, from
     * {@code SecurityConfig.securityFilterChain}'s authorization rules. The
     * sweep logs in as the seeded admin, who holds both roles, so this is
     * documentation and grouping rather than a gate -- but it is the thing to
     * check first when a route starts redirecting to login.
     */
    public enum Role {
        /** No authorization rule matches and the controller sets isUserRequired() false. */
        ANONYMOUS,
        /** SecurityConfig grants {@code hasAnyAuthority("admin", "editor")}. */
        EDITOR,
        /** SecurityConfig grants {@code hasAuthority("admin")} only. */
        ADMIN
    }

    /**
     * How a known-broken route fails, so the "assert it is still broken" test
     * can be specific. A vague assertion here would keep passing after a fix.
     */
    public enum Breakage {
        /**
         * The controller returns a logical view name that no resolver can turn
         * into a real JSP, so the container 404s on the forward. Expect a
         * non-2xx status.
         */
        VIEW_NOT_RESOLVED,
        /**
         * The view definition resolves, but its content tile points at
         * empty.jsp. The page returns HTTP 200 with the full site chrome --
         * banner, menu, footer and even an {@code <h1 class="roller-page-title">}
         * from the layout -- and no form. Status-only assertions pass on it.
         * Expect 200 and no content marker.
         */
        EMPTY_CONTENT_TILE
    }

    /**
     * A route the sweep visits and asserts on.
     *
     * @param path   servlet path, no context path and no query string
     * @param role   lowest role that can reach it
     * @param query  query string without the leading '?', empty when none
     * @param marker CSS selector for an element that only the route's own
     *               content tile renders. Never a generic "body is non-empty"
     *               check: the tabbedpage layout supplies a heading, a menu and
     *               a footer even when the content tile is empty.
     */
    public record Route(String path, Role role, String query, String marker) {

        /** Path plus query, ready to hand to openPath(). */
        public String url() {
            return query.isEmpty() ? path : path + "?" + query;
        }

        @Override
        public String toString() {
            return url();
        }
    }

    /** A route deliberately left out of the sweep, with the reason recorded. */
    public record SkippedRoute(String path, Role role, String reason) {
        @Override
        public String toString() {
            return path;
        }
    }

    /** A route confirmed broken in the source, kept visible instead of omitted. */
    public record BrokenRoute(String path, Role role, String query, Breakage breakage, String reason) {

        public String url() {
            return query.isEmpty() ? path : path + "?" + query;
        }

        @Override
        public String toString() {
            return url();
        }
    }

    private static final String WEBLOG = "weblog=" + WEBLOG_HANDLE;

    // ---------------------------------------------------------------- bucket a

    /**
     * Routes reachable with no request parameters at all.
     *
     * <p>Every controller behind these either overrides
     * {@code isWeblogRequired()} to false or is outside {@code /authoring/}.
     */
    private static final List<Route> NO_PARAMETERS = List.of(

            // LoginController.isUserRequired() is false and no SecurityConfig
            // authorization rule matches /roller-ui/login**, so this renders
            // logged out or logged in. Login.jsp's form is unconditional.
            new Route("/roller-ui/login.rol", Role.ANONYMOUS, "", "#loginForm"),

            // PasswordResetController.isUserRequired() is false, matching
            // LoginController; the form is unconditional.
            new Route("/roller-ui/forgotPassword.rol", Role.ANONYMOUS, "",
                    "form[action$='/roller-ui/forgotPassword!send.rol']"),

            // Landing page for authorization failures. RollerHandlerInterceptor
            // redirects here; it previously had no handler at all, so every denial
            // produced a bare 404. denied.jsp's only distinctive element is its h1#accessDenied.
            new Route("/roller-ui/access-denied.rol", Role.ANONYMOUS, "", "h1#accessDenied"),
            // What / shows while no weblog is the front page; public.
            new Route("/roller-ui/siteDirectory.rol", Role.ANONYMOUS, "", "#siteDirectory"),

            // SetupController.isUserRequired() is false, but SecurityConfig's
            // authorization rule for /roller-ui/setup* still requires the
            // "admin" authority, so this route is reachable only because the
            // sweep is logged in as the seeded admin, not because it is public.
            // Every link on Setup.jsp is conditional on user/blog counts; the
            // three card headings are not. The marker is an app-owned class
            // (not card-title) because Setup.jsp's headings sit in .card-header,
            // not .card-body, where Bootstrap 5's card-title is normally used.
            new Route("/roller-ui/setup.rol", Role.ADMIN, "", "h2.setup-section-title"),

            // The weblog list only renders under
            // <c:if test="${not empty existingPermissions}">, which holds because
            // seed-it-data.sql grants it_admin a permission on it_weblog. If the
            // seed ever stops granting it, this marker is the thing that fails.
            new Route("/roller-ui/menu.rol", Role.EDITOR, "", "h2.mm_weblog_name"),

            new Route("/roller-ui/profile.rol", Role.EDITOR, "",
                    "form[action$='/roller-ui/profile!save.rol']"),

            // Profile -> API tokens (spec 2026-10-13): the live tokens and the form.
            new Route("/roller-ui/profileTokens.rol", Role.EDITOR, "", "#profileTokensPage"),

            // The one-time reveal. Opened directly it has no flash secret, so it
            // renders its "already shown" state -- still its own content tile.
            new Route("/roller-ui/profileTokens!reveal.rol", Role.EDITOR, "", "#tokenRevealPage"),

            // The reader's account page. AccountController needs only the global
            // "comment" action, which readers hold -- but Role has no READER and
            // the sweep signs in as the seeded admin, who holds "comment" too, so
            // EDITOR (whose role grants "comment") is the lowest role listed here.
            new Route("/roller-ui/account.rol", Role.EDITOR, "", "#accountPage"),

            // Renders .CreateWeblog rather than .GenericError because
            // site.allowUserWeblogCreation defaults to true (runtimeConfigDefs.xml)
            // and groupblogging.enabled defaults to true (roller.properties), which
            // skips the "you already have a blog" bail-out.
            new Route("/roller-ui/createWeblog.rol", Role.EDITOR, "",
                    "form[action$='/roller-ui/createWeblog!save.rol']"),

            // The settings rail (task B9a): the <form> IS the .settings-grid,
            // so the rail is the marker that proves the page rendered its own
            // content rather than the form action, which the old
            // no-rail layout also had.
            new Route("/roller-ui/admin/globalConfig.rol", Role.ADMIN, "",
                    ".settings-rail"),

            new Route("/roller-ui/admin/userAdmin.rol", Role.ADMIN, "",
                    "#userListPage"),

            // Businesses: the list's own wrapper, and the edit form, which a
            // blank id opens empty (the new-business case), so neither needs seeding.
            new Route("/roller-ui/admin/businesses.rol", Role.ADMIN, "",
                    "#businesses-list-marker"),

            // Admin -> API tokens (spec 2026-10-13): every live token on the site.
            new Route("/roller-ui/admin/apiTokens.rol", Role.ADMIN, "", "#apiTokensPage"),

            new Route("/roller-ui/admin/businesses!edit.rol", Role.ADMIN, "",
                    "form[action$='/roller-ui/admin/businesses!save.rol']"),

            // Maintenance moved off the blog-author tabs and into Global
            // Admin: the three actions (flush cache, rebuild search index,
            // regenerate media renditions) are operator work, not authoring
            // work. MaintenanceController.isWeblogRequired() is false -- the
            // target weblog is chosen from a <select> on the page instead of
            // the weblog= action-context parameter -- so this reaches the
            // page with no parameters at all, like globalConfig.rol/
            // userAdmin.rol above. Maintenance.jsp's outer form posts back to
            // maintenance.rol itself; the individual buttons override it with
            // formaction.
            new Route("/roller-ui/admin/maintenance.rol", Role.ADMIN, "",
                    "form[action$='/roller-ui/admin/maintenance.rol']"),

            // UserEdit.jsp posts to a runtime-chosen ${saveAction}, so the form
            // action is not a stable selector; the username input is.
            // Anchored on the username input by NAME. It used to anchor on
            // id="bean_userName", which sat on the SCREEN NAME field -- so the
            // marker was proving the wrong control existed.
            new Route("/roller-ui/admin/createUser.rol", Role.ADMIN, "",
                    "input[name='bean.userName']"),

            // New agent (spec 2026-10-13): the create form.
            new Route("/roller-ui/admin/createAgent.rol", Role.ADMIN, "",
                    "form[action$='/roller-ui/admin/createAgent!save.rol']"),

            // An agent's page, for the agent seed-it-data.sql creates -- the one
            // route in this list that needs a query: a page needs its agent.
            new Route("/roller-ui/admin/agent.rol", Role.ADMIN, "userName=it_agent", "#agentPage"),

            // SeoController's sitemap index. Not HTML: Chrome's built-in XML
            // viewer keeps the source document's elements in the DOM (inside
            // #webkit-xml-viewer-source-xml), so the root element works as a
            // marker. PublicSurfaceIT asserts the document's content.
            new Route("/sitemap.xml", Role.ANONYMOUS, "", "sitemapindex"),

            // SeoController's generated robots policy (the static webapp
            // robots.txt is gone). Chrome wraps text/plain bodies in a <pre>.
            new Route("/robots.txt", Role.ANONYMOUS, "", "pre")
    );

    // ---------------------------------------------------------------- bucket b

    /**
     * Routes that need only {@code weblog=it_weblog}.
     *
     * <p>All of these sit under {@code /roller-ui/authoring/} and inherit
     * {@code BaseController.isWeblogRequired() == true}, so
     * {@code RollerHandlerInterceptor} bounces them to access-denied without the
     * parameter.
     */
    private static final List<Route> WEBLOG_ONLY = List.of(

            // Anchored on the category table itself. It used to be anchored on a
            // wrapper form posting to categories!move.rol -- a control nothing
            // could submit, which is a poor thing to prove a page rendered by.
            new Route("/roller-ui/authoring/categories.rol", Role.EDITOR, WEBLOG,
                    "table#category-table"),

            // The entry table is empty with no entries seeded; the delete-confirm
            // modal is emitted unconditionally.
            new Route("/roller-ui/authoring/entries.rol", Role.EDITOR, WEBLOG, "#delete-entry-modal"),

            new Route("/roller-ui/authoring/entryAdd.rol", Role.EDITOR, WEBLOG, "#entry"),

            // Trash is empty with nothing seeded to trash, but the wrapper div
            // around the list region (table or empty-state invitation) is
            // emitted unconditionally, same reasoning as entries.rol above.
            new Route("/roller-ui/authoring/trash.rol", Role.EDITOR, WEBLOG, "#trash-list-marker"),

            new Route("/roller-ui/authoring/mediaFileAdd.rol", Role.EDITOR, WEBLOG,
                    "form[action$='/roller-ui/authoring/mediaFileAdd!save.rol']"),

            // #mediaFileViewForm only renders when the directory has files;
            // #createPostForm is at the top of the JSP and always renders.
            new Route("/roller-ui/authoring/mediaFileView.rol", Role.EDITOR, WEBLOG, "#createPostForm"),

            new Route("/roller-ui/authoring/members.rol", Role.EDITOR, WEBLOG,
                    "form[action$='/roller-ui/authoring/members!save.rol']"),

            // Table is empty with no pages seeded, but the remove form around
            // it is emitted unconditionally, same reasoning as entries.rol.
            new Route("/roller-ui/authoring/pages.rol", Role.EDITOR, WEBLOG, "#pageRemoveForm"),

            // "New page" mode (no id parameter): the edit form always renders.
            new Route("/roller-ui/authoring/pageEdit.rol", Role.EDITOR, WEBLOG, "#pageEditForm"),

            // Both of StylesheetEdit.jsp's forms sit inside a <c:choose> on the
            // weblog's theme state, so neither is a reliable marker. The subtitle
            // is outside the choose.
            new Route("/roller-ui/authoring/stylesheetEdit.rol", Role.EDITOR, WEBLOG, "p.subtitle"),

            // Table is empty with no submissions seeded, but the delete form
            // around it is emitted unconditionally, same reasoning as
            // pages.rol/entries.rol.
            new Route("/roller-ui/authoring/submissions.rol", Role.EDITOR, WEBLOG,
                    "#submissionsDeleteForm"),

            // The moderation form is emitted unconditionally, empty list or not.
            new Route("/roller-ui/authoring/comments.rol", Role.EDITOR, WEBLOG,
                    "#commentsModerationForm"),

            new Route("/roller-ui/authoring/templates.rol", Role.EDITOR, WEBLOG,
                    "form[action$='/roller-ui/authoring/templates!remove.rol']"),

            // The theme card grid (task B9b). It sits inside #themeChooser,
            // which the page's JS reveals -- but this asserts the elements
            // EXIST, not that they are visible, so the marker holds whichever
            // state block the state machine lands on.
            new Route("/roller-ui/authoring/themeEdit.rol", Role.EDITOR, WEBLOG,
                    ".theme-cards"),

            new Route("/roller-ui/authoring/weblogConfig.rol", Role.EDITOR, WEBLOG,
                    "form[action$='/roller-ui/authoring/weblogConfig!save.rol']"),

            new Route("/roller-ui/authoring/weblogRemove.rol", Role.EDITOR, WEBLOG,
                    "form[action$='/roller-ui/authoring/weblogRemove!remove.rol']")
    );

    // ---------------------------------------------------------------- bucket c

    /**
     * Routes the sweep deliberately does not visit, each with its reason.
     *
     * <p>Most need the id of an entity nobody has created yet. Note that
     * seed-it-data.sql does pin three ids today --
     * {@code it-admin-0000-0000-0000-000000000001},
     * {@code it-weblog-0000-0000-0000-00000000001} and
     * {@code it-cat-0000-0000-0000-000000000001} -- so the user-editing routes
     * below could be promoted into the sweep as soon as somebody is willing to
     * couple this catalogue to those literals. Nothing entry-, media-file- or
     * template-shaped is seeded, so those genuinely have to wait for a fixture.
     *
     * <p>The rest are skipped for reasons that will not go away with more seed
     * data: they redirect somewhere that belongs to another page, they destroy
     * the session, or they only do anything before bootstrap.
     */
    private static final List<SkippedRoute> SKIPPED = List.of(

            // --- path templates the sweep cannot open literally ---

            new SkippedRoute("/sitemap-{handle}.xml", Role.ANONYMOUS,
                    "Path template, not an openable URL; the concrete "
                            + "/sitemap-it_weblog.xml is fetched and content-asserted by "
                            + "PublicSurfaceIT."),
            new SkippedRoute("/sitemap-site-pages.xml", Role.ANONYMOUS,
                    "A 404 until the site space has a published page, and the sweep "
                            + "publishes none; fetched and asserted while the front-door "
                            + "page is published by SitePagesIT."),
            new SkippedRoute("/roller-ui/resetPassword.rol", Role.ANONYMOUS,
                    "Needs a token= query parameter naming a live UserToken row; "
                            + "tokens are minted per forgot-password request, so there is "
                            + "no literal URL to sweep. Without one the required "
                            + "@RequestParam makes the request 400 rather than render a "
                            + "page. Covered by PasswordResetControllerTest."),

            // --- need a seeded entity id ---

            new SkippedRoute("/roller-ui/authoring/entryEdit.rol", Role.EDITOR,
                    "Needs bean.id of a WeblogEntry; without one the controller "
                            + "redirects to menu.rol. No entries are seeded."),
            new SkippedRoute("/roller-ui/authoring/entryEdit!firstSave.rol", Role.EDITOR,
                    "Post-save landing page for entryEdit; same missing entry id."),
            new SkippedRoute("/roller-ui/authoring/entryAddWithMediaFile.rol", Role.EDITOR,
                    "Needs selectedImage(s) holding MediaFile ids; no media files are seeded."),
            new SkippedRoute("/roller-ui/authoring/mediaFileEdit.rol", Role.EDITOR,
                    "Needs mediaFileId; no media files are seeded."),
            new SkippedRoute("/roller-ui/authoring/overlay/mediaFileImageChooser.rol", Role.EDITOR,
                    "Takes no required parameter, but the whole of "
                            + "MediaFileImageChooser.jsp sits inside "
                            + "<c:if test=\"${not empty childFiles or not empty "
                            + "allDirectories}\">, so with no media files seeded there is "
                            + "nothing to assert on. Exercised with a real fixture by "
                            + "EditorSeoIT's featured-image picker journey."),
            new SkippedRoute("/roller-ui/authoring/templateEdit.rol", Role.EDITOR,
                    "Needs bean.id of a WeblogTemplate; without one it falls back to "
                            + "the .Templates view with an error. No custom templates are "
                            + "seeded. TemplateIT drives it with a template it creates, and "
                            + "checks the result reaches the public page URL."),
            new SkippedRoute("/roller-ui/admin/modifyUser.rol", Role.ADMIN,
                    "Needs bean.id or bean.userName; without one it renders .UserAdmin "
                            + "with a not-found error rather than the edit form. "
                            + "UserAdminIT drives it with a fixture it creates, through "
                            + "the admin search form."),
            new SkippedRoute("/roller-ui/admin/modifyUser!firstSave.rol", Role.ADMIN,
                    "Delegates to modifyUser; same missing user id."),

            // --- no page of their own ---

            new SkippedRoute("/roller-ui/home.rol", Role.ANONYMOUS,
                    "Returns redirect:/ -- the site front page, which is Velocity-rendered "
                            + "weblog content rather than a controller view. Belongs to a "
                            + "front-page test, not a controller sweep."),
            new SkippedRoute("/roller-ui/rendering/comments/session.rol", Role.ANONYMOUS,
                    "JSON for the comment form's page script, not a page: it needs "
                            + "entry= naming a published entry, and none is seeded, so "
                            + "without one it answers 404. CommentsControllerTest pins "
                            + "its contract."),
            new SkippedRoute("/roller-ui/admin/createUser!cancel.rol", Role.ADMIN,
                    "Pure redirect to userAdmin.rol, which the sweep already asserts on."),
            new SkippedRoute("/roller-ui/admin/modifyUser!cancel.rol", Role.ADMIN,
                    "Pure redirect to userAdmin.rol, which the sweep already asserts on."),
            new SkippedRoute("/roller-ui/login-redirect.rol", Role.EDITOR,
                    "login-redirect.jsp branches on how many weblogs the user owns: exactly "
                            + "one sends you to the entry editor, otherwise to the main menu. "
                            + "Other ITs create weblogs on the shared baseline, so the landing "
                            + "page depends on execution order. Assert it in a login test that "
                            + "owns its fixture."),

            // --- would break the sweep itself ---

            new SkippedRoute("/roller-ui/logout.rol", Role.ANONYMOUS,
                    "logout-redirect.jsp calls session.invalidate() and cancels the "
                            + "remember-me cookie. Visiting it mid-sweep logs the sweep out and "
                            + "cascade-fails every later route. Needs its own test."),

            // --- only meaningful before bootstrap ---

            new SkippedRoute("/roller-ui/install/install.rol", Role.ANONYMOUS,
                    "Returns redirect:/ once the WebloggerProvider is bootstrapped; the install "
                            + "wizard views are unreachable on a running instance. Covering it "
                            + "means a deliberately un-migrated database."),
            new SkippedRoute("/roller-ui/install/install!bootstrap.rol", Role.ANONYMOUS,
                    "Same: returns redirect:/ when already bootstrapped, and triggering a "
                            + "real bootstrap mid-suite would reinitialise the app under the "
                            + "other tests.")
    );

    // ------------------------------------------------------------ known broken

    /**
     * Routes confirmed broken by reading the source. They are listed rather than
     * quietly omitted so that fixing one fails the sweep and forces this list to
     * shrink -- a route that silently disappears from the catalogue is exactly
     * how untested pages come back.
     *
     * <p>Each claim below was checked against
     * {@code RollerViewResolver.init()}, the controller's return value, and the
     * contents of {@code WEB-INF/jsps/}.
     */
    private static final List<BrokenRoute> BROKEN = List.of(
            // Empty, and that is the point of keeping it.
            //
            // It previously held six routes, all since deleted outright (dead
            // handlers whose views had gone years earlier) rather than swept.
            //
            // Add an entry here when a route is found broken and cannot be fixed
            // immediately: RouteSweepIT asserts these are STILL broken, so the
            // eventual fix fails the test and forces the route into the real sweep.
    );

    // -------------------------------------------------------------- accessors

    /** Routes the sweep visits and asserts real content on. */
    public static List<Route> covered() {
        return java.util.stream.Stream.concat(NO_PARAMETERS.stream(), WEBLOG_ONLY.stream()).toList();
    }

    /** Routes reachable with no request parameters. */
    public static List<Route> noParameters() {
        return NO_PARAMETERS;
    }

    /** Routes needing only {@code weblog=it_weblog}. */
    public static List<Route> weblogOnly() {
        return WEBLOG_ONLY;
    }

    /** Routes left out of the sweep, each carrying its reason. */
    public static List<SkippedRoute> skipped() {
        return SKIPPED;
    }

    /** Routes confirmed broken, asserted to stay broken until somebody fixes them. */
    public static List<BrokenRoute> broken() {
        return BROKEN;
    }
}
