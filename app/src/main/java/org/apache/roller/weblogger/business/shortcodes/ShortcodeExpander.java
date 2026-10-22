/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.business.shortcodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Expands {@code [name attr="value"]body[/name]} shortcodes in entry text
 * into HTML, using a registry of {@link ShortcodeHandler}s.
 *
 * <p>Syntax rules:
 * <ul>
 *   <li>{@code [name attr="v"]body[/name]} -- body form; the body is
 *       expanded recursively first, so shortcodes may nest. Same-name tags
 *       pair inside-out like HTML elements; when opens and closers do not
 *       balance, the outermost tag takes the last closer and the leftover
 *       inner opens fall back to self-closing (a closer that had a matching
 *       open never leaks into the output as raw text).</li>
 *   <li>The closer scan sees TAGS, not substrings: a {@code [/name]}
 *       look-alike inside any well-formed tag's quoted attribute value
 *       ({@code [b caption="[/a]"]}) is part of that value, never a closer.
 *       A bare {@code [/name]} in plain body text IS a closer -- the parser
 *       cannot read minds -- but a tag's body extends to the LAST closer in
 *       the run before the next same-name open, so prose that mentions
 *       {@code [/name]} between the intended end and the real closer stays
 *       inside the body as literal text instead of truncating it and leaking
 *       the real closer. For precision, escape it (next bullet).</li>
 *   <li>{@code [name attr="v"]} or {@code [name attr="v" /]} -- self-closing
 *       (no matching {@code [/name]} means self-closing too).</li>
 *   <li>Attribute values may be double-quoted, single-quoted, or bare
 *       (no whitespace); a quoted value may contain {@code ]} and {@code [}.
 *       Names are matched case-insensitively. Quote anything URL-shaped or
 *       bracket-containing -- a bare value can contain neither.</li>
 *   <li>{@code [[name ...]]} escapes a REGISTERED shortcode's open tag and
 *       {@code [[/name]]} its closer: they render as the literal text
 *       {@code [name ...]} / {@code [/name]} and play no structural role --
 *       the way to mention shortcode syntax in prose.</li>
 *   <li>Anything that is not a registered shortcode -- unknown names,
 *       unmatched brackets, malformed tags -- passes through completely
 *       unchanged. That keeps this expander forward-compatible: text written
 *       today with a Wave 2/3 shortcode renders as-is until the handler
 *       ships.</li>
 * </ul>
 *
 * <p>Expansion runs UNCONDITIONALLY in both entry render paths
 * ({@code WeblogEntry#render} and
 * {@code PluginManagerImpl#applyWeblogEntryPlugins}), before
 * {@code HTMLSanitizer.conditionallySanitize} -- shortcodes are not
 * opt-in-per-entry the way named {@code WeblogEntryPlugin}s are (deliberate
 * contract change; see docs/superpowers/plans/2026-08-01-stage2-wave1-media-seo.md).
 *
 * <p>Caching: expansion is deterministic for a given entry text and media
 * metadata, so it is safe inside the whole-page render caches
 * (WeblogPageCache and friends) -- those are already invalidated via the
 * weblog's lastModified whenever content or media change.
 */
public final class ShortcodeExpander {

    private static final Logger log = LoggerFactory.getLogger(ShortcodeExpander.class);

    /**
     * How deep body expansion may recurse. Nesting beyond this leaves the
     * inner shortcodes unexpanded rather than risking a runaway.
     */
    private static final int MAX_NESTING_DEPTH = 10;

    /**
     * An opening (or self-closing) shortcode tag: {@code [name attrs]},
     * {@code [name attrs /]}, or the escaped {@code [[name attrs]}] prefix
     * (group 1 captures the doubled bracket). The attribute section is
     * quote-aware: a {@code ]} inside a complete double- or single-quoted
     * value stays part of the value ({@code caption="Paris [2023]"}) rather
     * than ending the tag, while an unquoted (or unterminated-quote)
     * {@code ]} still ends it -- which is what keeps an unterminated
     * {@code [name} from swallowing the rest of the text. Group 4 catches
     * the no-attribute self-close ({@code [name/]}); a trailing {@code /}
     * after attributes lands inside group 3 and is handled by
     * {@link #isSelfClosing}.
     *
     * <p>Group 3 is written as runs of plain characters between quoted values,
     * all possessive, rather than as one alternation repeated per character.
     * {@code java.util.regex} recurses once per iteration of a repeated group,
     * so the per-character form overflowed a request thread's stack on a
     * couple of thousand characters of unclosed {@code [word ...} prose. The
     * two forms match the same text: a quoted value has exactly one possible
     * end, and nothing after group 3 can match inside it, so the possessive
     * quantifiers give up no match the greedy ones could have found.
     */
    private static final Pattern OPEN_TAG = openTagPattern();

    /**
     * {@link #OPEN_TAG}, compiled in a method of its own only so that the
     * REDOS review below is attached to this one pattern rather than to the
     * whole class: a suppression cannot sit on a static initializer.
     */
    @SuppressFBWarnings(
            value = "REDOS",
            justification = "Every repetition is possessive and the alternatives start on different "
                    + "characters (a plain run excludes both quotes), so nothing backtracks for input to "
                    + "multiply; ShortcodeExpanderTest feeds it 100,000 characters of unclosed prose "
                    + "and 20,000 quoted values.")
    private static Pattern openTagPattern() {
        return Pattern.compile("\\[(\\[?)([a-zA-Z][\\w-]*)"
                + "((?:\\s[^\\]\"']*+(?:(?:\"[^\"]*+\"|'[^']*+')[^\\]\"']*+)*+)?)(/?)\\]");
    }

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))");

    private final Map<String, ShortcodeHandler> handlers;

    public ShortcodeExpander(List<ShortcodeHandler> handlerList) {
        Map<String, ShortcodeHandler> byName = new LinkedHashMap<>();
        for (ShortcodeHandler handler : handlerList) {
            byName.put(handler.getName().toLowerCase(Locale.ROOT), handler);
        }
        this.handlers = Collections.unmodifiableMap(byName);
    }

    /**
     * The built-in registry: every shipped shortcode, in the order the
     * editor's insert menu lists them. ([pin], [q], and [a] are deliberately
     * NOT registered: they are child tags that MapPins/FaqBlocks parse out of
     * the [map]/[faq] bodies.)
     *
     * <p>This used to be a static {@code DEFAULT} instance; the handlers that
     * resolve media ([image], [gallery], [map auto=..]) reached the business
     * tier through the static service locator. They now take what they use,
     * so the registry is built once by {@code WebloggerBeanConfig} with the
     * tier's own collaborators and reached through
     * {@link org.apache.roller.weblogger.business.EntryRenderer}; tests build
     * one over mocks.
     *
     * @param weblogger  the facade the media-rendering handlers wrap files
     *                   with (url strategy + media manager); may be a lazy
     *                   proxy -- it is not dereferenced here
     * @param mediaFiles the media manager [map auto=..] resolves directories
     *                   through
     */
    public static ShortcodeExpander builtIn(Weblogger weblogger, MediaFileManager mediaFiles) {
        return new ShortcodeExpander(List.of(new ImageShortcode(weblogger),
                new GalleryShortcode(weblogger), new MapShortcode(mediaFiles),
                new CtaShortcode(), new FaqShortcode(), new VideoShortcode(),
                new ContactShortcode(), new SubscribeShortcode()));
    }

    /**
     * What the entry editor should offer for the registered shortcodes, in
     * registration order. The editor's insert menu is built from this, so the
     * shortcodes an author can insert are by construction the ones that
     * actually render.
     */
    public List<ShortcodeCard> cards() {
        return handlers.values().stream().map(ShortcodeHandler::getCard).toList();
    }

    /**
     * Expands every registered shortcode in {@code text}. Null-safe; returns
     * {@code text} itself when there is nothing to do. A handler that throws
     * or returns null leaves its shortcode in the output exactly as written.
     */
    public String expand(ShortcodeContext content, String text) {
        return expand(content, text, ShortcodeReads.IGNORED, 0);
    }

    /**
     * {@link #expand(ShortcodeContext, String)}, telling {@code reads} the
     * owner of everything a handler looked up beyond the content's weblog.
     */
    public String expand(ShortcodeContext content, String text, ShortcodeReads reads) {
        return expand(content, text, reads, 0);
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a shortcode name to look up one of Roller's registered handlers, "
                    + "whose names are ASCII; it chooses only which built-in renders the author's content, "
                    + "and EntryRenderer's sanitizing step still follows.")
    private String expand(ShortcodeContext content, String text, ShortcodeReads reads, int depth) {
        if (text == null || handlers.isEmpty() || depth > MAX_NESTING_DEPTH
                || text.indexOf('[') < 0) {
            return text;
        }

        Matcher matcher = OPEN_TAG.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean expandedAnything = false;

        while (last < text.length() && matcher.find(last)) {
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            ShortcodeHandler handler = handlers.get(name);
            if (handler == null) {
                // Unknown shortcode: pass through unchanged (forward-compat).
                expandedAnything |= appendPlainText(out, text, last, matcher.start());
                out.append(text, matcher.start(), matcher.end());
                last = matcher.end();
                continue;
            }

            expandedAnything |= appendPlainText(out, text, last, matcher.start());

            boolean doubledBracket = !matcher.group(1).isEmpty();
            if (doubledBracket && matcher.end() < text.length()
                    && text.charAt(matcher.end()) == ']') {
                // [[name ...]] is the escape form: emit the literal [name ...]
                out.append(text, matcher.start() + 1, matcher.end());
                last = matcher.end() + 1;
                expandedAnything = true;
                continue;
            }
            if (doubledBracket) {
                // "[[name]" with no closing pair: the first bracket is just text.
                out.append('[');
            }
            int tagStart = matcher.start() + (doubledBracket ? 1 : 0);

            boolean selfClosing = isSelfClosing(matcher.group(3), matcher.group(4));
            String attributeText = matcher.group(3);
            if (selfClosing) {
                // strip the explicit self-close slash so it never reads as
                // part of a bare attribute value
                String stripped = attributeText.stripTrailing();
                if (stripped.endsWith("/")) {
                    attributeText = stripped.substring(0, stripped.length() - 1);
                }
            }

            // Body: text up to the MATCHING [/name] (nested same-name opens
            // are balanced), unless written self-closing.
            String body = null;
            int consumedEnd = matcher.end();
            if (!selfClosing) {
                int closerAt = findMatchingCloser(text, name, matcher.end());
                if (closerAt >= 0) {
                    body = expand(content, text.substring(matcher.end(), closerAt), reads, depth + 1);
                    consumedEnd = closerAt + name.length() + 3; // "[/" + name + "]"
                }
            }

            String replacement = null;
            try {
                replacement = handler.render(parseAttributes(attributeText), body, content, reads);
            } catch (Exception e) {
                log.warn("Shortcode [{}] handler failed; leaving the "
                        + "shortcode text as the author wrote it", name, e);
                // a degraded render, not the render: never kept
                reads.readFrom(null);
            }
            if (replacement != null) {
                out.append(replacement);
                expandedAnything = true;
            } else {
                out.append(text, tagStart, consumedEnd);
            }
            last = consumedEnd;
        }

        expandedAnything |= appendPlainText(out, text, last, text.length());
        if (!expandedAnything) {
            return text;
        }
        return out.toString();
    }

    /**
     * Appends the plain-text span {@code [from, to)} to {@code out},
     * unescaping {@code [[/name]]} to the literal {@code [/name]} for
     * registered names on the way (the closer counterpart of the
     * {@code [[name ...]]} escape, which the main loop handles because it
     * matches OPEN_TAG). Returns whether anything was unescaped.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a shortcode name to check it against Roller's registered ASCII "
                    + "handler names, only to decide whether an escaped closer in the author's text is "
                    + "unescaped.")
    private boolean appendPlainText(StringBuilder out, String text, int from, int to) {
        boolean changed = false;
        int pos = from;
        int idx;
        while ((idx = text.indexOf("[[/", pos)) >= 0 && idx < to) {
            int nameStart = idx + 3;
            int close = text.indexOf(']', nameStart);
            if (close >= 0 && close + 1 < to && text.charAt(close + 1) == ']'
                    && handlers.containsKey(
                            text.substring(nameStart, close).toLowerCase(Locale.ROOT))) {
                out.append(text, pos, idx);
                out.append("[/").append(text, nameStart, close).append(']');
                pos = close + 2;
                changed = true;
            } else {
                out.append(text, pos, idx + 1);
                pos = idx + 1;
            }
        }
        out.append(text, pos, to);
        return changed;
    }

    /**
     * A tag is self-closing when it ends {@code /]}: with no attributes the
     * slash is captured by OPEN_TAG's group 4, after attributes it is the
     * last non-blank character of group 3.
     */
    private static boolean isSelfClosing(String attributeText, String slashGroup) {
        return !slashGroup.isEmpty() || attributeText.stripTrailing().endsWith("/");
    }

    /**
     * Finds the {@code [/name]} that closes an open tag whose body starts at
     * {@code from}. The scan walks TAGS, not raw substrings:
     *
     * <ul>
     *   <li>Any well-formed open tag of ANY name is skipped as one opaque
     *       span, so a closer look-alike inside its quoted attribute value
     *       ({@code [b caption="[/a]"]}) never counts as a closer.</li>
     *   <li>Nested same-name opens are balanced so
     *       {@code [a]..[a]..[/a]..[/a]} pairs inside-out like HTML;
     *       self-closing and escaped ({@code [[name ...]]}) inner opens do
     *       not consume a closer, and escaped closers ({@code [[/name]]})
     *       are not closers at all.</li>
     *   <li>The body extends GREEDILY to the last closer in the run before
     *       the next body-form same-name open (or end of text). A bare
     *       {@code [/name]} in body prose is structurally a closer, so when
     *       one appears before the author's real closer, the greedy rule
     *       keeps the prose mention inside the body as literal text rather
     *       than truncating the body there and leaking the real closer as
     *       raw output. Stopping at the next same-name open keeps sequential
     *       pairs ({@code [a]x[/a] .. [a]y[/a]}) pairing first-with-first.</li>
     *   <li>When opens outnumber closers a strict pairing would leave THIS
     *       tag unclosed even though the author wrote a closer -- fallback:
     *       the tag takes the last closer seen, and the leftover inner opens
     *       (re-scanned when the body is recursively expanded, where their
     *       closers are absent) degrade to self-closing.</li>
     * </ul>
     *
     * Matching is case-insensitive, like tag names everywhere else.
     * Returns -1 only when no closer exists at all.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares shortcode names case-insensitively to pair an author's opening tag "
                    + "with its closer; it decides only how the author's own text nests.")
    private static int findMatchingCloser(String text, String name, int from) {
        String closer = "[/" + name + "]";
        int depth = 1;
        int candidate = -1;   // best closer so far once the tag is balanced
        int lastCloser = -1;  // unbalanced-opens fallback
        int pos = from;

        int idx;
        while ((idx = text.indexOf('[', pos)) >= 0) {
            if (text.regionMatches(true, idx, closer, 0, closer.length())) {
                boolean escapedCloser = idx > 0 && text.charAt(idx - 1) == '['
                        && idx + closer.length() < text.length()
                        && text.charAt(idx + closer.length()) == ']';
                if (escapedCloser) {
                    // [[/name]]: a literal mention, not structure.
                    pos = idx + closer.length() + 1;
                    continue;
                }
                lastCloser = idx;
                if (depth > 0) {
                    depth--;
                }
                if (depth == 0) {
                    candidate = idx;
                }
                pos = idx + closer.length();
                continue;
            }

            Matcher open = OPEN_TAG.matcher(text).region(idx, text.length());
            if (open.lookingAt()) {
                // A well-formed tag (any name): skip its whole span so its
                // quoted attribute values are opaque to this scan.
                boolean escapedOpen = !open.group(1).isEmpty()
                        && open.end() < text.length()
                        && text.charAt(open.end()) == ']';
                boolean opensABody = !escapedOpen
                        && open.group(2).equalsIgnoreCase(name)
                        && !isSelfClosing(open.group(3), open.group(4));
                if (opensABody) {
                    if (depth == 0) {
                        // This tag's run of closers is over; the ones that
                        // follow belong to the new same-name open.
                        return candidate;
                    }
                    depth++;
                }
                pos = escapedOpen ? open.end() + 1 : open.end();
                continue;
            }

            pos = idx + 1;
        }
        return candidate >= 0 ? candidate : lastCloser;
    }

    private static Map<String, String> parseAttributes(String attributeText) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (attributeText == null || attributeText.isBlank()) {
            return attributes;
        }
        Matcher matcher = ATTRIBUTE.matcher(attributeText);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4);
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }
}
