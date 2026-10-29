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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans pinning the 2026-08-20 fit-and-finish repairs to the admin
 * JSP tree. Each one exists because the property it asserts is invisible in
 * a rendered page and therefore cannot regress loudly: a flash region that
 * stopped announcing itself, a layout that lost its {@code lang}, a table
 * whose headers stopped being scoped, or a screen whose one primary action
 * quietly became three.
 *
 * <p>Every test collects ALL violations before asserting, so one run names
 * the whole repair list rather than the first file that happens to fail.
 */
class AdminJspHygieneTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");
    private static final Path STYLES = Path.of("src/main/webapp/pressroom-ui/styles/roller.css");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JSP comments are not part of the rendered page, so a scan asserting that
     * some attribute or literal is PRESENT must not be satisfied by prose
     * about it. (This is not hypothetical: the CSRF-focus scan below was
     * satisfied by the very comment explaining the fix.)
     */
    private static String withoutJspComments(String src) {
        return src.replaceAll("(?s)<%--.*?--%>", "");
    }

    /**
     * The single element carrying {@code id="<id>"}, from its opening angle
     * bracket to the matching close -- so an attribute assertion is about THAT
     * control and not merely about the file it lives in.
     *
     * <p>Angle brackets are counted rather than searched for, because an
     * attribute value here routinely contains a whole nested tag
     * ({@code title="<spring:message .../>"}), which a naive
     * {@code indexOf('>')} would mistake for the end of the element.
     */
    private static String elementCarrying(String src, String id) {
        return elementAround(src, src.indexOf("id=\"" + id + "\""));
    }

    /**
     * The tag name of the element that owns each occurrence of an attribute --
     * "button", "form", and so on. Lets a test say WHERE an attribute sits, not
     * merely that the file contains it somewhere.
     */
    private static List<String> ownersOf(String src, String attribute) {
        List<String> owners = new ArrayList<>();
        Matcher m = Pattern.compile(Pattern.quote(attribute) + "\\s*=").matcher(src);
        while (m.find()) {
            String element = elementAround(src, m.start());
            Matcher name = Pattern.compile("^<\\s*([A-Za-z][A-Za-z0-9:_-]*)").matcher(element);
            owners.add(name.find() ? name.group(1).toLowerCase(Locale.ROOT) : "?");
        }
        return owners;
    }

    /** The element bracketing {@code marker}, or "" if there is none. */
    private static String elementAround(String src, int marker) {
        if (marker < 0) {
            return "";
        }
        int start = -1;
        for (int i = marker - 1; i >= 0; i--) {
            if (src.charAt(i) == '<') {
                String between = src.substring(i + 1, marker);
                if (between.chars().filter(c -> c == '<').count()
                        == between.chars().filter(c -> c == '>').count()) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = marker; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
                depth--;
            }
        }
        return "";
    }

    private static String jsp(String relative) {
        Path p = JSPS.resolve(relative);
        assertTrue(Files.isRegularFile(p), "JSP moved or deleted? " + p.toAbsolutePath());
        return read(p);
    }

    // ---------------------------------------------------------------- Task 11

    /**
     * A flash message that is not in a live region is invisible to a screen
     * reader: the page has already finished loading when it arrives, so
     * nothing re-reads it. #messages is polite (a success confirmation may
     * wait for a pause), #errors is assertive.
     */
    @Test
    void flashRegionsAnnounceThemselves() {
        String src = jsp("tiles/messages.jsp");
        List<String> missing = new ArrayList<>();
        if (!src.contains("role=\"status\"") || !src.contains("aria-live=\"polite\"")) {
            missing.add("#messages needs role=\"status\" aria-live=\"polite\"");
        }
        if (!src.contains("role=\"alert\"")) {
            missing.add("#errors needs role=\"alert\"");
        }
        assertTrue(missing.isEmpty(), "tiles/messages.jsp: " + String.join("; ", missing));
    }

    /**
     * The success block used to render each message as a nested
     * {@code .alert-info} inside the {@code .alert-success} wrapper -- a box
     * in a box, in the wrong colour. It is a flat list now, like the errors
     * block beside it.
     */
    @Test
    void successMessagesAreNotBoxedInsideTheSuccessBox() {
        String src = jsp("tiles/messages.jsp");
        assertTrue(!src.contains("alert alert-info"),
                "tiles/messages.jsp still nests an .alert-info inside the .alert-success wrapper");
    }

    /**
     * The 10-second auto-dismiss must never reach an error banner: an error
     * the reader is still reading should not evaporate, and on GenericError
     * -- whose whole body used to be the flash region -- it emptied the page.
     */
    @Test
    void autoDismissIsScopedToSuccessMessages() {
        String src = jsp("tiles/messages.jsp");
        assertTrue(src.contains("#messages .alert"),
                "tiles/messages.jsp: auto-dismiss must select #messages .alert, not every .alert");
        // The original bug, stated positively: a bare $(".alert") also matched
        // #errors, so an error banner evaporated after ten seconds while it
        // was being read -- and on GenericError, whose whole body is this
        // region, it emptied the page.
        assertTrue(!withoutJspComments(src).contains("$(\".alert\")"),
                "tiles/messages.jsp still selects every .alert somewhere");
    }

    /** The inline {@code <style>} block belongs with the other alert rules. */
    @Test
    void messagesJspCarriesNoInlineStyleBlock() {
        assertTrue(!jsp("tiles/messages.jsp").contains("<style>"),
                "tiles/messages.jsp: move the alert list styling into roller.css");
        assertTrue(read(STYLES).contains(".alert ul"),
                "roller.css: the .alert ul rule from messages.jsp should live here");
    }

    /**
     * GenericError had no body at all: the "weblog creation is disabled" and
     * "one weblog per user" answers rendered a single auto-dismissing alert
     * on an otherwise blank page, which then emptied.
     */
    @Test
    void genericErrorHasABody() {
        String src = jsp("core/GenericError.jsp");
        assertTrue(src.contains("empty-state"),
                "core/GenericError.jsp needs an .empty-state body explaining what happened");
        assertTrue(src.contains("menu.rol"),
                "core/GenericError.jsp needs a way back to the weblog list");
    }

    /**
     * Both chrome layouts must put the page title above the flash region:
     * tabbedpage used to render messages first, so the two screens disagreed
     * about where a confirmation appears.
     */
    @Test
    void bothChromeLayoutsPutTheTitleBeforeTheMessages() {
        List<String> wrong = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = jsp(layout);
            int title = src.indexOf("roller-page-title");
            int messages = src.indexOf("${tile_messages}");
            if (title < 0 || messages < 0 || title > messages) {
                wrong.add(layout);
            }
        }
        assertTrue(wrong.isEmpty(), "title must precede the flash region in: " + wrong);
    }

    // ---------------------------------------------------------------- Task 14

    private static final List<String> TILES_LAYOUTS = List.of(
            "tiles/tiles-barepage.jsp",
            "tiles/tiles-errorpage.jsp",
            "tiles/tiles-installpage.jsp",
            "tiles/tiles-loginpage.jsp",
            "tiles/tiles-mainmenupage.jsp",
            "tiles/tiles-popuppage.jsp",
            "tiles/tiles-simplepage.jsp",
            "tiles/tiles-tabbedpage.jsp");

    /**
     * Without a document language a screen reader picks its own -- usually the
     * user's OS locale -- and reads English admin copy with, say, German
     * phonemes. Eight layouts, so one missing declaration hides in seven
     * correct ones.
     */
    @Test
    void everyLayoutDeclaresADocumentLanguage() {
        List<String> missing = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            if (!jsp(layout).contains("<html lang=")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <html lang>: " + missing);
    }

    /** Every layout must name the tab; popuppage used to ship none at all. */
    @Test
    void everyChromeLayoutSetsATitle() {
        List<String> missing = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            // barepage is deliberately chrome-free -- see its own comment.
            if (layout.endsWith("barepage.jsp")) {
                continue;
            }
            if (!jsp(layout).contains("<title>")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <title>: " + missing);
    }

    /**
     * The page title is the document's h1. It was an h2 on both chrome
     * layouts, so every admin screen started its heading outline at level 2
     * and any real h2 inside the content tile read as a sibling of the title.
     */
    @Test
    void thePageTitleIsTheDocumentHeading() {
        List<String> wrong = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            if (!jsp(layout).contains("<h1 class=\"roller-page-title\"")) {
                wrong.add(layout);
            }
        }
        assertTrue(wrong.isEmpty(), "page title is not an <h1> in: " + wrong);
    }

    /** "Skip to content" has nowhere to go without a main landmark. */
    @Test
    void contentColumnsAreMainLandmarks() {
        List<String> missing = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp",
                "tiles/tiles-simplepage.jsp", "tiles/tiles-loginpage.jsp")) {
            String src = jsp(layout);
            if (!src.contains("<main ") || !src.contains("</main>")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <main> landmark: " + missing);
    }

    /**
     * A header cell with no scope is ambiguous to a screen reader's table
     * mode. Scanned over the whole tree rather than the one screen this
     * started as: UserEdit was scoped while the seven authoring tables an
     * editor actually lives in -- Entries, Trash, Pages, Categories,
     * Templates, Members, Submissions -- were not, and a test naming a single
     * file could not say so.
     */
    @Test
    void everyDataTableScopesItsHeaders() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dir : List.of("admin", "core", "editor", "tiles")) {
            try (Stream<Path> files = Files.walk(JSPS.resolve(dir))) {
                files.filter(f -> f.toString().endsWith(".jsp")).forEach(f -> {
                    String body = withoutJspComments(read(f));
                    Matcher m = Pattern.compile("<th\\b([^>]*)>").matcher(body);
                    while (m.find()) {
                        if (!m.group(1).contains("scope=")) {
                            violations.add(f.getFileName() + ": <th" + m.group(1) + "> has no scope");
                        }
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(),
                "header cells with no scope:\n  " + String.join("\n  ", violations));
    }

    /**
     * A div styled as a modal is not a dialog to assistive tech unless it says
     * so: {@code role="dialog"} alone leaves the reader in the document behind
     * it, {@code aria-modal="true"} is what scopes them to the dialog, and
     * without {@code aria-labelledby} the dialog opens unnamed. The two media
     * pickers additionally had no {@code tabindex="-1"}, which is what makes
     * Esc close a Bootstrap modal -- so the only way out of the image chooser
     * was the mouse.
     */
    @Test
    void everyModalIsAnAccessibleDialog() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dir : List.of("admin", "core", "editor", "tiles")) {
            try (Stream<Path> files = Files.walk(JSPS.resolve(dir))) {
                files.filter(f -> f.toString().endsWith(".jsp")).forEach(f -> {
                    String body = withoutJspComments(read(f));
                    Matcher m = Pattern.compile("<div\\b([^>]*)>").matcher(body);
                    while (m.find()) {
                        String attrs = m.group(1);
                        // The modal ROOT only: "modal" as a standalone class
                        // token, never modal-dialog/-content/-header, which
                        // are its own children and carry no dialog semantics.
                        Matcher cls = Pattern.compile("\\bclass=\"([^\"]*)\"").matcher(attrs);
                        if (!cls.find() || !List.of(cls.group(1).trim().split("\\s+")).contains("modal")) {
                            continue;
                        }
                        String id = attrs.replaceAll("(?s).*\\bid=\"([^\"]+)\".*", "$1");
                        String who = f.getFileName() + " #" + id;
                        if (!attrs.contains("tabindex=\"-1\"")) {
                            violations.add(who + ": no tabindex=\"-1\", so Esc cannot close it");
                        }
                        if (!attrs.contains("aria-modal=\"true\"")) {
                            violations.add(who + ": no aria-modal=\"true\"");
                        }
                        Matcher labelled = Pattern.compile("aria-labelledby=\"([^\"]+)\"").matcher(attrs);
                        if (!labelled.find()) {
                            violations.add(who + ": no aria-labelledby, so the dialog opens unnamed");
                        } else if (!body.contains("id=\"" + labelled.group(1) + "\"")) {
                            violations.add(who + ": aria-labelledby=\"" + labelled.group(1)
                                    + "\" names no element in the file");
                        }
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(),
                "modals that are not dialogs:\n  " + String.join("\n  ", violations));
    }

    // ---------------------------------------------------------------- Task 16

    /**
     * .subtitle is the one-line orientation sentence under a page title, used
     * on ~15 admin screens -- and styled nowhere, so every one of them
     * rendered it as full-ink body prose competing with the title above.
     */
    @Test
    void theSubtitleRoleIsActuallyStyled() {
        assertTrue(read(STYLES).contains("p.subtitle"),
                "roller.css has no p.subtitle rule, so the class is inert on ~15 screens");
    }

    /**
     * One screen, one primary action -- the design system's button hierarchy.
     * These six forms each had their save sitting at .btn-secondary, level
     * with Cancel, so nothing on the page said which control finished the job.
     */
    @Test
    void eachFormScreenHasExactlyOnePrimaryAction() {
        List<String> wrong = new ArrayList<>();
        for (String screen : List.of("admin/GlobalConfig.jsp", "admin/UserEdit.jsp",
                "core/Profile.jsp", "core/CreateWeblog.jsp", "core/Setup.jsp",
                "core/CreateDatabase.jsp")) {
            int primaries = jsp(screen).split("btn-primary", -1).length - 1;
            if (primaries != 1) {
                wrong.add(screen + " has " + primaries);
            }
        }
        assertTrue(wrong.isEmpty(), "expected exactly one btn-primary per screen: " + wrong);
    }

    /**
     * Rebuild-index and regenerate-renditions run for minutes against the
     * weblog named in a &lt;select&gt; the operator may well have scrolled
     * past. Both confirm, and the confirmation names the weblog.
     */
    @Test
    void theLongMaintenanceOperationsConfirmAndNameTheirWeblog() {
        String src = jsp("admin/Maintenance.jsp");
        List<String> missing = new ArrayList<>();
        for (String key : List.of("maintenance.confirm.index",
                "maintenance.confirm.regenerateRenditions")) {
            if (!src.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "admin/Maintenance.jsp: unconfirmed long operations: " + missing);
    }

    /**
     * Both layouts declared a footer tile and neither rendered it, so the
     * login and simple pages were the only admin screens with no footer.
     */
    @Test
    void theLoginAndSimpleLayoutsRenderTheirFooter() {
        List<String> missing = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-loginpage.jsp", "tiles/tiles-simplepage.jsp")) {
            if (!jsp(layout).contains("${tile_footer}")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts declaring but never including tile_footer: " + missing);
    }

    /**
     * Six ids from a pre-Bootstrap centring scheme, none with a CSS rule for
     * years, still wrapping the content of three layouts.
     */
    @Test
    void noLayoutStillCarriesTheDeadCentringScaffolding() {
        List<String> found = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            String src = jsp(layout);
            if (src.contains("leftcontent") || src.contains("centercontent")
                    || src.contains("rightcontent") || src.contains("id=\"footer\"")) {
                found.add(layout);
            }
        }
        assertTrue(found.isEmpty(), "dead #leftcontent-era scaffolding still in: " + found);
    }

    /** Inline style attributes on admin screens belong in roller.css. */
    @Test
    void theSweptScreensCarryNoInlineStyleAttributes() {
        List<String> found = new ArrayList<>();
        for (String screen : List.of("tiles/tiles-installpage.jsp", "core/Setup.jsp",
                "core/CreateWeblog.jsp")) {
            if (jsp(screen).contains("style=\"")) {
                found.add(screen);
            }
        }
        assertTrue(found.isEmpty(), "inline style= attributes remain in: " + found);
    }

    /**
     * form-vertical is a Bootstrap 3 class that does nothing in Bootstrap 5.
     * .form-stacked is this repo's real labels-above conversion.
     */
    @Test
    void noAdminFormRidesTheDeadBootstrap3FormClass() {
        List<String> found = new ArrayList<>();
        for (String screen : List.of("admin/Maintenance.jsp", "admin/UserAdmin.jsp")) {
            if (jsp(screen).contains("form-vertical")) {
                found.add(screen);
            }
        }
        assertTrue(found.isEmpty(), "dead form-vertical still in: " + found);
    }

    // ----------------------------------------------------------------- Task 9

    /**
     * Hardcoded English in the admin chrome and the login form -- the one
     * screen every locale sees first. Scoped to the exact literals repaired
     * here rather than a general English detector, so the failure list is
     * always actionable: a placeholder, an aria-label and a visually-hidden
     * label are all invisible to a translator reading the bundle.
     */
    @Test
    void theAdminChromeAndLoginFormCarryNoHardcodedEnglish() {
        List<String> found = new ArrayList<>();
        record Literal(String file, String text) { }
        for (Literal l : List.of(
                new Literal("core/Login.jsp", "placeholder=\"Username\""),
                new Literal("core/Login.jsp", "placeholder=\"Password\""),
                new Literal("tiles/bannerStatus.jsp", "Toggle navigation"),
                new Literal("tiles/bannerInstallation.jsp", "Toggle navigation"),
                new Literal("tiles/messages.jsp", "aria-label=\"Close\""))) {
            if (jsp(l.file()).contains(l.text())) {
                found.add(l.file() + ": " + l.text());
            }
        }
        assertTrue(found.isEmpty(), "hardcoded English still in the JSPs:\n  "
                + String.join("\n  ", found));
    }

    // ---------------------------------------------------------------- Task 13

    /**
     * A {@code <label for="x">} with no {@code id="x"} on the page is not a
     * label -- it is styled text. Clicking it does not focus the control, and
     * a screen reader announces the input unlabelled. Two things make this
     * easy to get wrong here and worth pinning rather than reviewing:
     * Spring's {@code form:} tags emit {@code id="<path>"} by default (so the
     * target often exists already and only the {@code for=} is missing), and
     * a label with NO {@code for=} at all looks correct in a diff.
     *
     * <p>Scoped to the admin/core/tiles tree this package owns; the editor
     * tree has its own scan (EditorJspLabelBindingTest).
     *
     * <p>Targets containing EL are NOT skipped -- they are compared as literal
     * source text, and that is deliberate rather than a limitation. A target
     * like {@code globalConfig_$&#123;pd.nameWithUnderbars&#125;} resolves per
     * property at render time, so no literal id could ever be checked; what
     * CAN be checked is that the same expression appears in an {@code id=}
     * attribute in the same file, which is exactly what makes the pair line up
     * for every property the loop renders.
     */
    @Test
    void everyLabelForTargetsAnIdInTheSameFile() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dir : List.of("admin", "core", "tiles")) {
            try (Stream<Path> files = Files.walk(JSPS.resolve(dir))) {
                files.filter(f -> f.toString().endsWith(".jsp")).forEach(f -> {
                    String src = read(f);
                    Matcher m = Pattern.compile("<label[^>]*\\bfor=[\"']([^\"']+)[\"']").matcher(src);
                    while (m.find()) {
                        String target = m.group(1);
                        if (!src.contains("id=\"" + target + "\"") && !src.contains("id='" + target + "'")) {
                            violations.add(f.getFileName() + ": label for=\"" + target + "\" has no target");
                        }
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(), "dangling <label for=>:\n  " + String.join("\n  ", violations));
    }

    /**
     * The complement of the test above, and the one that actually found the
     * ~40 unbound controls on these five screens: a label that carries no
     * {@code for=} at all. Restricted to the five form screens repaired here
     * so the assertion stays a repair pin rather than a general style rule.
     */
    @Test
    void theRepairedFormScreensBindEveryLabel() {
        List<String> violations = new ArrayList<>();
        for (String screen : List.of("admin/GlobalConfig.jsp", "admin/UserEdit.jsp",
                "core/Profile.jsp", "core/CreateWeblog.jsp", "core/Setup.jsp")) {
            String src = jsp(screen);
            Matcher m = Pattern.compile("<label(?![^>]*\\bfor=)[^>]*>(?!\\s*</label>)").matcher(src);
            while (m.find()) {
                int line = (int) src.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                violations.add(screen + ":" + line + " label with no for=");
            }
        }
        assertTrue(violations.isEmpty(), "unbound labels:\n  " + String.join("\n  ", violations));
    }

    // ---------------------------------------------------------------- Task 17

    /**
     * Without autocomplete hints a password manager guesses, and its usual
     * guess on a change-password form is "this is a login" -- so it offers
     * the OLD password and then quietly stores the new one under the wrong
     * entry. Every password field on these four screens says which it is.
     */
    @Test
    void credentialFieldsDeclareTheirAutocompleteRole() {
        List<String> missing = new ArrayList<>();
        record Field(String file, String id, String role) { }
        for (Field f : List.of(
                new Field("core/Login.jsp", "j_username", "username"),
                new Field("core/Login.jsp", "j_password", "current-password"),
                new Field("core/ResetPassword.jsp", "passwordText", "new-password"),
                new Field("core/ResetPassword.jsp", "passwordConfirm", "new-password"),
                new Field("core/Profile.jsp", "passwordText", "new-password"),
                new Field("core/Profile.jsp", "passwordConfirm", "new-password"),
                new Field("admin/UserEdit.jsp", "bean_password", "new-password"))) {
            // Anchored on the element carrying the id: a file-wide contains()
            // let ONE autocomplete="new-password" anywhere in Profile.jsp
            // satisfy both of its password fields, so the confirm field could
            // have had none and the scan would still have passed.
            String element = elementCarrying(jsp(f.file()), f.id());
            if (element.isEmpty()) {
                missing.add(f.file() + " has no element with id=\"" + f.id() + "\"");
            } else if (!element.contains("autocomplete=\"" + f.role() + "\"")) {
                missing.add(f.file() + " #" + f.id() + " -> " + f.role());
            }
        }
        assertTrue(missing.isEmpty(), "credential fields with no autocomplete role: " + missing);
    }

    /**
     * Two screens ran {@code document.forms[0].elements[0].focus()}. In a form
     * whose first element is the hidden CSRF input -- both of these -- that
     * focuses the hidden input, i.e. nothing at all. The attribute does what
     * the script was trying to do, and does it without a script.
     */
    @Test
    void noScreenFocusesTheHiddenCsrfInput() {
        List<String> found = new ArrayList<>();
        for (String screen : List.of("admin/UserEdit.jsp", "core/CreateWeblog.jsp")) {
            String src = jsp(screen);
            if (src.contains("elements[0].focus()")) {
                found.add(screen + " still focuses elements[0]");
            }
            // Comments stripped: the first version of this arm was satisfied
            // by the comment that explains the fix, which would have passed on
            // a screen where the attribute had been dropped again.
            if (!withoutJspComments(src).contains("autofocus")) {
                found.add(screen + " has no autofocus replacement");
            }
        }
        assertTrue(found.isEmpty(), String.join("; ", found));
    }

    // ---------------------------------------------------------------- Task 19

    /**
     * Chrome asks for /favicon.ico on every navigation whether or not the page
     * declares one, and the three bundled themes declare none -- so Roller
     * shipping no .ico meant a 404 on every rendered weblog page, which
     * BrowserHealth had to excuse blanket-wide to let the suite run at all.
     * Shipping the file is what retires that exemption.
     */
    @Test
    void aRealFaviconIcoIsShipped() throws IOException {
        Path ico = Path.of("src/main/webapp/favicon.ico");
        assertTrue(Files.isRegularFile(ico), "no /favicon.ico at " + ico.toAbsolutePath());
        byte[] head = Files.readAllBytes(ico);
        // ICONDIR: reserved 0x0000, type 0x0001 (icon), then the image count.
        assertTrue(head.length > 6 && head[0] == 0 && head[1] == 0 && head[2] == 1 && head[3] == 0,
                "favicon.ico is not an ICO file");
        assertTrue(head[4] > 0, "favicon.ico declares no images");
    }

    /**
     * An admin with four weblogs open had four tabs reading "Roller: Entries".
     * Only the layouts whose model actually carries actionWeblog append it --
     * these same layouts serve un-scoped screens (Global Config, User Admin).
     */
    @Test
    void weblogScopedLayoutsNameTheirWeblogInTheTitle() {
        List<String> missing = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = jsp(layout);
            int title = src.indexOf("<title>");
            int end = src.indexOf("</title>", title);
            if (title < 0 || !src.substring(title, end).contains("actionWeblog.handle")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts whose <title> omits the weblog handle: " + missing);
    }

    // ------------------------------------------------- Review fix round 1

    /**
     * An inline {@code onclick="return confirm('${fn:escapeXml(msg)}')"} FAILS
     * OPEN, which is why none may remain. fn:escapeXml is an HTML escape and
     * the attribute body is a JS-string position: the HTML parser turns
     * {@code &#039;} back into a literal apostrophe BEFORE the JS is compiled,
     * so one apostrophe -- in a translated value, or in an address like
     * o'brien@example.com -- terminates the string, the handler fails to
     * compile, and the control acts with NO confirmation at all.
     *
     * <p>The replacement puts the message in {@code data-confirm}, where
     * fn:escapeXml IS the correct escape because there is no second parser,
     * and one delegated handler in roller.js reads it back through
     * {@code dataset.confirm}.
     *
     * <p>The editor tree has a second shape of the same failure: a bare
     * {@code <spring:message>} dropped straight into a single-quoted
     * {@code confirm('...')} JS string, with no HTML parser in between to
     * blame -- the message text (or a value concatenated alongside it, as on
     * Templates.jsp/Pages.jsp) lands in the page as raw JS source, so a
     * translator's apostrophe ("can't be undone") or an author's own typed
     * value terminates the string early. {@code javaScriptEscape="true"} is
     * the correct fix here (there being no HTML layer for fn:escapeXml to
     * fight with), and the named-function + {@code javaScriptEscape} idiom
     * (e.g. {@code confirmTemplateDelete(...)} reading its arguments off
     * {@code data-*} attributes, with the message tag itself escaped) is
     * ALLOWED -- only a message tag missing the escape, sitting inside a
     * single-quoted confirm string, is banned. Double-quoted confirm strings
     * (the {@code confirm("<spring:message .../>")} idiom used elsewhere in
     * the editor tree) are out of scope: an apostrophe is harmless inside a
     * double-quoted JS string, so that shape does not share this failure
     * mode.
     */
    @Test
    void noAdminScreenBuildsAConfirmPromptInsideAnInlineHandler() throws IOException {
        Pattern inlineConfirmEl = Pattern.compile(
                "on(?:click|submit)\\s*=\\s*\"[^\"]*confirm\\s*\\([^\"]*\\$\\{");
        Pattern unescapedMessageInSingleQuotedConfirm = Pattern.compile(
                "confirm\\(\\s*'[^']*<spring:message\\b((?:(?!/>).)*?)/>");
        List<String> found = new ArrayList<>();
        for (String dir : List.of("admin", "core", "tiles", "editor")) {
            try (Stream<Path> files = Files.walk(JSPS.resolve(dir))) {
                files.filter(f -> f.toString().endsWith(".jsp")).forEach(f -> {
                    String src = read(f);
                    Matcher elMatch = inlineConfirmEl.matcher(src);
                    while (elMatch.find()) {
                        found.add(f.getFileName() + ": " + elMatch.group());
                    }
                    Matcher msgMatch = unescapedMessageInSingleQuotedConfirm.matcher(src);
                    while (msgMatch.find()) {
                        if (!msgMatch.group(1).contains("javaScriptEscape=\"true\"")) {
                            found.add(f.getFileName() + ": " + msgMatch.group());
                        }
                    }
                });
            }
        }
        assertTrue(found.isEmpty(),
                "confirm() built from unescaped EL or an un-escaped <spring:message> in a "
                        + "single-quoted JS string -- fails open on an apostrophe:\n  "
                        + String.join("\n  ", found));
    }

    /**
     * The three repaired prompts, the handler that reads them back, and -- the
     * part that matters -- WHERE each attribute sits.
     *
     * <p>All three belong on their CONTROL, not on the enclosing form. A
     * form-level {@code data-confirm} is answered twice: roller.js's click
     * handler walks up from the clicked button and finds it, the operator
     * confirms, the native submit proceeds, and the submit handler finds the
     * same attribute and asks again. Two dialogs for one click. That shipped
     * for one round on UserEdit's send-password-link form, so this test names
     * the owning element rather than trusting the file to contain the string.
     */
    @Test
    void theRepairedPromptsSitOnTheirControlNotTheirForm() {
        List<String> wrong = new ArrayList<>();
        record Screen(String file, int expected) { }
        for (Screen screen : List.of(new Screen("admin/Maintenance.jsp", 2),
                new Screen("admin/UserEdit.jsp", 1))) {
            List<String> owners = ownersOf(withoutJspComments(jsp(screen.file())), "data-confirm");
            if (owners.size() != screen.expected()) {
                wrong.add(screen.file() + " has " + owners.size()
                        + " data-confirm attributes, expected " + screen.expected());
            }
            for (String owner : owners) {
                if (!"button".equals(owner)) {
                    wrong.add(screen.file() + " puts data-confirm on <" + owner
                            + ">; it belongs on the button (a form-level one prompts twice)");
                }
            }
        }
        String js = read(Path.of("src/main/webapp/theme/scripts/roller.js"));
        if (!js.contains("data-confirm")) {
            wrong.add("theme/scripts/roller.js has no data-confirm handler");
        }
        // The general defence behind the convention: the click handler's walk
        // must stop at the form, or a form-level attribute double-prompts
        // again the moment someone adds one.
        if (!js.contains("node.tagName !== \"FORM\"")) {
            wrong.add("roller.js: the click handler's ancestor walk must stop at the form");
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
    }

    /**
     * The data-confirm values are single-escaped: the message ARGUMENTS go
     * through fn:escapeXml and the composed message is written into the
     * attribute as-is, so the browser hands dataset.confirm the exact literal
     * text. That is only safe while the bundle values themselves carry no raw
     * double quote -- which would close the attribute. An apostrophe in a
     * value is fine (the attribute is double-quoted) and an apostrophe in an
     * ARGUMENT is fine (fn:escapeXml turns it into &#039;, which the parser
     * decodes back). This is the assertion standing in for the o'brien case:
     * the escaping that handles it is fn:escapeXml on the argument, and what
     * would break it is a quote in the template.
     */
    @Test
    void theConfirmPromptValuesCannotCloseTheirAttribute() throws IOException {
        Properties bundle = new Properties();
        try (var in = Files.newInputStream(
                Path.of("src/main/resources/ApplicationResources.properties"))) {
            bundle.load(in);
        }
        List<String> bad = new ArrayList<>();
        for (String key : List.of("maintenance.confirm.index",
                "maintenance.confirm.regenerateRenditions",
                "userAdmin.sendPasswordLink.confirm")) {
            String value = bundle.getProperty(key);
            if (value == null) {
                bad.add(key + " is missing");
            } else if (value.indexOf('"') >= 0) {
                bad.add(key + " contains a double quote: " + value);
            }
        }
        assertTrue(bad.isEmpty(), "data-confirm message values: " + String.join("; ", bad));
    }
}
