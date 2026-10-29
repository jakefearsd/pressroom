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
package com.wikantik.pressroom.ui.rendering.servlets;

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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped theme page must send a Content-Security-Policy.
 *
 * <p>The travel and portfolio themes already have rendering tests that pin the
 * exact policy string. Those tests say nothing about the other themes, and
 * that gap was real: the retired {@code basic} theme's permalink and search pages rendered with
 * no CSP at all while its front page had one, so a reader following a link to
 * a post silently lost the protection they had a moment earlier. Nothing
 * failed, because nothing looked.
 *
 * <p>This is a source scan rather than a render, so it covers themes no
 * rendering test exercises and pages no fixture visits.
 */
class ThemeCspCoverageTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");

    private static final String CSP = "<meta http-equiv=\"Content-Security-Policy\"";

    /**
     * A page whose head is assembled from a shared template cannot carry the
     * literal itself; it delegates. Themes doing that (frontpage) keep
     * the policy in the included template.
     *
     * <p>The delegation only counts INSIDE the head. Nearly every page
     * includes something somewhere -- a sidebar, a footer -- and treating any
     * include as evidence of a shared head is what made the first version of
     * this test pass against the very gap it was written to catch.
     */
    private static final String INCLUDES_A_SHARED_HEAD = "#includeTemplate(";

    @Test
    public void everyThemePageWithAHeadSendsAContentSecurityPolicy() throws IOException {
        assertTrue(Files.isDirectory(THEMES), "Expected " + THEMES.toAbsolutePath());

        List<String> unprotected = new ArrayList<>();
        try (Stream<Path> themeDirs = Files.list(THEMES)) {
            for (Path theme : themeDirs.filter(Files::isDirectory).toList()) {
                unprotected.addAll(unprotectedPagesIn(theme));
            }
        }

        assertTrue(unprotected.isEmpty(),
                "These theme pages open a <head> but never send a Content-Security-Policy, "
                        + "so they render less safely than the rest of their own theme:\n  "
                        + String.join("\n  ", unprotected));
    }

    /**
     * Pages in one theme that open a {@code <head>}, carry no policy of their
     * own, and do not delegate to a shared head that has one.
     */
    private List<String> unprotectedPagesIn(Path theme) throws IOException {
        List<Path> templates;
        try (Stream<Path> files = Files.list(theme)) {
            templates = files.filter(f -> f.toString().endsWith(".vm")).toList();
        }

        boolean themeHasAPolicySomewhere = false;
        for (Path template : templates) {
            if (read(template).contains(CSP)) {
                themeHasAPolicySomewhere = true;
                break;
            }
        }

        List<String> unprotected = new ArrayList<>();
        for (Path template : templates) {
            String source = read(template);
            if (!source.contains("<head>") || source.contains(CSP)) {
                continue;
            }
            if (headOf(source).contains(INCLUDES_A_SHARED_HEAD) && themeHasAPolicySomewhere) {
                continue;
            }
            unprotected.add(theme.getFileName() + "/" + template.getFileName());
        }
        return unprotected;
    }

    /** Everything between the opening and closing head tags, or "" if malformed. */
    private static String headOf(String source) {
        int start = source.indexOf("<head>");
        int end = source.indexOf("</head>", start);
        return start < 0 || end < 0 ? "" : source.substring(start, end);
    }

    /**
     * The scan is only meaningful if it is actually reading templates; an empty
     * or moved themes directory would otherwise pass silently.
     */
    @Test
    public void theScanSeesTheShippedThemes() throws IOException {
        List<String> withHeads = new ArrayList<>();
        try (Stream<Path> files = Files.walk(THEMES)) {
            for (Path template : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vm")).toList()) {
                if (read(template).contains("<head>")) {
                    withHeads.add(template.toString());
                }
            }
        }
        assertFalse(withHeads.isEmpty(), "No theme templates with a <head> were found at "
                + THEMES.toAbsolutePath() + "; the scan above would pass vacuously");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Analytics depend on the policy allowing same-origin scripts and beacons.
     *
     * <p>The stack serves Umami's tracker and collect endpoint from the blog's
     * own origin under {@code /analytics/*} precisely so that
     * {@code script-src 'self'} and {@code connect-src 'self'} cover them
     * (see deploy/caddy/Caddyfile). Tightening either directive would not
     * break a test or log an error -- it would just stop the browser running
     * the tracker, and the dashboard would sit empty while everything looked
     * fine. So it is worth failing loudly here instead.
     */
    @Test
    public void everyPolicyStillAllowsSameOriginScriptsAndBeacons() throws IOException {
        List<String> tooStrict = new ArrayList<>();
        try (Stream<Path> files = Files.walk(THEMES)) {
            for (Path template : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vm")).toList()) {
                String source = read(template);
                int at = source.indexOf(CSP);
                if (at < 0) {
                    continue;
                }
                String policy = source.substring(at, source.indexOf('>', at));
                if (!policy.contains("script-src 'self'")) {
                    tooStrict.add(template + " (script-src)");
                }
                if (!policy.contains("connect-src 'self'")) {
                    tooStrict.add(template + " (connect-src)");
                }
            }
        }

        assertTrue(tooStrict.isEmpty(),
                "These policies no longer allow the same-origin tracker the analytics "
                        + "service depends on, which fails silently in the browser:\n  "
                        + String.join("\n  ", tooStrict));
    }

    /**
     * A theme that asks for a webfont must ship it and must allow it.
     *
     * <p>Both halves were broken in the retired {@code gaurav} theme and neither was visible.
     * The policies here start from {@code default-src 'none'} and none of them
     * named {@code font-src}, so every webfont the theme asked for was refused
     * by the browser -- its icons simply did not draw. Behind that, its
     * vendored Bootstrap declared a Glyphicons face pointing at a {@code fonts/}
     * directory the theme has never contained, so those urls would have 404d
     * even once unblocked.
     *
     * <p>Neither shows up in a rendering test, which compares markup, and
     * neither showed up in the browser suite either until {@code BrowserHealth}
     * learned to report requests that end without a response. A source scan
     * catches both before that ever runs.
     */
    @Test
    public void everyFontAThemeAsksForIsShippedAndAllowedByItsPolicy() throws IOException {
        List<String> problems = new ArrayList<>();

        try (Stream<Path> themeDirs = Files.list(THEMES)) {
            for (Path theme : themeDirs.filter(Files::isDirectory).toList()) {
                List<String> missing = new ArrayList<>();
                boolean wantsFonts = false;

                try (Stream<Path> files = Files.walk(theme)) {
                    for (Path css : files.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".css")).toList()) {
                        for (String reference : fontReferences(read(css))) {
                            wantsFonts = true;
                            if (!fontIsShipped(css, reference)) {
                                missing.add(reference);
                            }
                        }
                    }
                }

                for (String gone : missing) {
                    problems.add(theme.getFileName() + " asks for a font it does not ship: " + gone);
                }
                if (wantsFonts && !policyOf(theme).contains("font-src")) {
                    problems.add(theme.getFileName() + " uses webfonts but its policy has no "
                            + "font-src, so the browser refuses every one of them");
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "Themes whose webfonts cannot load. Neither symptom is visible in the "
                        + "rendered markup -- the icons just do not draw:\n  "
                        + String.join("\n  ", problems));
    }

    /**
     * Whether a font a theme's CSS references actually ships somewhere.
     *
     * <p>A theme font comes from one of two places. Most take the {@code
     * gaurav} (retired) shape this test was written for: a file vendored inside the
     * theme's own directory, addressed by a path relative to the CSS file --
     * checked by resolving that path on disk, same as the original check.
     * {@code journal} takes the {@code pressroom-tokens.css} shape instead: IBM
     * Plex self-hosted from the {@code org.webjars.npm:ibm__plex-*}
     * dependencies (app/pom.xml), served at runtime from the classpath
     * (Spring Boot's static-resource merge of {@code classpath:/META-INF/
     * resources/}) rather than unpacked anywhere under {@code src/main/
     * webapp/} -- there is no on-disk path for a {@code /webjars/} reference
     * to resolve against, by design, the same reason {@code
     * WebjarReferenceTest} checks those against the classpath instead of the
     * filesystem. A reference containing {@code /webjars/} is checked the
     * same way that test checks it; anything else keeps the original
     * disk-relative check.
     */
    private static boolean fontIsShipped(Path css, String reference) {
        int at = reference.indexOf("/webjars/");
        if (at >= 0) {
            String webjarResource = reference.substring(at + "/webjars/".length());
            return ThemeCspCoverageTest.class.getClassLoader()
                    .getResource("META-INF/resources/webjars/" + webjarResource) != null;
        }
        return Files.exists(css.getParent().resolve(reference).normalize());
    }

    /**
     * Font urls referenced by a stylesheet, stripped of the cache-busting query
     * and the {@code #iefix} fragment that would otherwise never resolve on disk.
     */
    private static List<String> fontReferences(String css) {
        List<String> references = new ArrayList<>();
        Matcher matcher = FONT_URL.matcher(css);
        while (matcher.find()) {
            references.add(matcher.group(1).replaceAll("[?#].*$", ""));
        }
        return references;
    }

    /** The policy governing a theme, wherever in its templates it is declared. */
    private static String policyOf(Path theme) throws IOException {
        try (Stream<Path> files = Files.walk(theme)) {
            for (Path template : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vm")).toList()) {
                String source = read(template);
                int at = source.indexOf(CSP);
                if (at >= 0) {
                    return source.substring(at, source.indexOf('>', at));
                }
            }
        }
        return "";
    }

    private static final Pattern FONT_URL =
            Pattern.compile("url\\([\"']?([^)\"']+\\.(?:woff2?|ttf|eot|otf))[^)]*\\)");
}
