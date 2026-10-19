package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a preview warns about: markup that renders, but badly for some
 * theme or some reader. It reads the sanitizer's output, whose attributes
 * are always double-quoted, so a pattern over it is exact enough; a warning
 * is advice, never a refusal.
 *
 * <p>An inline color is the costly one. The first agent post carried
 * callouts with a pale background and no text color, written for a light
 * canvas; the weblog's theme is dark, so near-white text sat on pale blue at
 * about 1.1:1. A GitHub alert is styled by the theme instead.
 */
final class PreviewLint {

    // No quantified group (a quantifier inside one backtracks badly); the
    // lookbehind keeps a whole attribute name, never the tail of data-style.
    private static final Pattern STYLED_TAG =
            Pattern.compile("<[a-zA-Z][a-zA-Z0-9]*\\s[^>]*?(?<=\\s)style=\"([^\"]*)\"[^>]*>");
    private static final Set<String> COLORING = Set.of("color", "background", "background-color");
    private static final Pattern UNNAMED_CODE_BLOCK =
            Pattern.compile("<pre\\b[^>]*>(?!\\s*<code\\b[^>]*\\bclass=\"[^\"]*\\blanguage-)");
    private static final int EXAMPLE_MAX = 120;

    private PreviewLint() {
    }

    static List<String> warnings(String html) {
        List<String> warnings = new ArrayList<>();
        inlineColors(html, warnings);
        unnamedCodeBlocks(html, warnings);
        return warnings;
    }

    private static void inlineColors(String html, List<String> warnings) {
        Matcher tag = STYLED_TAG.matcher(html);
        int count = 0;
        String first = null;
        while (tag.find()) {
            if (setsAColor(tag.group(1))) {
                count++;
                if (first == null) {
                    first = tag.group();
                }
            }
        }
        if (count == 0) {
            return;
        }
        String example = first.length() > EXAMPLE_MAX ? first.substring(0, EXAMPLE_MAX) + "…" : first;
        warnings.add(count + (count == 1 ? " element sets" : " elements set")
                + " a text color or background inline, for example " + example
                + ". Themes differ, and this one may be dark, so inline colors can leave text unreadable."
                + " For a callout, write a GitHub alert (> [!NOTE], [!TIP], [!IMPORTANT], [!WARNING] or"
                + " [!CAUTION]); otherwise drop the color and let the theme style it.");
    }

    private static boolean setsAColor(String style) {
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            // The sanitizer writes property names in lower case, so no case mapping.
            if (colon > 0 && COLORING.contains(declaration.substring(0, colon).trim())) {
                return true;
            }
        }
        return false;
    }

    private static void unnamedCodeBlocks(String html, List<String> warnings) {
        Matcher block = UNNAMED_CODE_BLOCK.matcher(html);
        int count = 0;
        while (block.find()) {
            count++;
        }
        if (count > 0) {
            warnings.add(count + (count == 1 ? " code block names" : " code blocks name")
                    + " no language, so readers see " + (count == 1 ? "it" : "them") + " without highlighting."
                    + " Name it after the opening fence, for example ```java.");
        }
    }
}
