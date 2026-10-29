package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins that every shell script in the repo runs on the oldest bash it can be
 * handed. Sibling of QualityGatePomTest, ItHarnessPomTest and
 * ProductionComposeTest: a build-file invariant asserted as text.
 *
 * <p>The oldest bash is macOS's. Apple still ships <strong>bash 3.2</strong> as
 * {@code /bin/bash} (the last GPLv2 release), so a developer cloning this repo
 * on a Mac gets 3.2 for any {@code #!/bin/bash} script and, unless they have
 * installed a newer bash themselves, for {@code #!/usr/bin/env bash} too.
 *
 * <p>This is not hypothetical. {@code bin/db/migrate.sh} used {@code mapfile}
 * and {@code declare -A}, neither of which exists in 3.2, and it is on the path
 * of {@code ./roller dev}, {@code ./roller db}, {@code ./roller migrate} and
 * {@code ./roller reset} -- so the first command a new Mac developer runs died
 * with {@code mapfile: command not found}. The same file is baked into the
 * production app image as {@code /app/migrate.sh} and run by
 * {@code deploy/provision.sh}, so portability here is not only a dev-laptop
 * concern.
 *
 * <p>Scope note: this pins the constructs that are silently absent or
 * mis-parsed on 3.2. It deliberately does not police shebangs -- {@code
 * #!/bin/bash} is fine for a script that stays within 3.2, which is exactly
 * what these assertions enforce.
 */
class ShellPortabilityTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();

    /**
     * bash 4+ constructs, each absent or fatally mis-parsed on bash 3.2.
     * The message is what a developer sees on a Mac when it is violated.
     */
    private static final List<String[]> BASH4_CONSTRUCTS = List.of(
            new String[] {"\\bmapfile\\b", "mapfile: command not found (bash 4+; use `while IFS= read -r`)"},
            new String[] {"\\breadarray\\b", "readarray: command not found (bash 4+; use `while IFS= read -r`)"},
            new String[] {"\\b(declare|local|typeset)\\s+-[A-Za-z]*A[A-Za-z]*\\b",
                "declare -A: invalid option (bash 4+ associative array; use a delimited string)"},
            new String[] {"\\$\\{[A-Za-z_][A-Za-z0-9_]*(\\[[^]]*\\])?,,?\\}",
                "${var,,} lowercase expansion is bash 4+ (use tr)"},
            new String[] {"\\$\\{[A-Za-z_][A-Za-z0-9_]*(\\[[^]]*\\])?\\^\\^?\\}",
                "${var^^} uppercase expansion is bash 4+ (use tr)"},
            new String[] {"shopt\\s+-s\\s+globstar", "globstar is bash 4+ (use find)"});

    /**
     * Every shell script the repo ships, including the two entrypoints that
     * carry no {@code .sh} extension -- {@code ./roller} is precisely the file
     * a new developer runs first, so omitting it would leave the highest-value
     * script unchecked.
     */
    private static List<Path> shellScripts() throws IOException {
        List<Path> scripts = new ArrayList<>();
        for (String dir : List.of("bin", "deploy", "it-selenium/src/test/script")) {
            Path root = REPO.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".sh"))
                        .forEach(scripts::add);
            }
        }
        for (String named : List.of("roller", "bin/pressroom-api")) {
            Path p = REPO.resolve(named);
            if (Files.isRegularFile(p)) {
                scripts.add(p);
            }
        }
        return scripts;
    }

    @Test
    void everyShellScriptStaysWithinBash32() throws IOException {
        List<Path> scripts = shellScripts();
        assertTrue(scripts.size() >= 14,
                "expected to find the repo's shell scripts, found " + scripts.size()
                        + " -- if scripts moved, update the directories this test walks "
                        + "rather than letting it silently check nothing");

        List<String> violations = new ArrayList<>();
        for (Path script : scripts) {
            String body = Files.readString(script);
            for (String[] construct : BASH4_CONSTRUCTS) {
                Matcher m = Pattern.compile(construct[0]).matcher(body);
                while (m.find()) {
                    // A construct named in a comment is documentation (this
                    // repo explains why it avoids them); only code counts.
                    if (lineOf(body, m.start()).trim().startsWith("#")) {
                        continue;
                    }
                    violations.add(REPO.relativize(script) + ":" + lineNumberOf(body, m.start())
                            + "  " + construct[1]);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "macOS ships bash 3.2 as /bin/bash; these break there:\n  "
                        + String.join("\n  ", violations));
    }

    private static String lineOf(String body, int index) {
        int start = body.lastIndexOf('\n', index) + 1;
        int end = body.indexOf('\n', index);
        return body.substring(start, end < 0 ? body.length() : end);
    }

    private static int lineNumberOf(String body, int index) {
        return (int) body.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
