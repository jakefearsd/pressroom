package com.wikantik.pressroom.ui.restapi.mcp;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * Derives a route's tool arguments from its handler method, so the tools
 * cannot drift from the API: path variables ({handle} is named "weblog"),
 * @RequestParam query parameters, and the @RequestBody type's record
 * components (a Map body becomes one "settings" object). Descriptions come
 * from springdoc's @Schema/@Parameter on the API's own types, the same text
 * /api/v1/openapi.json carries. Anything it cannot derive stops startup.
 */
public final class ArgumentDeriver {

    private static final String WEBLOG = "weblog";

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    /** The JSON type of each Java scalar a handler parameter or body component may be. */
    private static final Map<Class<?>, String> SCALAR_TYPES = Map.ofEntries(
            Map.entry(String.class, "string"),
            Map.entry(Boolean.class, "boolean"), Map.entry(boolean.class, "boolean"),
            Map.entry(Integer.class, "integer"), Map.entry(int.class, "integer"),
            Map.entry(Long.class, "integer"), Map.entry(long.class, "integer"),
            Map.entry(Double.class, "number"), Map.entry(double.class, "number"),
            Map.entry(Float.class, "number"), Map.entry(float.class, "number"));

    private ArgumentDeriver() {
    }

    public static List<ArgumentSpec> derive(ApiRoute route) {
        List<ArgumentSpec> specs = new ArrayList<>();
        for (MethodParameter param : route.handler().getMethodParameters()) {
            PathVariable path = param.getParameterAnnotation(PathVariable.class);
            RequestParam query = param.getParameterAnnotation(RequestParam.class);
            RequestBody body = param.getParameterAnnotation(RequestBody.class);
            if (path != null) {
                String source = named(route, path.value().isEmpty() ? path.name() : path.value());
                specs.add(pathArgument(source));
            } else if (query != null) {
                specs.add(queryArgument(route, param, query));
            } else if (body != null) {
                specs.addAll(bodyArguments(route, param));
            } else if (!isServletInfrastructure(param)) {
                throw new IllegalStateException(route.key() + ": cannot derive an argument from parameter "
                        + param.getParameterIndex() + " (" + param.getParameterType().getName() + ")");
            }
        }
        addUnboundPathVariables(route, specs);
        Set<String> seen = new HashSet<>();
        for (ArgumentSpec spec : specs) {
            if (!seen.add(spec.name())) {
                throw new IllegalStateException(route.key() + ": argument '" + spec.name() + "' appears twice");
            }
        }
        return specs;
    }

    private static ArgumentSpec queryArgument(ApiRoute route, MethodParameter param, RequestParam query) {
        String source = named(route, query.value().isEmpty() ? query.name() : query.value());
        Map<String, Object> schema = new LinkedHashMap<>(jsonType(route, param.getGenericParameterType()));
        Parameter doc = param.getParameterAnnotation(Parameter.class);
        if (doc != null && !doc.description().isBlank()) {
            schema.put("description", doc.description());
        }
        boolean required = query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue());
        return new ArgumentSpec(source, ArgumentSpec.Location.QUERY, source, schema, required);
    }

    /**
     * {handle} and friends that the handler never binds (the interceptor
     * resolves the action weblog from the URL) are still arguments: the
     * dispatcher needs them to build the URL.
     */
    private static void addUnboundPathVariables(ApiRoute route, List<ArgumentSpec> specs) {
        Matcher template = TEMPLATE_VARIABLE.matcher(route.pathTemplate());
        while (template.find()) {
            String variable = template.group(1);
            if (specs.stream().noneMatch(s -> s.location() == ArgumentSpec.Location.PATH
                    && s.sourceName().equals(variable))) {
                specs.add(pathArgument(variable));
            }
        }
    }

    private static ArgumentSpec pathArgument(String source) {
        String name = "handle".equals(source) ? WEBLOG : source;
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "string"));
        if (WEBLOG.equals(name)) {
            schema.put("description", "The weblog's handle; whoami and weblogs list find them.");
        }
        return new ArgumentSpec(name, ArgumentSpec.Location.PATH, source, schema, true);
    }

    public static boolean takesBody(ApiRoute route) {
        for (MethodParameter param : route.handler().getMethodParameters()) {
            if (param.hasParameterAnnotation(RequestBody.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<ArgumentSpec> bodyArguments(ApiRoute route, MethodParameter param) {
        Class<?> type = param.getParameterType();
        if (Map.class.isAssignableFrom(type)) {
            return List.of(new ArgumentSpec("settings", ArgumentSpec.Location.BODY_MAP, null,
                    Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
                            "description", "Property name to new value, as strings."),
                    true));
        }
        if (!type.isRecord()) {
            throw new IllegalStateException(route.key() + ": request body " + type.getName() + " is not a record");
        }
        List<ArgumentSpec> specs = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            specs.add(new ArgumentSpec(component.getName(), ArgumentSpec.Location.BODY,
                    component.getName(), describedType(route, component), false));
        }
        return specs;
    }

    /** A record component's JSON type with its springdoc description, when it has one. */
    private static Map<String, Object> describedType(ApiRoute route, RecordComponent component) {
        Map<String, Object> schema = new LinkedHashMap<>(jsonType(route, component.getGenericType()));
        Schema doc = component.getAccessor().getAnnotation(Schema.class);
        if (doc != null && !doc.description().isBlank()) {
            schema.put("description", doc.description());
        }
        return schema;
    }

    private static Map<String, Object> jsonType(ApiRoute route, Type type) {
        if (type instanceof ParameterizedType parameterized
                && List.class.isAssignableFrom((Class<?>) parameterized.getRawType())) {
            return Map.of("type", "array", "items", jsonType(route, parameterized.getActualTypeArguments()[0]));
        }
        if (type instanceof Class<?> cls) {
            String scalar = SCALAR_TYPES.get(cls);
            if (scalar != null) {
                return Map.of("type", scalar);
            }
            if (cls.isArray() && cls.getComponentType() == String.class) {
                return Map.of("type", "array", "items", Map.of("type", "string"));
            }
            if (cls.isRecord()) {
                // A nested record (an entry update's edits) describes its own
                // components, as it does in openapi.json.
                Map<String, Object> properties = new LinkedHashMap<>();
                for (RecordComponent component : cls.getRecordComponents()) {
                    properties.put(component.getName(), describedType(route, component));
                }
                return Map.of("type", "object", "properties", properties);
            }
        }
        throw new IllegalStateException(route.key() + ": cannot derive a JSON type for " + type.getTypeName());
    }

    private static String named(ApiRoute route, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(route.key() + ": an unnamed @PathVariable/@RequestParam"
                    + " (the build has no -parameters; name it)");
        }
        return name;
    }

    private static boolean isServletInfrastructure(MethodParameter param) {
        Class<?> type = param.getParameterType();
        return HttpServletRequest.class.isAssignableFrom(type)
                || HttpServletResponse.class.isAssignableFrom(type)
                || Principal.class.isAssignableFrom(type)
                || Locale.class == type
                || param.hasParameterAnnotation(RequestAttribute.class);
    }
}
