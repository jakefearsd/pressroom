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
package com.wikantik.pressroom.testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the parts of it-selenium's build that stop the browser IT harness
 * leaking, and that are invisible in any single file: an execution's ordering
 * against another plugin's, a flag whose default is the leak, and the run id
 * that has to reach every artefact of a run for identity-based cleanup to work
 * at all. Same job {@code ProductionComposeTest} does for the deploy stack.
 *
 * <p>None of it is checked by running the suite: a run whose container leaks
 * its anonymous volume, or whose cleanup is scheduled after the container it
 * depends on has gone, still passes every test in the suite.
 */
class ItHarnessPomTest {

    private static final Path POM = Paths.get("../it-selenium/pom.xml");

    private static Document pom;

    @BeforeAll
    static void parse() throws Exception {
        assertTrue(Files.exists(POM), "missing " + POM.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        pom = factory.newDocumentBuilder().parse(POM.toFile());
    }

    /**
     * postgres:18 declares {@code VOLUME /var/lib/postgresql} (16 and earlier
     * declared {@code /var/lib/postgresql/data}), so every
     * run gets an anonymous volume. docker-maven-plugin's removeVolumes
     * defaults to false, which made this the one leak that did not need an
     * abort: a completely successful run orphaned a volume too.
     */
    @Test
    void pgStopRemovesTheAnonymousVolumeItCreated() {
        Element pgStop = execution("io.fabric8", "docker-maven-plugin", "pg-stop");
        assertEquals("true", childText(configurationOf(pgStop), "removeVolumes"),
                "pg-stop must pass removeVolumes; without it every run orphans a postgres data volume");
    }

    /**
     * A single fixed pidfile cannot survive the situation it exists for: run
     * N leaks its app, run N+1 truncates the file, and run N's pid is gone
     * for good. Same for the log, which took the leaked run's diagnostics with
     * it. Both must carry the run id.
     */
    @Test
    void everyPerRunArtefactCarriesTheRunId() {
        for (String property : List.of("it.app.pidfile", "it.app.log", "it.db.container-name")) {
            assertTrue(property(property).contains("${it.run.id}"),
                    property + " must be per-run (contain ${it.run.id}), was: " + property(property));
        }
        assertTrue(property("it.run.id").contains("${it.http.port}"),
                "the run id must include the reserved port, or two runs starting in the same second collide");
    }

    /** The container is what the sweep matches on, so its name must carry the run id too. */
    @Test
    void theItContainerIsNamedPerRun() {
        Element docker = plugin("io.fabric8", "docker-maven-plugin");
        String pattern = descendantText(docker, "containerNamePattern");
        assertEquals("${it.db.container-name}", pattern,
                "a fixed container name gives the sweep no way to tell a corpse from a concurrent run");
    }

    /**
     * Every container the build starts -- PostgreSQL, and Keycloak for the
     * SSO tests -- is named per run AND handed to the supervisor. pg-start
     * starts every image in the plugin's configuration, so a second image
     * added with a fixed name collides across concurrent runs, and one the
     * supervisor is not told about leaks whenever the build dies before
     * pg-stop: the supervisor removes only what it was given.
     */
    @Test
    void everyContainerIsNamedPerRunAndSupervised() {
        List<String> supervised = argumentsOf(execution("org.apache.maven.plugins",
                "maven-antrun-plugin", "it-supervisor"));
        List<Element> images = children(plugin("io.fabric8", "docker-maven-plugin"),
                "configuration", "images", "image");
        assertTrue(images.size() >= 1, "no images under docker-maven-plugin");
        for (Element image : images) {
            String alias = childText(image, "alias");
            String pattern = childText(children(image, "run").get(0), "containerNamePattern");
            assertNotNull(pattern, alias + " has no containerNamePattern: the plugin would name it alone");
            assertTrue(pattern.matches("\\$\\{[^}]+}"),
                    alias + "'s container name must be one property the supervisor can be given, was: " + pattern);
            String property = pattern.substring(2, pattern.length() - 1);
            assertTrue(property(property).contains("${it.run.id}"),
                    property + " must be per-run (contain ${it.run.id}), was: " + property(property));
            assertTrue(supervised.contains(pattern),
                    "the supervisor is not told about " + alias + "'s container (" + pattern
                            + "), so a build that dies before pg-stop leaks it: " + supervised);
        }
    }

    /**
     * Cleanup that only runs in post-integration-test is cleanup that does not
     * run when an infrastructure step in pre-integration-test fails, or on a
     * Ctrl-C. The sweep (reaps what earlier runs left) and the supervisor
     * (outlives this build) both have to be in pre-integration-test, and the
     * supervisor has to be there before anything it is responsible for exists.
     */
    @Test
    void theSweepAndTheSupervisorRunBeforeAnythingElseInThePhase() {
        assertEquals("pre-integration-test", phaseOf(execution("org.apache.maven.plugins",
                "maven-antrun-plugin", "it-sweep-stale")));
        assertEquals("pre-integration-test", phaseOf(execution("org.apache.maven.plugins",
                "maven-antrun-plugin", "it-supervisor")));

        List<String> antrunExecutions = executionIds(plugin("org.apache.maven.plugins", "maven-antrun-plugin"));
        assertEquals(List.of("it-sweep-stale", "it-supervisor", "app-stop"), antrunExecutions,
                "same-phase executions run in declaration order: the sweep must precede the supervisor "
                        + "(so it cannot reap the run it is about to start), and both precede app-stop");
    }

    /**
     * Maven runs same-phase executions in the order their plugins are declared,
     * which is load-bearing at both ends here: maven-antrun-plugin's
     * pre-integration-test work must happen before pg-start (so the sweep
     * cannot see this run's own container) and its app-stop must happen before
     * pg-stop (so the app releases its DB connections before the container
     * goes). exec-maven-plugin must stay after docker-maven-plugin for the
     * opposite reason: pg-wait-ready has nothing to wait for until pg-start
     * has run. Merging any of these into one plugin declaration silently
     * reorders them; the pom comment records the effective-pom evidence.
     */
    @Test
    void pluginDeclarationOrderIsTheOneThatSchedulesCleanupCorrectly() {
        List<String> plugins = declaredPlugins();
        assertTrue(plugins.indexOf("maven-antrun-plugin") < plugins.indexOf("docker-maven-plugin"),
                "antrun must be declared before docker-maven-plugin, or app-stop runs after pg-stop: " + plugins);
        assertTrue(plugins.indexOf("docker-maven-plugin") < plugins.indexOf("exec-maven-plugin"),
                "docker-maven-plugin must be declared before exec-maven-plugin, or pg-wait-ready runs "
                        + "before pg-start: " + plugins);
        assertEquals(1, plugins.stream().filter("exec-maven-plugin"::equals).count(),
                "a second exec-maven-plugin declaration is merged into the first and reorders the phase");
        assertEquals(1, plugins.stream().filter("maven-antrun-plugin"::equals).count(),
                "a second maven-antrun-plugin declaration is merged into the first and reorders the phase");
    }

    /** stop-app.sh needs the run id to work when the pidfile is gone; app-start stamps it. */
    @Test
    void theScriptsAreGivenTheRunId() {
        assertTrue(argumentsOf(execution("org.codehaus.mojo", "exec-maven-plugin", "app-start"))
                        .contains("${it.run.id}"),
                "start-app.sh must be told the run id, or nothing it starts is identifiable");
        assertTrue(argumentsOf(execution("org.apache.maven.plugins", "maven-antrun-plugin", "app-stop"))
                        .contains("${it.run.id}"),
                "stop-app.sh must be told the run id, or it can only work from the pidfile");
    }

    // ------------------------------------------------------------------ dom

    private static String property(String name) {
        NodeList properties = pom.getElementsByTagName("properties");
        for (int i = 0; i < properties.getLength(); i++) {
            String value = childText((Element) properties.item(i), name);
            if (value != null) {
                return value;
            }
        }
        return fail("no <" + name + "> property in " + POM);
    }

    /** Plugin artifactIds in declaration order, which is what Maven schedules by. */
    private static List<String> declaredPlugins() {
        List<String> ids = new ArrayList<>();
        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            ids.add(childText((Element) plugins.item(i), "artifactId"));
        }
        return ids;
    }

    private static Element plugin(String groupId, String artifactId) {
        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (artifactId.equals(childText(plugin, "artifactId"))
                    && (childText(plugin, "groupId") == null || groupId.equals(childText(plugin, "groupId")))) {
                return plugin;
            }
        }
        return fail("no " + groupId + ":" + artifactId + " plugin in " + POM);
    }

    private static Element execution(String groupId, String artifactId, String executionId) {
        for (Element execution : children(plugin(groupId, artifactId), "executions", "execution")) {
            if (executionId.equals(childText(execution, "id"))) {
                return execution;
            }
        }
        return fail("no execution '" + executionId + "' on " + artifactId);
    }

    private static List<String> executionIds(Element plugin) {
        List<String> ids = new ArrayList<>();
        for (Element execution : children(plugin, "executions", "execution")) {
            ids.add(childText(execution, "id"));
        }
        return ids;
    }

    private static String phaseOf(Element execution) {
        return childText(execution, "phase");
    }

    private static Element configurationOf(Element execution) {
        List<Element> configuration = children(execution, "configuration");
        assertEquals(1, configuration.size(), "expected exactly one <configuration>");
        return configuration.get(0);
    }

    /** Every argument value under an execution, however the plugin spells them. */
    private static List<String> argumentsOf(Element execution) {
        List<String> values = new ArrayList<>();
        for (String tag : List.of("argument", "arg")) {
            NodeList nodes = execution.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                values.add(element.hasAttribute("value") ? element.getAttribute("value") : text(element));
            }
        }
        return values;
    }

    /** Direct children matching a path of tag names, e.g. ("executions", "execution"). */
    private static List<Element> children(Element parent, String... path) {
        List<Element> current = List.of(parent);
        for (String tag : path) {
            List<Element> next = new ArrayList<>();
            for (Element element : current) {
                NodeList nodes = element.getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                        next.add((Element) node);
                    }
                }
            }
            current = next;
        }
        return current;
    }

    private static String childText(Element parent, String tag) {
        List<Element> matches = children(parent, tag);
        return matches.isEmpty() ? null : text(matches.get(0));
    }

    private static String descendantText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        assertNotNull(nodes.item(0), "no <" + tag + "> under " + parent.getNodeName());
        return text((Element) nodes.item(0));
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }
}
