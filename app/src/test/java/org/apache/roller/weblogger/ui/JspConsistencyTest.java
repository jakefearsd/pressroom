/*
* Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  The ASF licenses this file to You
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

import org.apache.roller.weblogger.ui.controllers.RollerViewResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans that pin the admin UI's shared vocabulary: one status-pill
 * component, three button buckets, one selection bar, one confirm idiom,
 * no jQuery UI, no sidebar h3+hr. Each method names the task that made it
 * true; a red method means the vocabulary drifted, not that a page broke.
 *
 * <p>Every scan here covers EVERY JSP. Several of them used to skip an
 * {@code A_OWNED} set -- {@code EntryEdit.jsp}, {@code EntryEditor.jsp},
 * {@code PageEdit.jsp} -- while the CodeMirror rebuild owned those files in
 * a parallel worktree and this vocabulary could not be applied to them.
 * Task M1 applied it and deleted the set. There is deliberately no
 * replacement mechanism: an exemption that outlives the collaboration it
 * was written for is a scan that has quietly stopped scanning, and this one
 * survived two waves' worth of edits to the files it was excusing.
 */
class JspConsistencyTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static final Path ROLLER_CSS = Path.of("src/main/webapp/roller-ui/styles/roller.css");

    /**
     * Every JSP in the tree, once. A {@code List} rather than the
     * {@code Stream} this used to hand back: {@code Files.walk} holds an open
     * directory handle until its stream is closed, and a returned stream is
     * one no caller ever remembers to close.
     */
    static List<Path> jsps() throws IOException {
        try (Stream<Path> walk = Files.walk(JSPS)) {
            return walk.filter(p -> p.toString().endsWith(".jsp")).sorted().toList();
        }
    }

    @Test
    void statusIsAlwaysAStatusPill() throws IOException {
        List<Path> editorJsps = jsps().stream()
                .filter(p -> p.toString().contains("/editor/"))
                .toList();
        assertTrue(editorJsps.size() > 5,
                "Found too few editor JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : editorJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("badge bg-success") || src.contains("badge bg-info")
                    || src.contains("badge bg-warning") || src.contains("badge bg-danger")
                    || src.contains("badge bg-primary"), jsp + " uses a Bootstrap badge for status; use .status-pill");
            assertFalse(src.contains("EntryBox"), jsp + " renders the old status legend");
            assertFalse(src.contains("class=\"draftentry\"") || src.contains("class=\"pendingentry\""),
                    jsp + " tints rows by status");
        }

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String s : List.of("published", "draft", "pending", "scheduled", "trashed")) {
            assertTrue(css.contains(".status-pill.status-" + s), "roller.css lacks .status-pill.status-" + s);
        }
    }

    /**
     * Task B2: every button in the admin UI is one of exactly three buckets --
     * primary (one per screen, the one recommended action), destructive
     * (btn-danger), or secondary (everything else, including a bare .btn with
     * no variant class riding along). Bootstrap's stock .btn-success is not a
     * fourth bucket; every former use is either the screen's one primary
     * action (-> btn-primary) or not (-> btn-secondary).
     */
    @Test
    void buttonsUseThreeBucketsOnly() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        Pattern bareBtn = Pattern.compile("class=\"btn\"[^>]*>");
        for (Path jsp : allJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("btn-success"), jsp + ": btn-success -> btn-primary");
            assertFalse(bareBtn.matcher(src).find(),
                    jsp + ": bare .btn -> btn-secondary (or btn-primary if it is the screen's one primary action)");
            assertFalse(src.contains("<input type=\"button\" class=\"btn"),
                    jsp + ": <input type=button> as a button -> <button>");
            assertFalse(src.contains("<input id=\"toggleButton\""), jsp + ": media action bar still inputs");
        }

        // The disjunct this used to carry -- pages.contains("not empty pages") --
        // is trivially true the moment ANY <c:if test="${not empty pages}">
        // appears anywhere in the file, which makes the assertion pass whether
        // or not the top primary is actually inside one. Assert the real
        // structural claim instead: the addUrl anchor carrying btn-primary
        // sits inside a "${not empty pages}" guard, matching Pages.jsp's
        // actual shape byte-for-byte.
        String pages = Files.readString(JSPS.resolve("editor/Pages.jsp"), StandardCharsets.UTF_8);
        assertTrue(pages.contains("<c:if test=\"${not empty pages}\">\n    <a href=\"${addUrl}\" class=\"btn btn-primary btn-sm\">"),
                "Pages hides the top primary when empty");

        String members = Files.readString(JSPS.resolve("editor/Members.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(members, "btn btn-primary"), "Members has one primary");
    }

    /**
     * Task B3: every list screen (Entries, Submissions, MediaFileView) shows
     * exactly one selection bar -- a {@code .selection-bar} that names, via
     * {@code data-selection-bar}, the id of the {@code <form>} whose
     * checkboxes it counts. The bar and its form must live in the same JSP
     * (the delegated handler in roller.js looks the bar up by the checked
     * checkbox's own {@code .form.id}), and the old per-file bulk-action
     * markup ({@code #entriesBulkActions}) must be gone. Trash is
     * deliberately excluded -- see task-B3's brief: TrashController has no
     * bulk restore/delete handlers, so Trash.jsp keeps its per-row buttons
     * and whole-trash "Empty trash" only, with no selection bar at all.
     */
    @Test
    void everySelectionBarNamesItsForm() throws IOException {
        Pattern barPattern = Pattern.compile("class=\"selection-bar\"[^>]*data-selection-bar=\"([^\"]+)\"");
        Pattern formIdPattern = Pattern.compile("<form\\b[^>]*\\bid=\"([^\"]+)\"");

        int barsFound = 0;
        for (Path jsp : jsps()) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);

            Set<String> formIds = new HashSet<>();
            Matcher formMatcher = formIdPattern.matcher(src);
            while (formMatcher.find()) {
                formIds.add(formMatcher.group(1));
            }

            Matcher barMatcher = barPattern.matcher(src);
            while (barMatcher.find()) {
                barsFound++;
                String formId = barMatcher.group(1);
                assertTrue(formIds.contains(formId), jsp + ": .selection-bar names form \""
                        + formId + "\", which is not declared in this JSP");
            }
        }
        assertTrue(barsFound >= 3,
                "Found too few .selection-bar elements -- expected at least one each on "
                        + "Entries.jsp, Submissions.jsp and MediaFileView.jsp.");

        String entries = Files.readString(JSPS.resolve("editor/Entries.jsp"), StandardCharsets.UTF_8);
        assertFalse(entries.contains("entriesBulkActions"),
                "Entries.jsp still has the old #entriesBulkActions div -- its buttons "
                        + "must move into the .selection-bar");

        String submissions = Files.readString(JSPS.resolve("editor/Submissions.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(submissions, "class=\"selection-bar\""),
                "Submissions.jsp must have exactly one .selection-bar");

        String comments = Files.readString(JSPS.resolve("editor/Comments.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(comments, "class=\"selection-bar\""),
                "Comments.jsp must have exactly one .selection-bar");

        String mediaFileView = Files.readString(JSPS.resolve("editor/MediaFileView.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(mediaFileView, "class=\"selection-bar\""),
                "MediaFileView.jsp must have exactly one .selection-bar");
    }

    /**
     * Task B4: the Entries sidebar's date filters became native
     * {@code <input type="date">} elements, and jQuery UI -- the datepicker
     * widget that used to back them, and its only consumer anywhere in this
     * admin UI -- is gone entirely. No JSP may call its API or reference its
     * webjar, and head.jsp, which every admin page includes, must not load it.
     */
    @Test
    void noJqueryUiAnywhere() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : allJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("datepicker("), jsp + " still calls jQuery UI's datepicker()");
            assertFalse(src.contains("jquery-ui"), jsp + " still references the jquery-ui webjar");
        }

        String head = Files.readString(JSPS.resolve("tiles/head.jsp"), StandardCharsets.UTF_8);
        assertFalse(head.contains("jquery-ui"), "head.jsp still loads jquery-ui");
    }

    /**
     * Task B5: one date vocabulary across the admin UI. {@code fmt:formatDate}
     * formats in the JVM's default timezone -- the server's, which on this
     * application is nobody's clock in particular -- and emits no
     * machine-readable {@code datetime} attribute. {@code <rc:date>} formats
     * in the action weblog's zone, which is the clock every pubtime on this
     * application is already expressed in, and wraps the result in a
     * {@code <time>}.
     *
     * <p>The bundle half is the same rule stated where it can actually be
     * enforced. While {@code weblogEntryQuery.date.toStringFormat} still
     * <em>exists</em> as a key, a date format is a translatable string that
     * eight locale files may disagree about -- {@code ja} declared
     * {@code yy/MM/dd HH:mm} against the base bundle's
     * {@code MM/dd/yy hh:mm a} -- and any new call site is one
     * {@code <spring:message>} away from reintroducing request-locale
     * timezones to a data cell. Deleting the key is what makes the JSP-side
     * ban above unbypassable rather than merely observed.
     */
    @Test
    void noAdminTimestampIsFormattedWithFmtFormatDate() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : allJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("fmt:formatDate"),
                    jsp + ": format timestamps with <rc:date>, not fmt:formatDate");
            assertFalse(src.contains("weblogEntryQuery.date.toStringFormat"),
                    jsp + ": format timestamps with <rc:date>, not a message-bundle date pattern");
        }

        try (Stream<Path> bundles = Files.list(Path.of("src/main/resources"))) {
            List<Path> declaringTheDatePattern = bundles
                    .filter(p -> p.getFileName().toString().startsWith("ApplicationResources"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8)
                                    .contains("weblogEntryQuery.date.toStringFormat");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(declaringTheDatePattern.isEmpty(),
                    "a date format is not a translatable string -- <rc:date> owns the "
                            + "display pattern now, so no bundle may still declare "
                            + "weblogEntryQuery.date.toStringFormat: " + declaringTheDatePattern);
        }
    }

    /**
     * Task B6: the five sidebar tiles (EntriesSidebar, MediaFileSidebar,
     * CategoriesSidebar, TemplatesSidebar, MainMenuSidebar) speak the rail's
     * own grammar instead of a Bootstrap card with h3/hr section markup: a
     * caps-label ({@code p.sidebar-label}) per logical block, each wrapped in
     * a {@code div.sidebar-group}, no {@code <h3>} and no {@code <hr>}
     * anywhere in a sidebar tile. The two layouts that render a sidebar
     * (tiles-tabbedpage.jsp, tiles-mainmenupage.jsp) wrap the include in
     * {@code <aside id="adminSidebar">} rather than a {@code .card}/
     * {@code .card-body} pair -- checked only across the LEFT column, since
     * the main content column keeps its own {@code .card-body} untouched.
     *
     * <p>{@code buttonsUseThreeBucketsOnly} above only forbids btn-success
     * and a bare {@code class="btn"}; it does not count primaries per file,
     * so MediaFileSidebar.jsp's Search button (a second {@code btn-primary}
     * alongside MediaFileView.jsp's "Add a photo") needs no exemption there.
     */
    @Test
    void sidebarsUseTheRailGrammar() throws IOException {
        List<Path> sidebarJsps = jsps().stream()
                .filter(p -> p.getFileName().toString().endsWith("Sidebar.jsp"))
                .toList();
        assertEquals(5, sidebarJsps.size(),
                "Expected exactly the five sidebar tiles -- the scan is not looking where it thinks it is.");

        for (Path jsp : sidebarJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("<h3"), jsp + " still has an <h3> -- use <p class=\"sidebar-label\">");
            assertFalse(src.contains("<hr"), jsp + " still has an <hr> -- .sidebar-group's border-top replaces it");
            assertTrue(src.contains("sidebar-label"), jsp + " has no sidebar-label caps-label");
        }

        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = Files.readString(JSPS.resolve(layout), StandardCharsets.UTF_8);
            assertTrue(src.contains("id=\"adminSidebar\""), layout + " lacks the <aside id=\"adminSidebar\"> wrapper");

            int leftStart = src.indexOf("roller-column-left");
            int leftEnd = src.indexOf("roller-column-right");
            assertTrue(leftStart >= 0 && leftEnd > leftStart,
                    layout + ": could not isolate the left column -- the scan is not looking where it thinks it is.");
            String leftColumn = src.substring(leftStart, leftEnd);
            assertFalse(leftColumn.contains("card-body"),
                    layout + ": the sidebar is still wrapped in .card-body");
        }
    }

    /**
     * Task B7: one modal shape, one confirm idiom.
     *
     * <ul>
     *   <li>no {@code confirm(} in any JSP -- every
     *       destructive action is confirmed through {@code data-confirm}
     *       (see roller.js), never a native {@code window.confirm()} called
     *       from a JSP's own inline script;</li>
     *   <li>no {@code onsubmit=} attribute anywhere
     *       -- a form's behavior on submit is wired with
     *       {@code addEventListener}, not an inline handler, for the same
     *       reason confirm() moved out: an inline handler is a second place
     *       author-controlled or translated text can reach raw JavaScript;</li>
     *   <li>every {@code .modal-title} is a {@code <p>}, or -- if some other
     *       element carries the class -- that element is not a heading
     *       ({@code h1}-{@code h6}). A modal's title is one caps-label role,
     *       not a document heading competing with the page's own outline;</li>
     *   <li>every {@code .modal-footer} orders its buttons cancel/dismiss
     *       FIRST, primary-or-destructive action LAST. Bootstrap packs
     *       {@code .modal-footer} left-to-right in DOM order, so this is a
     *       markup convention, not something CSS enforces.</li>
     * </ul>
     *
     * <p>JSP comments are stripped before every scan below: a comment
     * documenting the banned pattern (e.g. UserEdit.jsp's explanation of why
     * its own button is NOT built with an inline {@code onsubmit}) is prose
     * about the rule, not a violation of it.
     */
    @Test
    void oneConfirmIdiomAndOneModalShape() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> confirmViolations = new ArrayList<>();
        List<String> onsubmitViolations = new ArrayList<>();
        List<String> modalTitleViolations = new ArrayList<>();
        List<String> modalFooterViolations = new ArrayList<>();

        Pattern modalTitleOwner = Pattern.compile(
                "<([A-Za-z][A-Za-z0-9]*)\\b[^>]*\\bclass=\"modal-title\"");
        Pattern modalFooter = Pattern.compile(
                "class=\"modal-footer\"[^>]*>(.*?)</div>", Pattern.DOTALL);
        Set<String> headingTags = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

        for (Path jsp : allJsps) {
            String name = jsp.getFileName().toString();
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));

            if (src.contains("confirm(")) {
                confirmViolations.add(name);
            }
            if (src.contains("onsubmit=")) {
                onsubmitViolations.add(name);
            }

            Matcher titleMatcher = modalTitleOwner.matcher(src);
            while (titleMatcher.find()) {
                String tag = titleMatcher.group(1).toLowerCase(Locale.ROOT);
                if (!"p".equals(tag) && headingTags.contains(tag)) {
                    modalTitleViolations.add(name + ": <" + tag + " class=\"modal-title\">");
                }
            }

            Matcher footerMatcher = modalFooter.matcher(src);
            while (footerMatcher.find()) {
                String footer = footerMatcher.group(1);
                int dismissIndex = footer.indexOf("data-bs-dismiss");
                int dangerIndex = footer.indexOf("btn-danger");
                int primaryIndex = footer.indexOf("btn-primary");
                int actionIndex = dangerIndex >= 0 && (primaryIndex < 0 || dangerIndex < primaryIndex)
                        ? dangerIndex : primaryIndex;
                if (dismissIndex >= 0 && actionIndex >= 0 && actionIndex < dismissIndex) {
                    modalFooterViolations.add(name + ": primary/destructive button comes "
                            + "before the dismiss button");
                }
            }
        }

        assertTrue(confirmViolations.isEmpty(),
                "confirm() called from a JSP's own script -- use data-confirm instead: "
                        + confirmViolations);
        assertTrue(onsubmitViolations.isEmpty(),
                "inline onsubmit= attribute -- wire the form with addEventListener instead: "
                        + onsubmitViolations);
        assertTrue(modalTitleViolations.isEmpty(),
                ".modal-title on a heading element -- use <p class=\"modal-title\"> instead: "
                        + modalTitleViolations);
        assertTrue(modalFooterViolations.isEmpty(),
                ".modal-footer buttons out of order -- dismiss/cancel first, "
                        + "primary/destructive last: " + modalFooterViolations);
    }

    // --- B8: errors point at the field they name ---

    /**
     * Every controller that calls {@code addFieldError} and the JSP(s) whose
     * form it renders. An explicit table rather than a derivation from
     * {@code RollerViewResolver}: the field a validation names is often on a
     * SIDEBAR tile ({@code TemplatesSidebar.jsp}) or in a modal on the list
     * screen ({@code Categories.jsp}), neither of which the view name spells
     * out. Adding a controller to {@code addFieldError} without adding it here
     * fails {@link #everyFieldErrorNamesAnIdThatExistsInItsJsp} -- an unmapped
     * caller is not silently skipped.
     */
    private static final Map<String, List<String>> FIELD_ERROR_JSPS = Map.ofEntries(
            Map.entry("editor/WeblogConfigController.java", List.of("editor/WeblogConfig.jsp")),
            Map.entry("editor/CategoryEditController.java", List.of("editor/Categories.jsp")),
            Map.entry("editor/PageEditController.java", List.of("editor/PageEdit.jsp")),
            Map.entry("editor/EntryEditController.java", List.of("editor/EntryEdit.jsp")),
            Map.entry("editor/TemplatesController.java", List.of("editor/TemplatesSidebar.jsp")),
            Map.entry("editor/MembersController.java", List.of("editor/Members.jsp")),
            Map.entry("core/CreateWeblogController.java", List.of("core/CreateWeblog.jsp")),
            Map.entry("core/ProfileController.java", List.of("core/Profile.jsp")),
            Map.entry("admin/UserEditController.java", List.of("admin/UserEdit.jsp")),
            Map.entry("admin/BusinessesController.java", List.of("admin/BusinessEdit.jsp")),
            // The token form's controls, shared by the agent page and Profile -> API tokens.
            Map.entry("controllers/TokenFormController.java", List.of("core/TokenForm.jsp")),
            Map.entry("admin/AgentController.java", List.of("admin/AgentCreate.jsp", "admin/Agent.jsp")));

    private static final Path CONTROLLERS =
            Path.of("src/main/java/org/apache/roller/weblogger/ui/controllers");

    private static final Pattern FIELD_ERROR_CALL =
            Pattern.compile("addFieldError\\(\\s*model\\s*,\\s*\"([^\"]+)\"");

    /**
     * A field id a controller names must be an id the page actually renders.
     *
     * <p>The marker is invisible when it misses: {@code getElementById} of a
     * renamed control simply returns null and {@code roller.js} moves on, so
     * the form comes back with a banner and nothing highlighted -- which looks
     * exactly like the behaviour before this feature existed. Nothing else
     * catches that, which is why it is a source scan rather than a convention.
     */
    @Test
    void everyFieldErrorNamesAnIdThatExistsInItsJsp() throws IOException {
        List<Path> controllers;
        try (Stream<Path> walk = Files.walk(CONTROLLERS)) {
            controllers = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }

        List<String> violations = new ArrayList<>();
        int idsChecked = 0;

        for (Path controller : controllers) {
            String src = Files.readString(controller, StandardCharsets.UTF_8);
            Matcher m = FIELD_ERROR_CALL.matcher(src);
            Set<String> fieldIds = new java.util.LinkedHashSet<>();
            while (m.find()) {
                fieldIds.add(m.group(1));
            }
            if (fieldIds.isEmpty()) {
                continue;
            }

            String key = controller.getParent().getFileName() + "/" + controller.getFileName();
            List<String> jspNames = FIELD_ERROR_JSPS.get(key);
            if (jspNames == null) {
                violations.add(key + " calls addFieldError but names no JSP in FIELD_ERROR_JSPS");
                continue;
            }

            StringBuilder markup = new StringBuilder();
            for (String jspName : jspNames) {
                markup.append(Files.readString(JSPS.resolve(jspName), StandardCharsets.UTF_8));
            }
            for (String fieldId : fieldIds) {
                idsChecked++;
                if (!markup.toString().contains("id=\"" + fieldId + "\"")) {
                    violations.add(key + " names field id \"" + fieldId
                            + "\", which no longer exists in " + jspNames);
                }
            }
        }

        assertTrue(idsChecked >= 15,
                "Found only " + idsChecked + " addFieldError ids -- the scan is not looking "
                        + "where it thinks it is");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * The three layouts an admin form can render through must all put the
     * joined ids on {@code <body>}; {@code roller.js} reads them from nowhere
     * else. Miss one and every form on it silently loses the marker --
     * {@code .Profile} and {@code .CreateWeblog} are simplepage, the editor
     * screens are tabbedpage, and {@code .MainMenu} is mainmenupage.
     */
    @Test
    void layoutsRenderInvalidFieldIds() throws IOException {
        for (String layout : List.of("tiles-tabbedpage.jsp", "tiles-simplepage.jsp",
                "tiles-mainmenupage.jsp")) {
            String src = Files.readString(JSPS.resolve("tiles").resolve(layout),
                    StandardCharsets.UTF_8);
            assertTrue(src.contains("data-invalid-fields=\"${fn:escapeXml(invalidFieldIds)}\""),
                    layout + " does not render data-invalid-fields on <body>");
            assertTrue(src.contains("${not empty invalidFieldIds}"),
                    layout + " emits the attribute unconditionally; a form with nothing "
                            + "wrong must carry no attribute at all");
            assertFalse(src.contains("<body>"),
                    layout + " still has a bare <body> tag -- the conditional attribute "
                            + "replaced it, so two body tags means the edit went to the "
                            + "wrong place");
        }

        String js = Files.readString(
                Path.of("src/main/webapp/theme/scripts/roller.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("invalidFields"),
                "roller.js does not read document.body.dataset.invalidFields, so nothing "
                        + "consumes what the layouts render");
        assertTrue(js.contains("is-invalid") && js.contains("aria-invalid"),
                "roller.js must set both the visual class and the accessible state");
    }

    // --- B9a: Global Config gets the settings rail ---

    /**
     * Task B9a: Global Config is the site's other long settings form, and it
     * now wears the same shape Weblog Settings does -- a
     * {@code .settings-grid} form with a {@code .settings-rail} holding a
     * section index and the Save button, so Save is reachable without
     * scrolling past nine display groups to find it.
     *
     * <p>Two things this pins beyond the markers. The section index is built
     * from {@code globalConfigDef.displayGroups}, so every group heading must
     * carry the {@code id} the index links to -- a hand-maintained list would
     * silently stop matching the moment {@code runtimeConfigDefs.xml} grew a
     * group. And the page's validation is attached with
     * {@code addEventListener}, not with an inline {@code onchange}/
     * {@code onkeyup} on every numeric field: the same reason
     * {@link #oneConfirmIdiomAndOneModalShape} bans inline {@code onsubmit}.
     *
     * <p>{@code id="saveButton"} is load-bearing beyond this page:
     * {@code RollerIT.setGlobalFlags} -- which every browser test that
     * permutes a runtime property goes through -- clicks it by that id.
     */
    @Test
    void globalConfigHasTheSettingsRail() throws IOException {
        String src = Files.readString(JSPS.resolve("admin/GlobalConfig.jsp"), StandardCharsets.UTF_8);
        String markup = withoutJspComments(src);

        assertTrue(markup.contains("class=\"settings-grid form-stacked\""),
                "GlobalConfig.jsp's <form> must be the .settings-grid container, "
                        + "the same shape WeblogConfig.jsp uses");
        assertEquals(1, countOccurrences(markup, "<aside class=\"settings-rail\">"),
                "GlobalConfig.jsp must have exactly one .settings-rail");
        assertTrue(markup.contains("class=\"section-index\""),
                "the rail must carry a .section-index built from the display groups");
        assertTrue(markup.contains("href=\"#cfg-${"),
                "the section index must link to the group headings by id");
        assertTrue(markup.contains("id=\"cfg-${"),
                "every display-group heading must carry the id its index entry links to");
        assertTrue(markup.contains("id=\"saveButton\"") && markup.contains("btn btn-primary w-100"),
                "Save must be the rail's full-width primary and keep id=saveButton, "
                        + "which RollerIT.setGlobalFlags clicks");

        assertFalse(markup.contains("onchange="),
                "GlobalConfig.jsp still has an inline onchange= -- attach the validation "
                        + "with one delegated addEventListener instead");
        assertFalse(markup.contains("onkeyup="),
                "GlobalConfig.jsp still has an inline onkeyup= -- attach the validation "
                        + "with one delegated addEventListener instead");

        assertTrue(markup.contains("class=\"form-check\""),
                "boolean properties render as a .form-check with the label beside the box");
    }

    // --- B9b: media add leads with the drop zone; the theme chooser is cards ---

    /**
     * Task B9b: the upload form asks for the files first. Description,
     * copyright and tags used to sit above the drop zone -- three optional
     * fields between an author and the only thing they came to the page to do,
     * applied identically to every file in a batch. They are in a collapsed
     * {@code <details>} below it now.
     *
     * <p>The folder {@code <select>} stays ABOVE the drop zone: it is not
     * optional the way the three text fields are (it decides where the batch
     * lands) and there is no undo for dropping thirty files into the wrong
     * directory.
     *
     * <p>The three ids the drop-zone script and every media IT drive
     * ({@code mediaDropZone}, {@code uploadedFiles}, {@code uploadButton}) are
     * asserted here too, because reordering markup is exactly the edit that
     * loses one by accident.
     */
    @Test
    void mediaAddLeadsWithTheDropZone() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/MediaFileAdd.jsp"), StandardCharsets.UTF_8));

        int folder = markup.indexOf("id=\"mfadd_bean_directoryId\"");
        int dropZone = markup.indexOf("id=\"mediaDropZone\"");
        int details = markup.indexOf("<details class=\"editor-details\"");
        int description = markup.indexOf("id=\"mfadd_bean_description\"");

        assertTrue(folder >= 0 && dropZone >= 0 && details >= 0 && description >= 0,
                "MediaFileAdd.jsp is missing one of folder/drop zone/details/description "
                        + "-- the scan is not looking where it thinks it is");
        assertTrue(folder < dropZone,
                "the folder select must stay above the drop zone: it decides where the "
                        + "batch lands and there is no undo");
        assertTrue(dropZone < details,
                "the drop zone must come before the optional Details drawer");
        assertTrue(details < description,
                "description/copyright/tags belong inside the Details drawer");

        for (String id : List.of("mediaDropZone", "uploadedFiles", "uploadButton", "mediaChosenFiles")) {
            assertTrue(markup.contains("id=\"" + id + "\""),
                    "MediaFileAdd.jsp lost id=\"" + id + "\", which its own script "
                            + "and every media IT drive");
        }
    }

    /**
     * Task B9b: picking a theme is a grid of cards, each showing the theme's
     * own preview image, not a one-line {@code <select>} beside a thumbnail
     * fetched over ajax. Every card is a {@code <label>} wrapping a radio, so
     * the whole card is the hit target and the browser does the grouping.
     *
     * <p>The chooser's {@code change} handler is delegated rather than an
     * inline {@code onchange}, for the reason
     * {@link #globalConfigHasTheSettingsRail} gives: an inline handler is a
     * second place generated text reaches raw JavaScript. The old
     * {@code #themeSelector} select and the {@code #themeThumbnail} it drove
     * must both be gone -- leaving either means two chooser mechanisms on one
     * page, only one of which the state machine listens to.
     */
    @Test
    void themeChooserIsACardGrid() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/ThemeEdit.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("class=\"theme-cards\""),
                "ThemeEdit.jsp must render the .theme-cards grid");
        assertTrue(markup.contains("<label class=\"theme-card\">"),
                "every theme is a <label class=\"theme-card\"> wrapping its radio");
        assertTrue(markup.contains("type=\"radio\" name=\"selectedThemeId\""),
                "the cards must post the same selectedThemeId the controller reads");
        assertFalse(markup.contains("id=\"themeSelector\""),
                "the old <select id=\"themeSelector\"> must be gone, not merely hidden");
        assertFalse(markup.contains("id=\"themeThumbnail\""),
                "the ajax-driven thumbnail is replaced by each card's own preview image");
        assertFalse(markup.contains("onchange="),
                "wire the chooser with a delegated change listener, not an inline onchange");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String rule : List.of(".theme-cards", ".theme-card", ".theme-card-thumb",
                ".theme-card-name", ".theme-card-desc")) {
            assertTrue(css.contains(rule), "roller.css lacks " + rule);
        }
    }

    // --- B9c: the main menu lists weblogs as quiet rows ---

    /**
     * Task B9c: the first screen an author lands on listed each weblog as a
     * Bootstrap card carrying a raw absolute URL on its own line and a
     * {@code .btn-group} of four equally-loud buttons -- a toolbar, on a
     * screen whose job is to get you into one weblog. It is a quiet row now:
     * the name links to the weblog (so the URL line has nothing left to say),
     * the handle and entry count wear the mono data face, and the actions are
     * one primary "New entry" beside plain {@code .quiet-link}s.
     *
     * <p>{@code h2.mm_weblog_name} (an h3 until the heading sweep put it one
     * level under the page's h1) survives the restyle deliberately:
     * {@code Routes.java} identifies {@code menu.rol} by that selector, so
     * renaming it here without moving the marker in the same commit fails
     * {@code RouteSweepIT}.
     *
     * <p>Two {@code btn btn-primary} occurrences, not one: the empty state
     * above the loop ("you have no blog yet") has its own, and the two branches
     * are mutually exclusive at render time even though both are in the source.
     */
    @Test
    void mainMenuListsWeblogsAsQuietRows() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("core/MainMenu.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("class=\"weblog-row\""),
                "each weblog must be a .weblog-row");
        assertTrue(markup.contains("<h2 class=\"mm_weblog_name section-head\">"),
                "h2.mm_weblog_name is Routes.java's marker for menu.rol -- keep it");
        assertTrue(markup.contains("class=\"weblog-actions\""),
                "the row's actions belong in a .weblog-actions strip");
        assertTrue(markup.contains("class=\"quiet-link\""),
                "everything but New entry is a quiet link, not a button");

        assertFalse(markup.contains("btn-group"),
                "the four-button toolbar is gone -- one primary plus quiet links");
        assertFalse(markup.contains("yourWeblogBox"),
                "the row is not a Bootstrap card any more");
        assertFalse(markup.contains("btn btn-secondary"),
                "a secondary button in the row is the toolbar coming back");

        assertEquals(2, countOccurrences(markup, "btn btn-primary"),
                "exactly two primaries in the source: the empty state's and the "
                        + "row's New entry, which never render together");
        assertEquals(1, countOccurrences(markup, "urls.weblogAbsolute(perms.weblog)"),
                "the raw URL line is gone; the weblog name is the only link to it");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String rule : List.of(".weblog-row", ".weblog-actions")) {
            assertTrue(css.contains(rule), "roller.css lacks " + rule);
        }
    }

    // --- B10: the weblog switcher in the top bar ---

    /**
     * Task B10: the top bar's brand now carries a {@code .weblog-switcher}
     * dropdown when the signed-in user holds more than one weblog
     * ({@code BaseController.populateCommonModel} adds {@code userWeblogs}
     * only in that case, so the {@code <c:if>} around this markup is what
     * keeps a one-weblog session's top bar unchanged). The confirm-idiom
     * rule applies here too: this is a navigation control, not a destructive
     * one, so it needs no {@code data-confirm} -- but it must not regress to
     * the inline {@code onclick} idiom {@code oneConfirmIdiomAndOneModalShape}
     * already bans on every other admin screen.
     */
    @Test
    void theTopBarCarriesTheSwitcher() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("tiles/bannerStatus.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("weblog-switcher"),
                "bannerStatus.jsp must render the .weblog-switcher dropdown");
        assertFalse(markup.contains("onclick="),
                "the switcher is a nav control -- no inline onclick, ever");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        assertTrue(css.contains(".weblog-switcher"), "roller.css lacks .weblog-switcher");
    }

    /**
     * The top bar's "Main Menu" link is for someone who can use it: anonymous
     * visitors (it leads to the login page, then the menu) and authors. A signed-in
     * reader holds no global action and {@code menu.rol} refuses them, so on the
     * login page and the site directory the link led to access-denied.
     * {@code BaseController.populateCommonModel} adds {@code userIsAuthor} (the
     * global {@code login} action); {@code BaseControllerTest} pins it.
     */
    @Test
    void theTopBarOffersTheMainMenuOnlyToSomeoneWhoCanUseIt() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("tiles/bannerStatus.jsp"), StandardCharsets.UTF_8));

        int link = markup.indexOf("/roller-ui/menu.rol");
        assertTrue(link > 0, "bannerStatus.jsp no longer links the menu");
        assertEquals(1, countOccurrences(markup, "/roller-ui/menu.rol"), "one Main Menu link");
        String guard = "<c:if test=\"${empty authenticatedUser or userIsAuthor}\">";
        int open = markup.lastIndexOf(guard, link);
        assertTrue(open >= 0, "the Main Menu link is not guarded by " + guard);
        assertTrue(markup.indexOf("</c:if>", open) > link,
                "the guard closes before the Main Menu link");
    }

    /**
     * B9's `<details>` disclosure on MediaFileAdd.jsp had two loose ends,
     * folded in here per B12's deferred-minors decision. First, its summary
     * used a bare {@code cursor:pointer} rule rather than the same chevron
     * treatment {@code .editor-drawer} already gives its own disclosure
     * toggles ({@code EntryEdit.jsp}'s SEO drawer) -- two disclosure widgets
     * with two different looks reads as two components, not one idiom.
     * Second, the details started collapsed on every render, including a
     * re-render after a validation refusal that carries typed description,
     * copyright or tags -- collapsing typed-but-unsubmitted text out of view
     * is the same mistake the field-error work exists to prevent for
     * top-level fields, just one level down.
     */
    @Test
    void mediaFileAddDetailsMatchesTheDrawerChevronAndReopensWithContent() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/MediaFileAdd.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains(
                "<details class=\"editor-details\" "
                        + "${not empty bean.description or not empty bean.tagsAsString "
                        + "or not empty bean.copyrightText ? 'open' : ''}>"),
                "the details must reopen when a re-render carries description, tags or copyright");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        int drawerBlockStart = css.indexOf(".editor-drawer {");
        int drawerBlockEnd = css.indexOf("}", drawerBlockStart);
        String drawerBlock = css.substring(drawerBlockStart, drawerBlockEnd);
        int detailsBlockStart = css.indexOf(".editor-details > summary {");
        int detailsBlockEnd = css.indexOf("}", detailsBlockStart);
        String detailsBlock = css.substring(detailsBlockStart, detailsBlockEnd);

        assertTrue(detailsBlock.contains("var(--accent)"),
                ".editor-details > summary must wear .editor-drawer's accent color");
        assertTrue(detailsBlock.contains("font-weight: 600"),
                ".editor-details > summary must wear .editor-drawer's weight");
        assertTrue(drawerBlock.contains("var(--accent)") && drawerBlock.contains("font-weight: 600"),
                "sanity: .editor-drawer itself must still carry the properties being reused");

        int chevronStart = css.indexOf(".editor-details > summary::");
        assertTrue(chevronStart >= 0, ".editor-details > summary must have its own rotating chevron, "
                + "the same shape as .editor-drawer::after / .editor-drawer:not(.collapsed)::after");
    }

    /**
     * B9's "first card checked" fallback only covered a blank
     * {@code selectedThemeId} (a weblog on a custom theme). A weblog whose
     * {@code editorTheme} names a shared theme that no longer exists (a
     * retired id, or a directory removed by hand) sends a NON-blank
     * {@code selectedThemeId} that matches no card, so the old condition
     * {@code opt.id == selectedThemeId or (empty selectedThemeId and
     * themeStatus.first)} checks nothing at all -- the browser posts no
     * {@code selectedThemeId}, and save answers "theme not found". The
     * fallback must trigger whenever the weblog's current theme is not
     * found among the rendered cards, not only when it is blank.
     */
    @Test
    void themeCardFallsBackToTheFirstCardWhenTheCurrentThemeIsNotInTheList() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/ThemeEdit.jsp"), StandardCharsets.UTF_8));

        assertFalse(markup.contains("empty selectedThemeId and themeStatus.first"),
                "the old fallback only handled a blank selectedThemeId, not one naming a missing theme");
        assertTrue(markup.contains("selectedThemeFound"),
                "the checked condition must fall back to the first card whenever the weblog's "
                        + "current theme id is not found among the rendered cards");
    }

    // --- B11's bug sweep, folded in from the class it was routed to ---

    /**
     * Task B11: every {@code <div>} a JSP opens, it closes.
     *
     * <p>{@code TemplateEdit.jsp} opened {@code <div id="accordion">} to wrap
     * the advanced-settings panel and never closed it, so the accordion's own
     * closing {@code </div>} was mistaken for the outer one and everything
     * after that panel -- the closing {@code </c:if>}, the CSRF input,
     * {@code </form>} and the page's own script -- rendered one level deeper
     * than the surrounding markup expects.
     *
     * <p>{@code <c:choose>}/{@code <c:when>}/{@code <c:otherwise>} branches
     * need no special handling: each branch balances its own {@code <div>}s
     * independently, so a plain count over the whole file, comments stripped,
     * is the right measure.
     */
    @Test
    void everyDivOpenedInAJspIsClosed() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : allJsps) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            int opens = countMatches(src, Pattern.compile("<div\\b"));
            int closes = countMatches(src, Pattern.compile("</div>"));
            if (opens != closes) {
                violations.add(jsp + ": " + opens + " <div> vs " + closes + " </div>");
            }
        }

        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * Task B11: no card or section heading skips a level.
     *
     * <p>Every one of them used {@code h4}, or in one place {@code h5},
     * immediately inside a page whose own title is the tiles layout's
     * {@code h2.roller-page-title}, with no {@code h3} anywhere between.
     * {@code h3} is the single level every other admin card already used.
     *
     * <p>This subsumes what used to be a MainMenuSidebar-only assertion in
     * {@code AdminJspHygieneTest}: a scan naming one file cannot say that the
     * rule holds, only that one file obeys it.
     */
    @Test
    void noHeadingLevelIsSkippedToH4OrH5() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : allJsps) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            if (src.contains("<h4") || src.contains("<h5")) {
                violations.add(jsp.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "no JSP may skip a heading level to h4/h5: " + violations);
    }

    /** The whole webapp, so the {@code roller-ui/errors} pages are scanned too. */
    static final Path WEBAPP = Path.of("src/main/webapp");

    private static final Pattern HEADING_TAG = Pattern.compile("<h([1-6])\\b");

    /**
     * Layouts whose document only ever opens in an iframe inside a modal on
     * an admin page that the tabbed layout already titled {@code h1} (the
     * media editor and the image chooser).
     * Their content continues that page's outline, so it starts at h2 like
     * any other admin fragment rather than printing a second h1.
     */
    private static final Set<String> IFRAMED_LAYOUTS = Set.of("/WEB-INF/jsps/tiles/tiles-popuppage.jsp");

    /**
     * No heading skips a level, counting from the title the page's layout
     * already printed.
     *
     * <p>The h4/h5 ban above was written when the layouts titled every page
     * {@code h2.roller-page-title} and the cards used {@code h3}. The layouts
     * moved to {@code h1}, and the cards stayed {@code h3.section-head}, so
     * fourteen screens went h1 to h3 with nothing in between -- a gap a
     * screen reader's heading list shows as a missing section -- and the scan
     * could not see it, because it judged each file alone. Two pages had the
     * opposite fault: {@code denied.jsp} and the install pages sit in layouts
     * that print no title, and opened at {@code h2} with no {@code h1} at all.
     *
     * <p>So each content JSP is judged from its layout, which this reads from
     * {@code RollerViewResolver} itself: under a layout that prints an
     * {@code h1}, the fragment starts at h2 and may not print a second h1.
     * Under one that prints none, the fragment must open with the h1. A JSP
     * no view names is a whole document when it has an {@code <html>} (it
     * starts at h1) and otherwise an include into the admin chrome (h2).
     * Within a file, a heading may go at most one level below the one before.
     * The one layout read differently is the popup ({@link #IFRAMED_LAYOUTS}).
     *
     * <p>The emphasis rule is untouched by any of this: the level is for the
     * outline, the class carries the look ({@code .section-head} is the 12px
     * caps label whether it is an h2 or an h3).
     */
    @Test
    void noHeadingSkipsALevelBelowItsPageTitle() throws IOException {
        Map<Path, Integer> titledBy = layoutTitleLevels();
        assertEquals(0, titledBy.get(WEBAPP.resolve("roller-ui/errors/denied.jsp")),
                "the error layout prints no title -- did the view map stop being read?");
        assertEquals(1, titledBy.get(JSPS.resolve("core/Login.jsp")),
                "the login layout prints the h1 -- did the view map stop being read?");

        List<String> violations = new ArrayList<>();
        for (Path jsp : everyServedJsp()) {
            String src = blankJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            int base = titledBy.getOrDefault(jsp, src.contains("<html") ? 0 : 1);
            violations.addAll(headingSkips(WEBAPP.relativize(jsp).toString(), src, base));
        }

        assertEquals(List.of(), violations,
                "a heading skips a level (h2.section-head is the card/section level under the "
                        + "layout's h1; a page in the error or install layout titles itself h1)");
    }

    /** The scan itself, on planted markup. */
    @Test
    void theHeadingScanCountsFromTheLayoutsTitle() {
        assertEquals(List.of(),
                headingSkips("A.jsp", "<h2 class=\"section-head\">a</h2><h3>b</h3><h2>c</h2>", 1));
        assertEquals(List.of("B.jsp:2  h3 follows h1"),
                headingSkips("B.jsp", "<div>\n<h3 class=\"section-head\">a</h3>", 1));
        assertEquals(List.of("C.jsp:1  h1 under a layout that already printed the h1"),
                headingSkips("C.jsp", "<h1>a</h1>", 1));
        assertEquals(List.of("D.jsp:1  h2 follows h0"),
                headingSkips("D.jsp", "<h2 class=\"roller-page-title\">Access denied</h2>", 0));
        assertEquals(List.of(),
                headingSkips("E.jsp", "<h1>one branch</h1><h1>another branch</h1><header>", 0));
    }

    private static List<String> headingSkips(String name, String src, int base) {
        List<String> skips = new ArrayList<>();
        int previous = base;
        Matcher heading = HEADING_TAG.matcher(src);
        while (heading.find()) {
            int level = Integer.parseInt(heading.group(1));
            if (level <= base) {
                skips.add(name + ":" + lineOf(src, heading.start()) + "  h" + level
                        + " under a layout that already printed the h" + base);
            } else if (level > previous + 1) {
                skips.add(name + ":" + lineOf(src, heading.start()) + "  h" + level
                        + " follows h" + previous);
            }
            previous = level;
        }
        return skips;
    }

    private static List<Path> everyServedJsp() throws IOException {
        try (Stream<Path> walk = Files.walk(WEBAPP)) {
            List<Path> all = walk.filter(p -> p.toString().endsWith(".jsp")).sorted().toList();
            assertTrue(all.size() > 60, "found only " + all.size() + " JSPs under " + WEBAPP);
            return all;
        }
    }

    /**
     * For each content JSP a view names: the heading level its layout has
     * already used -- 1 when the layout prints an {@code h1}, else 0. A JSP
     * two views share takes the stricter (lower) one. Read by recording what
     * {@code RollerViewResolver.init()} registers, so a new view or a layout
     * swap is seen without this test being told.
     */
    private static Map<Path, Integer> layoutTitleLevels() throws IOException {
        Map<String, String> layoutOf = new HashMap<>();
        Map<String, String> contentOf = new HashMap<>();
        RollerViewResolver recorder = new RollerViewResolver() {
            @Override
            public void addDefinition(String name, String layout, Map<String, String> attributes) {
                super.addDefinition(name, layout, attributes);
                layoutOf.put(name, layout);
                contentOf.put(name, attributes.get("content"));
            }

            @Override
            public void addExtendedDefinition(String name, String extendsName,
                                              Map<String, String> overrides) {
                super.addExtendedDefinition(name, extendsName, overrides);
                layoutOf.put(name, layoutOf.get(extendsName));
                contentOf.put(name, overrides.getOrDefault("content", contentOf.get(extendsName)));
            }
        };
        recorder.init();

        Map<Path, Integer> levels = new HashMap<>();
        for (Map.Entry<String, String> view : contentOf.entrySet()) {
            String content = view.getValue();
            if (content == null || !content.endsWith(".jsp")) {
                continue;   // a base definition: its content is "${content}"
            }
            String layoutPath = layoutOf.get(view.getKey());
            Path layout = WEBAPP.resolve(layoutPath.substring(1));
            int level = IFRAMED_LAYOUTS.contains(layoutPath)
                    || withoutJspComments(Files.readString(layout, StandardCharsets.UTF_8))
                            .contains("<h1") ? 1 : 0;
            levels.merge(WEBAPP.resolve(content.substring(1)), level, Math::min);
        }
        return levels;
    }

    /**
     * Task B11: no {@code <str:...>} markup, because there is no {@code str}
     * taglib to interpret it.
     *
     * <p>{@code MediaFileImageChooser.jsp} and {@code MediaFileView.jsp}
     * wrapped a filename in {@code <str:truncateNicely>}, a Struts tag from
     * before the migration to Spring MVC. With no taglib declared, the
     * container treats the unrecognised prefix as literal markup: the opening
     * and closing tags rendered verbatim around the filename, on every media
     * tile, truncating nothing. This is the JSP-side sibling of the Velocity
     * leniency trap in CLAUDE.md's Templates section.
     */
    @Test
    void noStrTagRendersInAnyJsp() throws IOException {
        List<Path> allJsps = jsps();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : allJsps) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            if (src.contains("<str:")) {
                violations.add(jsp.toString());
            }
        }

        assertTrue(violations.isEmpty(), "dead <str:...> markup (no str taglib exists): " + violations);
    }

    /**
     * Confirms the premise {@link #noStrTagRendersInAnyJsp} depends on: no
     * {@code str} taglib is declared anywhere under {@code webapp/}, so
     * {@code <str:...>} could never have been a real custom tag -- it was
     * always going to render as literal text.
     */
    @Test
    void noStrTaglibIsDeclaredAnywhere() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/webapp"))) {
            List<Path> withStrPrefix = walk
                    .filter(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".tld"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("prefix=\"str\"");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(withStrPrefix.isEmpty(),
                    "expected no str taglib declaration, found: " + withStrPrefix);
        }
    }

    // --- B11 review follow-up: the href ratchet ---

    /**
     * Every {@code href="${...}"} in the tree is built somewhere that escapes,
     * and this is a tree-wide rule rather than a list of repaired files
     * because the obvious grep is what missed the real one.
     *
     * <p>{@code grep 'href="$&#123;' | grep -v escapeXml | grep -v 'c:url\|urls\.'}
     * turns out to be almost all false positives -- nearly every one is fed by
     * a {@code <c:url>}-built variable, which URL-encodes its parameters and
     * entity-encodes the joining {@code &} -- and grepping by variable NAME
     * cannot see the shape that actually broke: a {@code urls.*} helper call
     * with a raw field appended straight after it
     * ({@code href="$&#123;urls.weblogAbsolute(w)&#125;$&#123;p.slug&#125;"}).
     * A page slug is author-controlled and restricted only against {@code /}
     * and reserved names, never against quote or angle-bracket characters, so
     * {@code x" onmouseover="alert(1)} broke out of the attribute.
     *
     * <p>So the rule is stated over the whole VALUE instead: strip every
     * {@code fn:escapeXml(...)} expression, every bare {@code urls.*(...)}
     * helper call (server-built from ids and handles, no author text in it),
     * every inline {@code <c:url .../>} tag, and every {@code $&#123;var&#125;}
     * whose {@code var} a {@code <c:url>} in the same file declares. Whatever
     * expression is left is one nobody has accounted for.
     *
     * <p>The last two are the same form written two ways -- {@code <c:url
     * var="x" value="..."/>} used later as {@code $&#123;x&#125;}, versus the
     * tag written straight into the attribute -- and the inline spelling is
     * invisible to the {@code href="$&#123;} grep above, which is how three
     * of them (UserEdit's Cancel and both tab layouts' menu links) went
     * unlisted until this scan ran.
     */
    @Test
    void everyHrefExpressionIsEscapedOrServerBuilt() throws IOException {
        // An attribute value routinely contains a whole nested tag, whose own
        // attributes are single-quoted -- href="<c:url value='${x}'/>" -- so
        // the value runs to the next DOUBLE quote, not the next quote.
        Pattern hrefValue = Pattern.compile("href=\"([^\"]*\\$\\{[^\"]*)\"");
        Pattern cUrlVar = Pattern.compile("<c:url\\b[^>]*\\bvar=\"([^\"]+)\"", Pattern.DOTALL);
        Pattern escapeXmlCall = Pattern.compile("\\$\\{\\s*fn:escapeXml\\([^}]*\\)\\s*\\}");
        Pattern urlsHelperCall = Pattern.compile("\\$\\{\\s*urls\\.[A-Za-z0-9_]+\\([^}]*\\)\\s*\\}");
        Pattern inlineCUrlTag = Pattern.compile("<c:url\\b[^>]*/>");

        List<String> violations = new ArrayList<>();
        int hrefsChecked = 0;

        for (Path jsp : jsps()) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));

            Set<String> declared = new HashSet<>();
            Matcher varMatcher = cUrlVar.matcher(src);
            while (varMatcher.find()) {
                declared.add(varMatcher.group(1));
            }

            Matcher hrefMatcher = hrefValue.matcher(src);
            while (hrefMatcher.find()) {
                hrefsChecked++;
                String remaining = hrefMatcher.group(1);
                remaining = escapeXmlCall.matcher(remaining).replaceAll("");
                remaining = urlsHelperCall.matcher(remaining).replaceAll("");
                remaining = inlineCUrlTag.matcher(remaining).replaceAll("");
                for (String var : declared) {
                    remaining = remaining.replace("${" + var + "}", "");
                }
                if (remaining.contains("${")) {
                    violations.add(jsp + ":" + lineOf(src, hrefMatcher.start())
                            + " -> " + hrefMatcher.group());
                }
            }
        }

        assertTrue(hrefsChecked >= 25,
                "Only " + hrefsChecked + " EL-valued hrefs found -- the scan is not "
                        + "looking where it thinks it is.");
        assertTrue(violations.isEmpty(),
                "an href built from an expression that is neither fn:escapeXml'd, nor a "
                        + "<c:url>-declared variable, nor a urls.* helper call:\n  "
                        + String.join("\n  ", violations));
    }

    /**
     * Criterion 25 (spec 2026-10-13): every POST form carries the CSRF token.
     * Spring Security refuses a POST without it, so a form that lost it is a
     * page that 403s on submit -- found by a person, not a test. Forms whose
     * tag names its action before its method are not seen; new forms put
     * method="post" first.
     */
    @Test
    void everyPostFormCarriesACsrfInput() throws IOException {
        Pattern formTag = Pattern.compile("<form\\b[^>]*>");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path jsp : jsps()) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            Matcher m = formTag.matcher(src);
            while (m.find()) {
                if (!m.group().toLowerCase(Locale.ROOT).matches("(?s).*method\\s*=\\s*\"post\".*")) {
                    continue;
                }
                checked++;
                int end = src.indexOf("</form>", m.end());
                String body = end < 0 ? src.substring(m.end()) : src.substring(m.end(), end);
                if (!body.contains("<sec:csrfInput/>")) {
                    missing.add(jsp + ":" + lineOf(src, m.start()));
                }
            }
        }
        assertTrue(checked > 40,
                "only " + checked + " POST forms found -- the scan is not looking where it thinks it is");
        assertTrue(missing.isEmpty(), "POST forms with no <sec:csrfInput/>:\n  " + String.join("\n  ", missing));
    }

    private static int lineOf(String src, int index) {
        return (int) src.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static int countMatches(String haystack, Pattern pattern) {
        Matcher m = pattern.matcher(haystack);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * JSP comments are not part of the rendered page, so a scan asserting
     * that some pattern is ABSENT must not be defeated by prose that merely
     * mentions it (or, worse, pass for the wrong reason on prose that
     * mentions the FIXED pattern). See AdminJspHygieneTest, which established
     * this helper first.
     */
    private static String withoutJspComments(String src) {
        return src.replaceAll("(?s)<%--.*?--%>", "");
    }

    /** {@link #withoutJspComments}, keeping each comment's newlines so line numbers stay true. */
    private static String blankJspComments(String src) {
        Matcher comment = Pattern.compile("(?s)<%--.*?--%>").matcher(src);
        StringBuilder out = new StringBuilder();
        while (comment.find()) {
            comment.appendReplacement(out, comment.group().replaceAll("[^\\n]", ""));
        }
        return comment.appendTail(out).toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
