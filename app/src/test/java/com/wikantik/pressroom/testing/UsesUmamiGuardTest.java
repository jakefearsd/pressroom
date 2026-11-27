package com.wikantik.pressroom.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every test class that uses the Umami container says so with {@link UsesUmami},
 * which is how {@link UmamiWarmUp} knows to start the container in the background
 * at the start of the run (it takes ~12 s to boot) instead of in the middle.
 */
class UsesUmamiGuardTest {

    @Test
    void everyUserOfTheUmamiContainerIsMarked() throws IOException {
        List<String> unmarked;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            unmarked = files.filter(p -> p.toString().endsWith("Test.java"))
                    .filter(p -> read(p).contains("UmamiContainer.get()"))
                    .filter(p -> !read(p).contains("@UsesUmami"))
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
        assertEquals(List.of(), unmarked);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }
}
