package org.apache.roller.weblogger.ui.core.security.sso;

/**
 * Where a reader lands after signing in. Only a path on this site is
 * followed; anything else becomes the site root, so the start step can never
 * be used as an open redirect (spec, Flow step 2 and criterion 6).
 */
public final class ReturnPaths {

    /** The session attribute that carries the return path across the provider round trip. */
    public static final String SESSION_ATTRIBUTE = "roller.sso.return";

    static final int MAX_LENGTH = 2000;

    private ReturnPaths() {
    }

    /**
     * @param raw         the requested return path, possibly hostile
     * @param contextPath the request's context path ({@code ""} at the root)
     * @return {@code raw} when it is a path under {@code contextPath}, otherwise
     *         {@code contextPath + "/"}
     */
    public static String sanitize(String raw, String contextPath) {
        String root = (contextPath == null ? "" : contextPath) + "/";
        if (raw == null
                || raw.length() > MAX_LENGTH
                || !raw.startsWith(root)
                // At the root context "//host" and "/\host" start with "/", yet a
                // browser reads both as another host.
                || raw.startsWith("//")
                || raw.startsWith("/\\")
                || hasControlCharacter(raw)) {
            return root;
        }
        return raw;
    }

    /** CR and LF would split the Location header; the rest have no business in a path. */
    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
