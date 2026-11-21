package com.wikantik.pressroom.ui.restapi.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@link Caller} decides how a request authenticated (spec 2026-11-20,
 * caller kind). Everyone else asks {@code Caller.current().kind()}; nobody
 * reads an {@code Authentication}'s details to work it out alone, because a
 * second reading is how a future login mechanism ends up with session powers
 * in one place and none in another.
 *
 * <p>Source scan over {@code app/src/main/java}, comments and javadoc
 * stripped first (a mention in prose is not a read), the same family as
 * {@code StaticServiceLocatorTest}:
 * <ol>
 *   <li>{@code instanceof} on a caller-kind marker ({@code ApiPrincipal},
 *       {@code PasswordCallerDetails}, {@code WebAuthenticationDetails}) or
 *       a {@code WebAuthenticationDetails.class} comparison appears only in
 *       {@code Caller}.</li>
 *   <li>{@code getDetails()} is called only in {@code Caller}, with one
 *       shape allowed anywhere: {@code getDetails() instanceof
 *       WeblogPinnedCredential}, which reads a token's weblog pin, not the
 *       caller's kind ({@code PressroomHandlerInterceptor}).</li>
 * </ol>
 */
class CallerKindConfinementTest {

    private static final Path MAIN =
            Path.of(System.getProperty("user.dir")).getParent().resolve("app/src/main/java");
    private static final String CALLER = "com/wikantik/pressroom/ui/restapi/auth/Caller.java";

    private static final Pattern KIND_MARKER = Pattern.compile(
            "\\binstanceof\\s+(?:[\\w.]+\\.)?(ApiPrincipal|PasswordCallerDetails|WebAuthenticationDetails)\\b"
                    + "|\\bWebAuthenticationDetails\\.class\\b");
    private static final Pattern DETAILS_READ = Pattern.compile("\\bgetDetails\\(\\)");
    private static final Pattern PIN_READ = Pattern.compile("\\s*instanceof\\s+WeblogPinnedCredential\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void onlyCallerTestsForACallerKindMarker() throws IOException {
        Map<String, String> sources = javaSources();
        assertTrue(KIND_MARKER.matcher(sources.get(CALLER)).find(),
                "the scan found no marker test in Caller itself, so it would find none anywhere");
        List<String> problems = new ArrayList<>();
        sources.forEach((file, code) -> {
            if (file.equals(CALLER)) {
                return;
            }
            Matcher m = KIND_MARKER.matcher(code);
            while (m.find()) {
                problems.add(file + " decides the caller kind itself (`" + m.group()
                        + "`). Ask Caller.current().kind() instead.");
            }
        });
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void onlyCallerReadsAnAuthenticationsDetails() throws IOException {
        Map<String, String> sources = javaSources();
        assertTrue(DETAILS_READ.matcher(sources.get(CALLER)).find(),
                "the scan found no getDetails() in Caller itself, so it would find none anywhere");
        List<String> problems = new ArrayList<>();
        sources.forEach((file, code) -> {
            if (file.equals(CALLER)) {
                return;
            }
            Matcher m = DETAILS_READ.matcher(code);
            while (m.find()) {
                Matcher pin = PIN_READ.matcher(code).region(m.end(), code.length());
                if (!pin.lookingAt()) {
                    problems.add(file + " reads getDetails() to learn how the request "
                            + "authenticated. Ask Caller.current().kind() instead.");
                }
            }
        });
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    private static Map<String, String> javaSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll(" ")).replaceAll(" ");
                sources.put(MAIN.relativize(file).toString().replace('\\', '/'), code);
            }
        }
        return sources;
    }
}
