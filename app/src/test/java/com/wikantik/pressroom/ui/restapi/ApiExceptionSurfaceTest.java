package com.wikantik.pressroom.ui.restapi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ApiException's public surface after spec 2026-11-20-api-contract-design,
 * Task 3 (criterion 1): no public factory or constructor takes a free-text
 * detail, so outside {@code ui.restapi} the compiler refuses an unkeyed
 * problem. The public factories are pinned by signature; one added later
 * fails here and has to say why it is not a literal detail. The error-building
 * paths never throw on a bad argument: a code with no fixed status, or a null
 * argument array, still builds a problem.
 */
class ApiExceptionSurfaceTest {

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    @Test
    void thePublicFactoriesAreTheKeyedOnes() {
        Set<String> factories = Arrays.stream(ApiException.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .map(ApiExceptionSurfaceTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(Set.of(
                "problem(ProblemCode,String[])",
                "problem(ProblemCode,Throwable,String[])",
                // field is the request field's name, never text
                "problemOnField(String,ProblemCode,String[])",
                "problemOnField(String,ProblemCode,Throwable,String[])",
                // title is the HTTP status phrase (RFC 9457 about:blank), and there is no detail
                "httpError(int,String)",
                "authenticationRequired()",
                "weblogNotFound()",
                "permissionDenied()",
                // the hash is the currentContentHash extension member, not the detail
                "entryChanged(String)",
                "pageChanged(String)",
                "internalError()",
                "unsupportedMediaType()",
                "notAcceptable()")), factories);
    }

    @Test
    void noConstructorIsPublic() {
        for (Constructor<?> constructor : ApiException.class.getDeclaredConstructors()) {
            assertEquals(false, Modifier.isPublic(constructor.getModifiers()), constructor.toString());
        }
    }

    /** http.error has no fixed status: asked for as a keyed problem, it is the opaque 500, never a throw. */
    @Test
    void aCodeWithNoFixedStatusBuildsTheOpaque500() {
        ApiException problem = assertDoesNotThrow(() -> ApiException.problem(ProblemCode.HTTP_ERROR));
        assertEquals(500, problem.getStatus());
        assertEquals("server.internalError", problem.toProblem(null).code());
        assertEquals("The request could not be completed.", problem.getMessage());

        ApiException onField = assertDoesNotThrow(() -> ApiException.problemOnField("f", ProblemCode.HTTP_ERROR));
        assertEquals(500, onField.getStatus());
    }

    /** A null argument array is no arguments: the status stands and the text renders. */
    @Test
    void aNullArgumentArrayIsNoArguments() {
        ApiException problem = assertDoesNotThrow(() -> ApiException.problem(ProblemCode.ENTRY_NOT_FOUND, (String[]) null));
        assertEquals(404, problem.getStatus());
        assertEquals("No such entry.", problem.getMessage());

        FieldErrors errors = new FieldErrors();
        assertDoesNotThrow(() -> errors.add("title", ProblemCode.FIELD_REQUIRED, (String[]) null));
        ApiException collected = assertThrows(ApiException.class, errors::throwIfAny);
        assertEquals(400, collected.getStatus());
    }

    /** The interceptor's two refusals, keyed so it keeps its two permitted ui.restapi imports. */
    @Test
    void theInterceptorsRefusalsAreKeyed() {
        assertEquals("weblog.notFound", ApiException.weblogNotFound().toProblem(null).code());
        assertEquals("No such weblog.", ApiException.weblogNotFound().getMessage());
        assertEquals("auth.noPermission", ApiException.permissionDenied().toProblem(null).code());
        assertEquals(403, ApiException.permissionDenied().getStatus());
    }
}
