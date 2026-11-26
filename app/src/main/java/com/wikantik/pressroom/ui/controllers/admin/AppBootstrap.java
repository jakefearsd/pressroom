package com.wikantik.pressroom.ui.controllers.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The one JSON block the React admin's shell carries
 * ({@code <script type="application/json" id="pressroom-bootstrap">}): context
 * path, application version, the CSRF header and token, the signed-in user and
 * the UI locale.
 *
 * <p>The block sits inside a script element, and a screen name is typed by a
 * person, so {@link #toJson} also escapes {@code <}, {@code >}, {@code &} and the
 * two JavaScript line separators as {@code \\uXXXX}. The result is the same
 * JSON to a parser and can never contain {@code </script>} or {@code <!--}.
 */
record AppBootstrap(String contextPath, String version, String csrfHeaderName, String csrfToken,
                    String userName, String screenName, String userLocale, String globalPermission,
                    String uiLocale, String messagesUrl) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    String toJson() {
        Map<String, Object> csrf = new LinkedHashMap<>();
        csrf.put("headerName", csrfHeaderName);
        csrf.put("token", csrfToken);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userName", userName);
        user.put("screenName", screenName);
        user.put("locale", userLocale);
        user.put("globalPermission", globalPermission);
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("contextPath", contextPath);
        all.put("version", version);
        all.put("csrf", csrf);
        all.put("user", user);
        all.put("uiLocale", uiLocale);
        all.put("messagesUrl", messagesUrl);
        try {
            return scriptSafe(MAPPER.writeValueAsString(all));
        } catch (JacksonException e) {
            throw new IllegalStateException("The bootstrap block is plain strings and cannot fail to serialise", e);
        }
    }

    private static String scriptSafe(String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '<', '>', '&', ' ', ' ' -> out.append(String.format("\\u%04x", (int) c));
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
