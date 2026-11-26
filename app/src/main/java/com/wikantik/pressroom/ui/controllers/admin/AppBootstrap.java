package com.wikantik.pressroom.ui.controllers.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wikantik.pressroom.business.Colophon;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The one JSON block the React admin's shell carries
 * ({@code <script type="application/json" id="pressroom-bootstrap">}): context
 * path, application version, the CSRF header and token, the session's inactivity timeout (for the
 * editors' expiry warning), the signed-in user, the
 * UI locale, the site's names and the footer's colophon facts.
 *
 * <p>The block sits inside a script element, and a screen name is typed by a
 * person, so {@link #toJson} also escapes {@code <}, {@code >}, {@code &} and the
 * two JavaScript line separators as {@code \\uXXXX}. The result is the same
 * JSON to a parser and can never contain {@code </script>} or {@code <!--}.
 */
record AppBootstrap(String contextPath, String version, String csrfHeaderName, String csrfToken,
                    int sessionTimeoutSeconds,
                    String userName, String screenName, String userLocale, String globalPermission,
                    String uiLocale, String messagesUrl, Site site, Colophon colophon) {

    /**
     * The site's own facts the frame shows: its name (the brand) and short name (the tab title), and the
     * site space's handle, which the API never serves, so the frame cannot ask for it.
     */
    record Site(String name, String shortName, String siteSpaceHandle) {
    }

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
        Map<String, Object> siteFacts = new LinkedHashMap<>();
        siteFacts.put("name", site.name());
        siteFacts.put("shortName", site.shortName());
        siteFacts.put("siteSpaceHandle", site.siteSpaceHandle());
        // The footer's facts as the Colophon states them; one it does not know is left out, so the
        // footer drops that clause as footer.jsp does.
        Map<String, Object> facts = new LinkedHashMap<>();
        putIfPresent(facts, "copyrightHolder", colophon.copyrightHolder());
        facts.put("copyrightYear", Integer.toString(colophon.copyrightYear()));
        putIfPresent(facts, "privacyUrl", colophon.privacyUrl());
        putIfPresent(facts, "shortRevision", colophon.shortRevision());
        putIfPresent(facts, "releaseDate", colophon.releaseDate());
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("contextPath", contextPath);
        all.put("version", version);
        all.put("csrf", csrf);
        all.put("sessionTimeoutSeconds", sessionTimeoutSeconds);
        all.put("user", user);
        all.put("uiLocale", uiLocale);
        all.put("messagesUrl", messagesUrl);
        all.put("site", siteFacts);
        all.put("colophon", facts);
        try {
            return scriptSafe(MAPPER.writeValueAsString(all));
        } catch (JacksonException e) {
            throw new IllegalStateException("The bootstrap block is plain strings and cannot fail to serialise", e);
        }
    }

    private static void putIfPresent(Map<String, Object> into, String key, String value) {
        if (value != null && !value.isEmpty()) {
            into.put(key, value);
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
