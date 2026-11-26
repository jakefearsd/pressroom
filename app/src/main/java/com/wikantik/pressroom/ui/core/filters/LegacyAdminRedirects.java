package com.wikantik.pressroom.ui.core.filters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The one table of legacy admin GET routes that now belong to the React admin (spec
 * 2026-11-25-react-admin-migration-design, A8 step 1): each row names the old {@code .rol} URL, the React
 * route it answers 302 to, which request parameters become path segments, and which are carried over as the
 * query. {@link LegacyAdminRedirectFilter} serves it; every wave that migrates a screen adds its rows here
 * and nowhere else (docs/dev/admin-ui.md, "Migrating a screen to React").
 *
 * <p>A row's React route is relative to {@code /pressroom-ui/app}; a path variable written
 * {@code {name}} takes the value of the request parameter the row maps it to, percent-encoded as one
 * segment. When a needed parameter is missing or blank the redirect goes to the app home rather than to a
 * route that cannot render. The target is built from the context path, a fixed prefix and encoded values
 * only, so it is host-relative and cannot leave the site.
 *
 * <p>A route is redirected or handled by a JSP controller, never both: the controller is deleted in the
 * same change that adds the row ({@code LegacyAdminRedirectsTest} fails otherwise).
 *
 * @param legacyPath the old route, servlet path with no context path
 * @param reactPath the React route under the app, {@code {name}} placeholders allowed
 * @param pathParams placeholder name to the request parameter that supplies it
 * @param carried request parameters copied to the query string, in this order, when present
 */
public record LegacyAdminRedirects(String legacyPath, String reactPath, Map<String, String> pathParams,
                                   List<String> carried) {

    /** Where the React app lives, as a servlet path (spec A1). */
    public static final String APP = "/pressroom-ui/app";

    /** The rows. Order is irrelevant; a legacy path appears once. */
    public static final List<LegacyAdminRedirects> ROWS = List.of(
            new LegacyAdminRedirects("/pressroom-ui/menu.rol", "/", Map.of(), List.of()),
            new LegacyAdminRedirects("/pressroom-ui/authoring/traffic.rol", "/weblogs/{weblog}/traffic",
                    Map.of("weblog", "weblog"), List.of("period", "tab", "sort")),
            new LegacyAdminRedirects("/pressroom-ui/admin/siteTraffic.rol", "/admin/traffic", Map.of(),
                    List.of("period")));

    /** Every legacy path in the table, for the filter's URL patterns. */
    public static List<String> legacyPaths() {
        return ROWS.stream().map(LegacyAdminRedirects::legacyPath).toList();
    }

    /**
     * The redirect target for a request, as a host-relative URL under the context path, or empty when the
     * path is not in the table.
     *
     * @param contextPath the request's context path, "" at the root
     * @param path the servlet path requested
     * @param param reads a request parameter (null when absent)
     */
    public static Optional<String> locate(String contextPath, String path, UnaryOperator<String> param) {
        for (LegacyAdminRedirects row : ROWS) {
            if (row.legacyPath.equals(path)) {
                return Optional.of(contextPath + APP + row.resolve(param));
            }
        }
        return Optional.empty();
    }

    private String resolve(UnaryOperator<String> param) {
        String route = reactPath;
        for (Map.Entry<String, String> placeholder : pathParams.entrySet()) {
            String value = param.apply(placeholder.getValue());
            if (value == null || value.isBlank()) {
                return "/";
            }
            route = route.replace("{" + placeholder.getKey() + "}", encode(value));
        }
        List<String> query = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String name : carried) {
            String value = param.apply(name);
            if (value != null && !value.isEmpty()) {
                seen.put(name, value);
            }
        }
        seen.forEach((name, value) -> query.add(encode(name) + "=" + encode(value)));
        return query.isEmpty() ? route : route + "?" + String.join("&", query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
