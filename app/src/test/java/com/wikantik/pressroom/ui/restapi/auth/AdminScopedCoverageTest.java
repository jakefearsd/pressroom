package com.wikantik.pressroom.ui.restapi.auth;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Makes the {@link AdminScoped} convention enforceable rather than merely
 * documented: any REST API handler whose EFFECTIVE path -- class-level
 * {@code @RequestMapping} composed with the method's own
 * {@code @GetMapping}/{@code @PostMapping}/etc. -- starts with
 * {@code /v1/admin} must have its controller carry {@link AdminScoped}, or
 * {@code ApiScopeInterceptor} never applies the ADMIN-role ceiling to it and
 * the handler is silently ungated for every scoped token regardless of role.
 *
 * <p>Composing the two mapping levels, rather than reading only the
 * class-level annotation, matters: {@code MetaApi} already shows the shape a
 * real controller can take -- a shared class-level prefix ({@code
 * @RequestMapping("/v1")}) with the meaningful path segment living on each
 * method's own mapping. A controller written the same way for an admin
 * endpoint (class-level {@code "/v1"}, method-level
 * {@code @GetMapping("/admin/...")}) is genuinely mapped under
 * {@code /v1/admin} but carries no class-level {@code @RequestMapping} value
 * that says so -- a class-level-only scan does not see it at all. Verified:
 * see the task report for the scratch controller used to confirm this scan
 * catches that shape and the class-level-only version did not.
 *
 * <p>Tasks 15 and 16 add the real admin controllers this wave; this is what
 * catches them if they forget the annotation.
 */
class AdminScopedCoverageTest {

    @Test
    void everyHandlerMappedUnderV1AdminCarriesAdminScoped() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : restApiV1Classes()) {
            String classPrefix = firstPathOrEmpty(
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] methodPaths = mapping.value().length == 0
                        ? new String[] {""} : mapping.value();
                for (String methodPath : methodPaths) {
                    String effectivePath = compose(classPrefix, methodPath);
                    if (effectivePath.startsWith("/v1/admin")
                            && !controller.isAnnotationPresent(AdminScoped.class)) {
                        violations.add(controller.getName() + "#" + method.getName()
                                + " (" + effectivePath + ")");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "These handlers are mapped under /v1/admin (class-level mapping composed with the "
                        + "method's own) but their controller does not carry @AdminScoped, so "
                        + "ApiScopeInterceptor never applies the ADMIN-role ceiling to them:\n  "
                        + String.join("\n  ", violations));
    }

    private static String firstPathOrEmpty(RequestMapping mapping) {
        if (mapping == null || mapping.value().length == 0) {
            return "";
        }
        return mapping.value()[0];
    }

    /**
     * Spring's own path-composition rule in miniature: join two segments
     * with exactly one {@code /} between them, and let an empty method path
     * (e.g. {@code TokensApi.issue()}'s {@code @PostMapping("")}) contribute
     * nothing.
     */
    private static String compose(String classPrefix, String methodPath) {
        String left = classPrefix == null ? "" : classPrefix;
        String right = methodPath == null ? "" : methodPath;
        if (right.isEmpty()) {
            return left;
        }
        if (left.endsWith("/") && right.startsWith("/")) {
            return left + right.substring(1);
        }
        if (!left.isEmpty() && !left.endsWith("/") && !right.startsWith("/")) {
            return left + "/" + right;
        }
        return left + right;
    }

    /** Every class file directly under {@code ui.restapi.v1}, found on the classpath. */
    private static List<Class<?>> restApiV1Classes() throws Exception {
        Path root = Paths.get("src/main/java/com/wikantik/pressroom/ui/restapi/v1");
        assertTrue(Files.isDirectory(root), "Expected " + root.toAbsolutePath());

        List<Class<?>> classes = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString();
                String className = "com.wikantik.pressroom.ui.restapi.v1."
                        + relative.substring(0, relative.length() - ".java".length())
                                .replace(java.io.File.separatorChar, '.');
                classes.add(Class.forName(className));
            }
        }
        assertTrue(!classes.isEmpty(), "No REST API v1 controllers found to scan");
        return classes;
    }
}
