package com.wikantik.pressroom.ui.restapi.mcp;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
        for (Class<?> type : controllerTypes()) {
            register(ctx, type);
        }
        ctx.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();
        return mapping;
    }

    /**
     * One instance of every API controller, built as {@link #real()} builds
     * them (collaborators null, nothing injected), for a test that dispatches
     * to them but never lets a handler run.
     */
    public static List<Object> controllers() {
        List<Object> controllers = new ArrayList<>();
        for (Class<?> type : controllerTypes()) {
            controllers.add(instantiate(type));
        }
        return controllers;
    }

    private static List<Class<?>> controllerTypes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ApiRouteTable.API_PACKAGE)) {
            types.add(load(candidate.getBeanClassName()));
        }
        return types;
    }

    private static <T> void register(GenericWebApplicationContext ctx, Class<T> type) {
        ctx.registerBean(type, () -> type.cast(instantiate(type)));
    }

    private static Object instantiate(Class<?> type) {
        Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                .min(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        try {
            ctor.setAccessible(true);
            return ctor.newInstance(new Object[ctor.getParameterCount()]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
