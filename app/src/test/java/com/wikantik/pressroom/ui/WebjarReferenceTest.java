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
package com.wikantik.pressroom.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that every /webjars/ URL hardcoded in a JSP, tag file, Velocity
 * template or CSS file actually resolves to a resource inside a webjar on
 * the classpath.
 *
 * Webjar versions appear in two places that drift independently: the
 * dependency versions in app/pom.xml, and the URLs written by hand into
 * templates such as WEB-INF/jsps/tiles/head.jsp (or, since roller-tokens.css
 * added self-hosted @font-face rules, into CSS {@code url()}s). When a
 * dependency is bumped and the reference is not, the asset silently 404s at
 * runtime. Because head.jsp is included by every admin page, such a mismatch
 * takes out the editor, date pickers and client-side validation site-wide --
 * with no compile error and no other failing test.
 *
 * <p>CSS files address webjars with a path relative to their own location
 * rather than the {@code <c:url>}-rewritten absolute path JSPs use --
 * roller-tokens.css's @font-face {@code src} is {@code
 * url("../../webjars/ibm__plex-sans/...")}. {@link #WEBJAR_URL} does not
 * need to special-case that: it matches the literal substring
 * {@code "/webjars/"} wherever it falls in the file and captures only what
 * follows, so any leading {@code ../} segments are already discarded before
 * the match starts -- the capture group is the same
 * webjar-artifact/version/path form regardless of whether the reference was
 * absolute or relative.
 */
public class WebjarReferenceTest {

    /**
     * Captures everything after "/webjars/" up to the closing quote or
     * paren, e.g. "clipboard.js/2.0.11/clipboard.min.js". Trailing path
     * segments may contain slashes (easymde serves from a dist/
     * subdirectory). ')' is excluded too so an unquoted CSS {@code url(...)}
     * does not swallow its own closing paren into the resource path.
     */
    private static final Pattern WEBJAR_URL = Pattern.compile("/webjars/([^'\"\\s>)]+)");

    private static final Path WEBAPP = Paths.get("src/main/webapp");

    @Test
    public void everyWebjarReferenceResolvesOnTheClasspath() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path template : templateFiles()) {
            String content = Files.readString(template, StandardCharsets.UTF_8);
            for (String reference : webjarReferences(content)) {
                checked++;
                if (!resolvesOnClasspath(reference)) {
                    failures.add(WEBAPP.relativize(template) + " -> /webjars/" + reference);
                }
            }
        }

        assertTrue(checked > 0,
                "Found no /webjars/ references to check under " + WEBAPP.toAbsolutePath()
                        + " -- the test is not looking where it thinks it is.");

        assertTrue(failures.isEmpty(),
                "Webjar URL(s) do not match any webjar on the classpath. The dependency "
                        + "version in app/pom.xml was most likely bumped without updating the "
                        + "template, which 404s the asset at runtime:\n  "
                        + String.join("\n  ", failures));
    }

    /**
     * Guards the guard: if head.jsp stops being scanned (renamed, moved, or the
     * scan silently narrows), the test above would vacuously pass.
     */
    @Test
    public void headJspIsScannedAndReferencesWebjars() throws IOException {
        Path headJsp = WEBAPP.resolve("WEB-INF/jsps/tiles/head.jsp");
        assertTrue(Files.exists(headJsp), "Expected to find " + headJsp.toAbsolutePath());
        assertTrue(templateFiles().contains(headJsp),
                headJsp + " is not picked up by the template scan");
        assertFalse(templateFiles().isEmpty(), "Template scan returned nothing");

        String content = Files.readString(headJsp, StandardCharsets.UTF_8);
        assertTrue(WEBJAR_URL.matcher(content).find(),
                headJsp + " no longer references any webjar; if the assets moved, "
                        + "update this test to follow them.");
    }

    private List<Path> templateFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(WEBAPP)) {
            return paths.filter(Files::isRegularFile)
                    .filter(WebjarReferenceTest::isTemplate)
                    .toList();
        }
    }

    private static boolean isTemplate(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsp") || name.endsWith(".tag") || name.endsWith(".vm")
                || name.endsWith(".css");
    }

    /**
     * Every {@code /webjars/...} reference found in {@code content}, e.g.
     * {@code "ibm__plex-sans/1.1.0/fonts/.../IBMPlexSans-Text.woff2"}.
     */
    private static List<String> webjarReferences(String content) {
        Matcher matcher = WEBJAR_URL.matcher(content);
        List<String> references = new ArrayList<>();
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
        return references;
    }

    private boolean resolvesOnClasspath(String webjarReference) {
        return getClass().getClassLoader()
                .getResource("META-INF/resources/webjars/" + webjarReference) != null;
    }

    /**
     * Regression proof for the CSS scan added alongside roller-tokens.css's
     * self-hosted @font-face rules: a relative {@code url()} reference with a
     * version that does not exist on the classpath must be caught, the same
     * way an absolute JSP reference already is. Before {@link #isTemplate}
     * covered {@code .css}, a mutation exactly like this one in
     * roller-tokens.css passed silently.
     */
    @Test
    public void aBrokenVersionInACssUrlIsCaught() {
        String css = "@font-face {\n"
                + "  font-family: \"IBM Plex Sans\";\n"
                + "  src: url(\"../../webjars/ibm__plex-sans/9.9.9-does-not-exist/fonts/"
                + "complete/woff2/IBMPlexSans-Text.woff2\") format(\"woff2\");\n"
                + "}\n";

        List<String> references = webjarReferences(css);
        assertFalse(references.isEmpty(), "Test fixture itself has no /webjars/ reference to check");

        List<String> failures = references.stream().filter(ref -> !resolvesOnClasspath(ref)).toList();
        assertFalse(failures.isEmpty(),
                "A CSS url() with a nonexistent webjar version should have failed to resolve, "
                        + "but resolvesOnClasspath() reported it as present: " + references);

        // control: the real, correct reference from roller-tokens.css must still resolve,
        // proving the failure above is about the broken version and not the matching/lookup itself.
        String goodReference = "ibm__plex-sans/1.1.0/fonts/complete/woff2/IBMPlexSans-Text.woff2";
        assertTrue(resolvesOnClasspath(goodReference),
                "Sanity control reference no longer resolves -- fixture is out of date");
    }
}
