package com.wikantik.pressroom.ui.core.security.sso;

/**
 * The icon on every reader sign-in button (the login page, the blog header,
 * the comments and the places nudge): Google's multicolour G when the
 * provider is Google ({@link SsoSettings#isGoogle()}), otherwise a neutral
 * "sign in" arrow drawn in the button's own ink. Inline SVG, so a page needs
 * no extra request and the CSP's img-src is not involved.
 *
 * <p>Built here rather than in a template because the G's fills are hex
 * colours, and a hash before a word is a directive in Velocity. Each mark is
 * one line with no single quote or backslash: the blog pages splice it into a
 * single-quoted JavaScript string as well as into markup.
 */
public final class SignInMark {

    private static final String OPEN =
            "<svg class=\"g-signin-mark\" xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\""
            + " viewBox=\"0 0 48 48\" aria-hidden=\"true\" focusable=\"false\">";

    /** Google's sign-in G (the branding guidelines' four-colour mark). */
    private static final String GOOGLE = OPEN
            + "<path fill=\"#EA4335\" d=\"M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0"
            + " 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z\"/>"
            + "<path fill=\"#4285F4\" d=\"M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26"
            + " 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z\"/>"
            + "<path fill=\"#FBBC05\" d=\"M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92"
            + " 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z\"/>"
            + "<path fill=\"#34A853\" d=\"M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16"
            + " 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z\"/>"
            + "</svg>";

    /** A door with an arrow going in, stroked in currentColor. */
    private static final String NEUTRAL = OPEN
            + "<path fill=\"none\" stroke=\"currentColor\" stroke-width=\"4\" stroke-linecap=\"round\""
            + " stroke-linejoin=\"round\" d=\"M28 6h10a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H28M20 34l10-10-10-10M30 24H6\"/>"
            + "</svg>";

    private SignInMark() {
    }

    /** The mark for this provider's buttons. */
    public static String forProvider(SsoSettings sso) {
        return sso.isGoogle() ? GOOGLE : NEUTRAL;
    }
}
