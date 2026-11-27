package com.wikantik.pressroom.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unit suite runs one test at a time, except for the classes listed in
 * {@link #CONCURRENT}, which run their own methods side by side on at most four
 * threads. Concurrency is deliberate and small (Jake, 2026-11-27: efficiency
 * first, no heavy concurrency). A class joins the list only when every test in
 * it is isolated, as CutoverScriptTest's are: a temp dir, stubs, log and lock
 * per test. CutoverScriptTest drives a 600-line shell script 48 times, about
 * 0.5 s each, mostly waiting on processes.
 */
class UnitSuiteConcurrencyTest {

    private static final List<String> CONCURRENT = List.of("CutoverScriptTest.java");

    @Test
    void theEngineRunsEverythingOneAtATimeUnlessAClassOptsIn() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        for (String parameter : List.of(
                "junit.jupiter.execution.parallel.enabled = true",
                "junit.jupiter.execution.parallel.mode.default = same_thread",
                "junit.jupiter.execution.parallel.mode.classes.default = same_thread",
                "junit.jupiter.execution.parallel.config.strategy = fixed",
                "junit.jupiter.execution.parallel.config.fixed.parallelism = 4",
                "junit.jupiter.execution.parallel.config.fixed.max-pool-size = 4")) {
            assertTrue(pom.contains(parameter), "surefire must pass " + parameter);
        }
    }

    @Test
    void onlyTheListedClassesRunConcurrently() throws IOException {
        List<String> concurrent;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            concurrent = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(UnitSuiteConcurrencyTest::optsIn)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertEquals(CONCURRENT, concurrent, "a new concurrent class is a reviewed decision: add it here");
    }

    private static boolean optsIn(Path source) {
        try {
            return Files.readString(source).contains("@Execution(ExecutionMode.CONCURRENT)")
                    && !source.getFileName().toString().equals("UnitSuiteConcurrencyTest.java");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + source, e);
        }
    }
}
