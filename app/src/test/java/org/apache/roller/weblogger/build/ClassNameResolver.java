package org.apache.roller.weblogger.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decides whether a qualified name written in a non-Java file names something
 * that exists.
 *
 * <p>Names are matched against the live classpath and the compiled class
 * roots, never against a literal root, so after the Phase 1 package move the
 * scanners follow the code with no edit ({@link ProjectNames} derives the
 * root from a class; its {@code LEGACY_ROOT} keeps old tokens in scope).
 */
final class ClassNameResolver {

    private static final ClassLoader LOADER = ClassNameResolver.class.getClassLoader();

    private ClassNameResolver() {
    }

    private static List<Path> classRoots() {
        Path target = RepoFiles.repoRoot().resolve("app").resolve("target");
        return List.of(target.resolve("classes"), target.resolve("test-classes"));
    }

    /** Empty when the token resolves; otherwise the reason it does not. */
    static Optional<String> unresolved(String token) {
        if (token.indexOf('/') >= 0) {
            return resolvesSlashed(token) ? Optional.empty()
                    : Optional.of("no such resource or package directory");
        }
        if (isClassOrNested(token) || isPackage(token.replace('.', '/'))) {
            return Optional.empty();
        }
        int dot = token.lastIndexOf('.');
        if (dot > 0 && isMemberName(token.substring(dot + 1))
                && isClassOrNested(token.substring(0, dot))) {
            return Optional.empty();
        }
        return Optional.of("no such class, package, member or nested class");
    }

    /** True when some compiled class's name starts with the dotted prefix (a trailing-star glob). */
    static boolean matchesClassPrefix(String dottedPrefix) {
        String slashed = dottedPrefix.replace('.', '/');
        for (Path root : classRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                if (walk.anyMatch(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    return rel.startsWith(slashed) && rel.endsWith(".class");
                })) {
                    return true;
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot walk " + root, e);
            }
        }
        return false;
    }

    /** A method or field reference: lowercase-initial, or an ALL_CAPS constant (enum value). */
    private static boolean isMemberName(String last) {
        return Character.isLowerCase(last.charAt(0)) || last.equals(last.toUpperCase(java.util.Locale.ROOT));
    }

    /** A class, or a nested class: some number of trailing dots replaced by dollars loads. */
    private static boolean isClassOrNested(String name) {
        String candidate = name;
        while (true) {
            if (isClass(candidate)) {
                return true;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return false;
            }
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
        }
    }

    private static boolean isClass(String name) {
        try {
            Class.forName(name, false, LOADER);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean resolvesSlashed(String token) {
        String path = token.endsWith("/") ? token.substring(0, token.length() - 1) : token;
        return LOADER.getResource(path) != null || isPackage(path) || isSourceFile(path);
    }

    /** A tracked Java source file, named by its package path (e.g. a test's reference to a source it scans). */
    private static boolean isSourceFile(String path) {
        for (String src : List.of("app/src/main/java", "app/src/test/java",
                "it-selenium/src/main/java", "it-selenium/src/test/java")) {
            if (Files.isRegularFile(RepoFiles.repoRoot().resolve(src).resolve(path))) {
                return true;
            }
        }
        return false;
    }

    /** A directory under a class root holding at least one .class file at any depth. */
    private static boolean isPackage(String slashed) {
        for (Path root : classRoots()) {
            Path dir = root.resolve(slashed).normalize();
            if (dir.startsWith(root) && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    if (walk.anyMatch(p -> p.getFileName().toString().endsWith(".class"))) {
                        return true;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("cannot walk " + dir, e);
                }
            }
        }
        return false;
    }
}
