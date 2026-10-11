package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every /api/v1 route as Spring actually maps it -- read from the
 * RequestMappingHandlerMapping, not from annotations, so the MCP tools and
 * the coverage test see exactly what a REST caller can reach.
 */
public final class ApiRouteTable {

    public static final String API_PACKAGE = "org.apache.roller.weblogger.ui.restapi.v1";

    private final Map<String, ApiRoute> byKey;

    private ApiRouteTable(Map<String, ApiRoute> byKey) {
        this.byKey = Collections.unmodifiableMap(byKey);
    }

    public static ApiRouteTable from(RequestMappingHandlerMapping mapping) {
        Map<String, ApiRoute> routes = new TreeMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!API_PACKAGE.equals(handler.getBeanType().getPackageName())) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                throw new IllegalStateException(handler + " maps every HTTP method; name one.");
            }
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : methods) {
                    ApiRoute route = new ApiRoute(method.name(), pattern, handler);
                    routes.put(route.key(), route);
                }
            }
        });
        return new ApiRouteTable(routes);
    }

    public Optional<ApiRoute> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Collection<ApiRoute> all() {
        return byKey.values();
    }
}
