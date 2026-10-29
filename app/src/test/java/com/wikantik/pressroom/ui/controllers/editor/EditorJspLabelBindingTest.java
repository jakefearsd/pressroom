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
package com.wikantik.pressroom.ui.controllers.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-scan tests pinning label/control binding in the editor JSP tree.
 *
 * <p>A {@code <label>} that neither carries a {@code for=} pointing at a real
 * id nor wraps its own control is not a label to a screen reader or to a
 * pointer -- it is styled text. The sweep of 2026-08-20 found ~80 such labels
 * across the admin JSPs, of which the editor tree owns the bulk; these scans
 * pin the repair for the editor tree specifically (a sibling package extends
 * the coverage to {@code jsps/admin}, {@code jsps/core} and {@code jsps/tiles}).
 *
 * <p>Both scans collect every violation before asserting once, following
 * {@link EditorJspScriptBindingTest}: an {@code assertTrue} inside the loop
 * stops at the first offender, so a single run can never show the whole set.
 */
class EditorJspLabelBindingTest {

    private static final Path EDITOR_JSP_DIR =
            Path.of("src/main/webapp/WEB-INF/jsps/editor");

    /** {@code <label ...>body</label>}, body spanning lines. */
    private static final Pattern LABEL =
            Pattern.compile("<label\\b([^>]*)>(.*?)</label>", Pattern.DOTALL);

    private static final Pattern FOR_ATTR = Pattern.compile("\\bfor=\"([^\"]*)\"");

    private static List<Path> editorJsps() throws IOException {
        try (Stream<Path> files = Files.list(EDITOR_JSP_DIR)) {
            return files.filter(p -> p.toString().endsWith(".jsp")).sorted().toList();
        }
    }

    /** Every literal {@code <label for="X">} must have a matching {@code id="X"}
     *  in the same JSP. A dangling {@code for} is worse than none: it silently
     *  claims a binding that does not exist. */
    @Test
    void everyLabelForTargetsAnIdInTheSameFile() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            Matcher m = Pattern.compile("<label[^>]*\\bfor=\"([^\"$]+)\"").matcher(src);
            while (m.find()) {
                if (!src.contains("id=\"" + m.group(1) + "\"")) {
                    violations.add(jsp.getFileName() + ": label for=\"" + m.group(1)
                            + "\" has no target");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** A label must bind to something: either {@code for=} or a control nested
     *  inside it. Anything else is a caption pretending to be a label, and the
     *  fix is either the missing {@code for}/{@code id} pair or a
     *  {@code <span class="col-form-label">} when the row is read-only content
     *  rather than a form field. */
    @Test
    void everyLabelBindsToAControl() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            Matcher m = LABEL.matcher(src);
            while (m.find()) {
                String attrs = m.group(1);
                String body = m.group(2);
                Matcher f = FOR_ATTR.matcher(attrs);
                if (f.find() && !f.group(1).isBlank()) {
                    continue;
                }
                if (body.contains("<input") || body.contains("<select")
                        || body.contains("<textarea") || body.contains("<form:")) {
                    continue;
                }
                violations.add(jsp.getFileName() + ": label \""
                        + body.replaceAll("\\s+", " ").trim()
                        + "\" binds to no control (no for=, no nested control)");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * A {@code for=} target that exists in only ONE branch of a
     * {@code <c:choose>} is a dangling label for every render that takes the
     * other branch -- and the file-wide scan above cannot see it, because the
     * id IS present in the source.
     *
     * <p>Found live: TemplateEdit.jsp's name and description labels sat
     * outside a {@code required}/{@code otherwise} choose whose readonly
     * branch carried the id and whose editable branch did not, so the label
     * bound to nothing on exactly the templates an author can actually edit.
     *
     * <p>The rule checked is narrow on purpose: within one {@code c:choose},
     * if two branches both declare the same control, they must agree on its
     * {@code id=}. That is precisely the shape above, and it says nothing
     * about branches that legitimately render different controls.
     *
     * <p>"The same control" is its {@code name=} -- except for a radio or a
     * checkbox, where it is {@code name=} plus {@code value=}. A radio GROUP
     * is by definition several controls sharing one name and differing in id,
     * so keying on name alone reports every grouped radio inside a
     * {@code c:choose} as a violation of a rule it cannot obey.
     * {@code ThemeEdit.jsp}'s shared/custom pair is the live example.
     * {@code type="hidden"} is skipped outright: nothing labels a hidden
     * input, so it has no {@code for=} that could dangle -- and a hidden input
     * standing in for a control the other branch renders (again ThemeEdit,
     * which ships {@code themeType=shared} when there is nothing to choose
     * between) is the correct shape, not a defect.
     *
     * <p>Two blind spots this narrowing accepted, recorded rather than fixed
     * because closing either would reintroduce the original false positive:
     * <ul>
     * <li>Keying a radio/checkbox on {@code name=} plus {@code value=} means
     * two checkboxes that are really "the same control" across branches --
     * meant to share one {@code id=} -- but whose {@code value=} diverges (a
     * typo, or one branch dropping the attribute so it defaults to
     * {@code "on"}) are read as two unrelated controls instead of one with a
     * dangling label. The check cannot tell "different value, same control"
     * from "different value, different control".
     * <li>Skipping every {@code type="hidden"} control means a {@code for=}
     * that happens to target a hidden input's id -- unusual, but nothing
     * stops a JSP from doing it -- is invisible to this scan even if that id
     * differs between branches. Hidden inputs are not meant to be labelled,
     * which is why this is accepted rather than closed.
     * </ul>
     */
    @Test
    void aControlDeclaredInSeveralBranchesKeepsTheSameIdInEachOfThem() throws Exception {
        Pattern choose = Pattern.compile("<c:choose>(.*?)</c:choose>", Pattern.DOTALL);
        Pattern branch = Pattern.compile(
                "<c:(?:when[^>]*|otherwise)>(.*?)</c:(?:when|otherwise)>", Pattern.DOTALL);
        Pattern control = Pattern.compile("<(?:input|select|textarea)\\b([^>]*)>");

        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            Matcher chooses = choose.matcher(Files.readString(jsp));
            while (chooses.find()) {
                Map<String, Set<String>> idsByName = new LinkedHashMap<>();
                Matcher branches = branch.matcher(chooses.group(1));
                while (branches.find()) {
                    Matcher controls = control.matcher(branches.group(1));
                    while (controls.find()) {
                        String attrs = controls.group(1);
                        Matcher name = Pattern.compile("\\bname=\"([^\"]+)\"").matcher(attrs);
                        if (!name.find()) {
                            continue;
                        }
                        Matcher type = Pattern.compile("\\btype=\"([^\"]+)\"").matcher(attrs);
                        String controlType = type.find() ? type.group(1) : "";
                        if ("hidden".equals(controlType)) {
                            continue;
                        }
                        String key = name.group(1);
                        if ("radio".equals(controlType) || "checkbox".equals(controlType)) {
                            Matcher value = Pattern.compile("\\bvalue=\"([^\"]+)\"").matcher(attrs);
                            key += "[" + (value.find() ? value.group(1) : "") + "]";
                        }
                        Matcher id = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(attrs);
                        idsByName.computeIfAbsent(key, k -> new LinkedHashSet<>())
                                .add(id.find() ? id.group(1) : "(no id)");
                    }
                }
                idsByName.forEach((name, ids) -> {
                    if (ids.size() > 1) {
                        violations.add(jsp.getFileName() + ": the branches of one c:choose give "
                                + "name=\"" + name + "\" different ids " + ids
                                + " -- a label bound to one of them dangles in the other branch");
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
