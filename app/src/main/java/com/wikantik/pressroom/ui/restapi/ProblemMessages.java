package com.wikantik.pressroom.ui.restapi;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Renders a keyed problem's text: in English from the base bundle (what
 * {@code ApiException.getMessage()} reads, for logs and for MCP), or in the
 * caller's language through the {@code MessageSource}.
 *
 * <p>The language comes from the {@code Accept-Language} header alone (see
 * {@link #localeFor}), never {@code request.getLocale()}: Tomcat answers that
 * with the JVM default when the header is absent. English renders with
 * {@link Locale#ROOT}, because a resource bundle lookup for a locale with no
 * bundle of its own (English has none: the base bundle is English) falls
 * back to the JVM default locale, and ROOT is the one locale that does not.
 */
public final class ProblemMessages {

    /**
     * The languages a bundle ships for, English first so that a wildcard
     * range ({@code *}) chooses it. The order of the rest does not matter:
     * the caller's ranges are tried one at a time, in q order.
     */
    private static final List<String> SHIPPED = List.of("en", "de", "es", "fr", "ja", "ko", "ru", "zh-CN");

    private static final String ENGLISH = "en";

    private ProblemMessages() {
    }

    /**
     * A message source on {@code ApplicationResources}, as the application's
     * own {@code messageSource} bean is: what a writer built without one
     * (a unit test, a static writer) renders from, so it localises as
     * production does.
     */
    public static MessageSource applicationResources() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("ApplicationResources");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    /** One text, rendered for one request. */
    @FunctionalInterface
    interface Renderer {
        String render(ProblemCode code, List<String> args);
    }

    /** The base bundle, read once: the English every keyed problem's {@code getMessage()} reads. */
    private static final class Base {
        private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("ApplicationResources", Locale.ROOT,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /**
     * {@code code}'s English with {@code args}. Every code's key is in the
     * base bundle ({@code ProblemCodeCatalogTest}).
     */
    static String english(ProblemCode code, List<String> args) {
        String pattern = Base.BUNDLE.getString(code.key());
        return formats(code, args) ? new MessageFormat(pattern, Locale.ROOT).format(args.toArray()) : pattern;
    }

    /**
     * {@code MessageFormat} runs only for a code that takes arguments and was
     * given some: an arity-0 text is used as written, as the JSPs use theirs.
     */
    private static boolean formats(ProblemCode code, List<String> args) {
        return code.arity() > 0 && !args.isEmpty();
    }

    /**
     * Renders through {@code messages} in {@code locale}. A key the source
     * lacks (it cannot lack one the base bundle has, unless a test hands it a
     * narrower source) falls back to the English: the text is display, and
     * the status and code have already been decided.
     */
    static Renderer renderer(MessageSource messages, Locale locale) {
        return (code, args) -> {
            try {
                return messages.getMessage(code.key(), formats(code, args) ? args.toArray() : null, locale);
            } catch (NoSuchMessageException e) {
                return english(code, args);
            }
        };
    }

    /**
     * The locale to render in for an {@code Accept-Language} header: the
     * header's ranges in q order (q=0 excluded), each matched against the
     * shipped languages by RFC 4647 basic filtering ({@code zh} matches
     * {@code zh-CN}, {@code *} matches English), then, failing that, by
     * lookup, which drops the range's subtags from the end so that a regional
     * range reaches its language ({@code fr-CA} matches {@code fr}). The
     * first range that matches wins. {@link Locale#ROOT} when none does, when
     * the match is English, or when the header is absent or will not parse.
     */
    public static Locale localeFor(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.ROOT;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException malformed) {
            return Locale.ROOT;
        }
        for (Locale.LanguageRange range : ranges) {
            String tag = match(List.of(range));
            if (tag != null) {
                Locale matched = Locale.forLanguageTag(tag);
                return ENGLISH.equals(matched.getLanguage()) ? Locale.ROOT : matched;
            }
        }
        return Locale.ROOT;
    }

    private static String match(List<Locale.LanguageRange> range) {
        List<String> filtered = Locale.filterTags(range, SHIPPED, Locale.FilteringMode.IGNORE_EXTENDED_RANGES);
        return filtered.isEmpty() ? Locale.lookupTag(range, SHIPPED) : filtered.get(0);
    }
}
