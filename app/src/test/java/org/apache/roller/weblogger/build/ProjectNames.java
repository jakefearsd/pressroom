package org.apache.roller.weblogger.build;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.roller.util.RollerConstants;

/**
 * The project's root package name, for the rebrand safety-net scanners.
 *
 * <p>The current root is derived from a real class
 * ({@code RollerConstants}), not written as a literal, so after the Phase 1
 * package move the scanners follow the code with no edit. {@link #LEGACY_ROOT}
 * is the one literal of the old root in this package; it keeps old-name
 * tokens in scope once the two roots differ.
 */
public final class ProjectNames {

    /** The pre-rebrand root package. */
    static final String LEGACY_ROOT = "org.apache.roller";

    /** A dotted or slashed qualified name under any of {@link #roots()}. */
    static final Pattern QUALIFIED_NAME = buildPattern();

    private ProjectNames() {
    }

    /** The parent package of {@code RollerConstants}. */
    public static String currentRoot() {
        String pkg = RollerConstants.class.getPackageName();
        return pkg.substring(0, pkg.lastIndexOf('.'));
    }

    /** The current root and the legacy root (equal until the package move). */
    static Set<String> roots() {
        Set<String> roots = new LinkedHashSet<>();
        roots.add(currentRoot());
        roots.add(LEGACY_ROOT);
        return roots;
    }

    /**
     * The token with its root removed, and with the {@code weblogger} level
     * that the package move drops, so a name keys the same before and after
     * the move: {@code <legacy>.weblogger.a.B} and {@code <new>.a.B} both give
     * {@code .a.B}. Dotted and slashed tokens alike. Empty when the token
     * starts with none of the roots.
     */
    static Optional<String> rootRelative(String token, Collection<String> roots) {
        for (String root : roots) {
            for (String form : new String[] {root, root.replace('.', '/')}) {
                if (token.startsWith(form) && atBoundary(token, form.length())) {
                    String rest = token.substring(form.length());
                    for (String level : new String[] {".weblogger", "/weblogger"}) {
                        if (rest.startsWith(level) && atBoundary(rest, level.length())) {
                            rest = rest.substring(level.length());
                        }
                    }
                    return Optional.of(rest);
                }
            }
        }
        return Optional.empty();
    }

    /** True when {@code s} ends at {@code i} or continues with a separator there. */
    private static boolean atBoundary(String s, int i) {
        return i == s.length() || ".$/".indexOf(s.charAt(i)) >= 0;
    }

    private static Pattern buildPattern() {
        StringBuilder sb = new StringBuilder();
        for (String root : roots()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(root)).append("(?:\\.[A-Za-z_$][\\w$]*)+");
            sb.append('|');
            sb.append(Pattern.quote(root.replace('.', '/'))).append("(?:/[\\w$.-]+)+");
        }
        return Pattern.compile(sb.toString());
    }
}
