package com.wikantik.pressroom.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JavaScript build that produces the editor bundle. The bundle is
 * built by Maven (frontend-maven-plugin + esbuild) and is not committed, so
 * the only thing that can rot is this wiring -- a plugin dropped from the
 * pom builds a WAR whose editor page loads a 404 and says nothing.
 */
class EditorBundlePomTest {

    private static final Path APP_POM = Path.of("pom.xml");
    private static final Path PARENT_POM = Path.of("..", "pom.xml");
    private static final Path PACKAGE_JSON = Path.of("frontend", "package.json");
    private static final Path HEAD_JSP = Path.of("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");
    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");

    @Test
    void theFrontendPluginIsPinnedInTheParentAndExecutedInApp() throws IOException {
        String parent = Files.readString(PARENT_POM);
        assertTrue(parent.contains("<artifactId>frontend-maven-plugin</artifactId>"),
                "parent pluginManagement must declare frontend-maven-plugin");
        Matcher node = Pattern.compile("<nodeVersion>v(\\d+)\\.(\\d+)\\.(\\d+)</nodeVersion>").matcher(parent);
        assertTrue(node.find(), "nodeVersion must be pinned to an exact vX.Y.Z");
        assertEquals("24", node.group(1), "Node 24 LTS");

        String app = Files.readString(APP_POM);
        int install = app.indexOf("<goal>install-node-and-npm</goal>");
        int ci = app.indexOf("<id>npm-ci</id>");
        int build = app.indexOf("<id>npm-build</id>");
        assertTrue(install > 0 && ci > install && build > ci,
                "install-node-and-npm, npm ci, npm run build must be declared in that order");
        assertTrue(app.contains("<workingDirectory>${project.basedir}/frontend</workingDirectory>"));
        assertTrue(app.contains("<id>npm-test</id>"), "the frontend node:test suite must run in the test phase");
    }

    /**
     * The outfile must be the webapp root, not a classpath location, and the
     * difference is the whole difference between a working editor and a 404
     * nobody notices until someone opens the page.
     *
     * <p>{@code DispatcherServlet} is mapped to {@code *.rol} only, so Spring
     * MVC's static-resource handling never sees an asset request and
     * {@code classpath:/static} is dead here. The servlet-spec classpath
     * location, {@code WEB-INF/classes/META-INF/resources}, is dead too: the
     * app ships as a Boot executable WAR, where {@code WEB-INF/classes} is a
     * nested classpath entry Boot's static-resource scan cannot open. Both
     * were tried and both 404'd; the browser suite is what said so.
     */
    @Test
    void theBundleLandsWhereHeadJspLoadsIt() throws IOException {
        String build = Files.readString(Path.of("frontend", "build.mjs"));
        assertTrue(build.contains("src/main/webapp/pressroom-ui/scripts/roller-editor.js"),
                "esbuild outfile must be the webapp root, the only static location this "
                        + "deployment serves");
        assertFalse(build.contains("target/classes/"),
                "no classpath location is served here -- DispatcherServlet is mapped to *.rol "
                        + "only, and WEB-INF/classes is nested inside the executable WAR");
        String head = Files.readString(HEAD_JSP);
        assertTrue(head.contains("/pressroom-ui/scripts/roller-editor.js"),
                "head.jsp must load the bundle");
    }

    /**
     * The reader's bundle (code highlighting, copy buttons, heading anchors) is
     * the editor bundle's twin: same build, same webapp-root location, for the
     * same reason -- it is the one static location this deployment serves. It
     * is loaded by the content-asset macro in {@code weblog.vm}, not by a JSP,
     * because it serves public pages.
     */
    @Test
    void theReaderBundleLandsBesideTheEditorBundleWhereWeblogVmLoadsIt() throws IOException {
        String build = Files.readString(Path.of("frontend", "build.mjs"));
        assertTrue(build.contains("src/content.js"), "build.mjs must bundle the reader entry");
        assertTrue(build.contains("src/main/webapp/pressroom-ui/scripts/roller-content.js"),
                "the reader bundle must land in the webapp root beside roller-editor.js");
        assertTrue(build.contains("globalName: 'PressroomContent'"),
                "the macro calls window.PressroomContent.enhance, so the IIFE must expose it");
        String weblogVm = Files.readString(WEBLOG_VM);
        assertTrue(weblogVm.contains("$url.site/pressroom-ui/scripts/roller-content.js"),
                "the content-asset macro must load the bundle under the context path");
    }

    /**
     * highlight.js is BSD-3-Clause, which asks a redistribution to carry its
     * copyright notice; every reader's browser receives this bundle, and
     * {@code legalComments: 'none'} strips the library's own comments. Reads
     * the built file, which the frontend build writes before tests run.
     */
    @Test
    void theReaderBundleCarriesHighlightJsLicenseNotice() throws IOException {
        String bundle = Files.readString(Path.of("src/main/webapp/pressroom-ui/scripts/roller-content.js"));

        assertTrue(bundle.startsWith("/*! highlight.js "), bundle.substring(0, Math.min(120, bundle.length())));
        String notice = bundle.substring(0, bundle.indexOf("*/") + 2);
        assertTrue(notice.contains("BSD-3-Clause") && notice.contains("Copyright (c) 2006, Ivan Sagalaev"), notice);
    }

    /**
     * The retired editor and the icon font that existed only for its toolbar.
     * Checked as raw text over the whole file rather than as a dependency
     * lookup, so a comment that still explains why they are there fails too --
     * a stale justification is how a deleted dependency comes back.
     */
    @Test
    void easyMdeAndFontAwesomeAreGone() throws IOException {
        String app = Files.readString(APP_POM);
        String head = Files.readString(HEAD_JSP);
        assertFalse(app.contains("easymde"), "pom still depends on easymde");
        assertFalse(app.contains("font-awesome"), "pom still depends on font-awesome");
        assertFalse(head.contains("easymde"), "head.jsp still loads easymde");
        assertFalse(head.contains("font-awesome"), "head.jsp still loads font-awesome");
    }

    @Test
    void nodeArtifactsAreIgnored() throws IOException {
        String ignore = Files.readString(Path.of("..", ".gitignore"));
        assertTrue(ignore.contains("app/frontend/node_modules/"));
        assertTrue(ignore.contains("app/frontend/node/"));
        // The bundle has to live under the webapp root to be served at all
        // (see above), which puts a build output inside src/. Ignoring it is
        // what keeps it from being committed by the next `git add -A`.
        assertTrue(ignore.contains("app/src/main/webapp/pressroom-ui/scripts/roller-editor.js"),
                "the built bundle must be git-ignored: it lives under src/main/webapp");
        assertTrue(ignore.contains("app/src/main/webapp/pressroom-ui/scripts/roller-content.js"),
                "the reader bundle must be git-ignored too: it lives beside the editor's");
        assertTrue(Files.exists(PACKAGE_JSON.resolveSibling("package-lock.json")),
                "package-lock.json must be committed so npm ci is reproducible");
    }
}
