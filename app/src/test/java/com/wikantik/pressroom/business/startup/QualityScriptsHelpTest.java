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
package com.wikantik.pressroom.business.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * bin/quality-report.sh and bin/check-diff-coverage.sh answer {@code --help}
 * with their usage, instead of taking it for a rule name or a git ref.
 *
 * <p>Each runs from a scratch checkout with {@code mvn}, {@code diff-cover},
 * {@code pgrep} and {@code python3} as stubs that record being called: a help
 * request must do none of the work.
 */
class QualityScriptsHelpTest {

    @TempDir Path dir;
    private Path checkout;
    private Map<String, String> env;

    @BeforeEach
    void layOut() throws Exception {
        checkout = dir.resolve("checkout");
        Files.createDirectories(checkout.resolve("bin"));
        Files.createDirectories(checkout.resolve("app/target/site/jacoco"));
        Files.writeString(checkout.resolve("app/target/site/jacoco/jacoco.xml"), "<report/>\n");
        for (String script : new String[] {"quality-report.sh", "check-diff-coverage.sh"}) {
            Files.copy(Paths.get("../bin/" + script), checkout.resolve("bin/" + script));
        }
        Path bin = dir.resolve("stubs");
        for (String tool : new String[] {"mvn", "diff-cover", "python3"}) {
            ScriptRun.stub(bin, tool, "echo \"" + tool + " $*\" >> \"$STUB_LOG\"\n");
        }
        ScriptRun.stub(bin, "pgrep", "exit 1\n");
        env = Map.of("STUB_LOG", dir.resolve("calls.log").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void theQualityReportPrintsItsUsage(String flag) throws Exception {
        ScriptRun.Result r = run("quality-report.sh", flag);

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("Usage: bin/quality-report.sh [rule-name]"), r.out());
        assertFalse(r.out().contains("#"), "the comment markers are stripped: " + r.out());
        assertEquals("", calls(), "a help request builds nothing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void theDiffCoverageCheckPrintsItsUsage(String flag) throws Exception {
        ScriptRun.Result r = run("check-diff-coverage.sh", flag);

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("Usage: bin/check-diff-coverage.sh [base-ref]"), r.out());
        assertEquals("", calls(), "a help request compares nothing");
    }

    /** No rule name and no git ref starts with a dash, so any other flag is a mistake. */
    @Test
    void anUnknownFlagIsAUsageError() throws Exception {
        ScriptRun.Result report = run("quality-report.sh", "--rule");
        ScriptRun.Result coverage = run("check-diff-coverage.sh", "--base");

        assertEquals(2, report.exit(), report.out());
        assertTrue(report.out().contains("Usage:"), report.out());
        assertEquals(2, coverage.exit(), coverage.out());
        assertTrue(coverage.out().contains("Usage:"), coverage.out());
        assertEquals("", calls());
    }

    /** The ordinary argument still means what it did. */
    @Test
    void aBaseRefStillReachesDiffCover() throws Exception {
        ScriptRun.Result r = run("check-diff-coverage.sh", "origin/master");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("--compare-branch=origin/master"), calls());
    }

    private ScriptRun.Result run(String script, String... args) throws Exception {
        return ScriptRun.run(dir, dir.resolve("stubs"), env, checkout.resolve("bin/" + script), args);
    }

    private String calls() throws Exception {
        return ScriptRun.log(dir.resolve("calls.log"));
    }
}
