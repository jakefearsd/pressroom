package org.apache.roller.weblogger.ui.restapi.mcp;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The real route table for unit tests: every @RestController in the API
 * package, found by scanning (so a new controller is included without
 * touching this file), mapped by a real RequestMappingHandlerMapping. The
 * controllers' collaborators are left null -- only their mappings matter.
 */
public final class ApiRouteTables {

    private ApiRouteTables() {
    }

    public static ApiRouteTable real() {
        return ApiRouteTable.from(realMapping());
    }

    /** The mapping {@link #real()} reads, for a test that must register it as a bean. */
    public static RequestMappingHandlerMapping realMapping() {
        GenericWebApplicationContext ctx = new GenericWebApplicationContext(new MockServletContext());
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition candidate : scanner.findCandidateComponents(ApiRouteTable.API_PACKAGE)) {
            Class<?> type = load(candidate.getBeanClassName());
            register(ctx, type);
        }
        ctx.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();
        return mapping;
    }

    @SuppressWarnings("unchecked")
    private static <T> void register(GenericWebApplicationContext ctx, Class<T> type) {
        Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                .min(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        ctx.registerBean(type, () -> {
            try {
                ctor.setAccessible(true);
                return (T) ctor.newInstance(new Object[ctor.getParameterCount()]);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
