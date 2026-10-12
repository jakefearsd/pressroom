package org.apache.roller.weblogger.ui.core.security.sso;

import java.util.Collection;
import java.util.Map;

/**
 * The identity claims Roller reads from a provider's ID token.
 *
 * @param issuer        the token issuer
 * @param subject       the provider's stable user id
 * @param email         the email claim, possibly null
 * @param emailVerified true only when the provider sent boolean {@code true}
 * @param name          the name claim, possibly null
 */
public record ReaderClaims(String issuer, String subject, String email, boolean emailVerified, String name) {

    private static final int MAX_EMAIL = 255;
    private static final int MAX_NAME_CODE_POINTS = 100;
    private static final String FALLBACK_NAME = "Reader";

    /** Builds the claims, taking the first value of any multi-valued claim. */
    public static ReaderClaims from(String issuer, String subject, Map<String, Object> claims) {
        Object email = firstScalar(claims.get("email"));
        Object name = firstScalar(claims.get("name"));
        return new ReaderClaims(issuer, subject,
                email == null ? null : email.toString(),
                Boolean.TRUE.equals(firstScalar(claims.get("email_verified"))),
                name == null ? null : name.toString());
    }

    static Object firstScalar(Object value) {
        if (value instanceof Collection<?> values) {
            return values.isEmpty() ? null : values.iterator().next();
        }
        return value;
    }

    /** The name shown for the reader; never blank. */
    public String displayName() {
        String fromName = clean(name);
        if (!fromName.isEmpty()) {
            return fromName;
        }
        if (email != null) {
            String fromEmail = clean(email.substring(0, Math.max(0, email.lastIndexOf('@'))));
            if (!fromEmail.isEmpty()) {
                return fromEmail;
            }
        }
        return FALLBACK_NAME;
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        raw.codePoints()
                .filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                .forEach(kept::appendCodePoint);
        String stripped = kept.toString().replaceAll("\\s+", " ").strip();
        if (stripped.codePointCount(0, stripped.length()) > MAX_NAME_CODE_POINTS) {
            stripped = stripped.substring(0, stripped.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)).strip();
        }
        return stripped;
    }

    /** Refuses sign-in unless there is a usable, provider-verified email. */
    public void requireUsable() throws ReaderSignInRefused {
        if (email == null || email.isBlank()) {
            throw new ReaderSignInRefused("no email");
        }
        if (email.length() > MAX_EMAIL) {
            throw new ReaderSignInRefused("email too long");
        }
        if (!emailVerified) {
            throw new ReaderSignInRefused("email not verified");
        }
    }
}
