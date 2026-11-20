package com.wikantik.pressroom.config.runtime;

import java.util.Optional;
import java.util.regex.Pattern;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;

/**
 * The declared type of a runtime property, and the one check of a value
 * against it. The Global Config form and {@code PATCH /v1/admin/config} each
 * word the problem their own way (a message bundle key there, a sentence
 * here), but whether a value is acceptable is decided once, here.
 */
public enum PropertyType {
    BOOLEAN("boolean"), INTEGER("integer"), FLOAT("float"), STRING("string");

    private static final Pattern TRUE_OR_FALSE = Pattern.compile("true|false", Pattern.CASE_INSENSITIVE);

    /** Why a value is refused. */
    public sealed interface TypeProblem permits Unparsed, Range { }

    /** The value is not of the declared shape. */
    public record Unparsed() implements TypeProblem { }

    /** An integer outside the inclusive range its setting allows. */
    public record Range(int min, int max) implements TypeProblem { }

    /** The one {@link Unparsed}. */
    public static final TypeProblem UNPARSED = new Unparsed();

    private final String declared;

    PropertyType(String declared) {
        this.declared = declared;
    }

    /** The type a definition's {@code type} attribute names; text and anything unknown are strings. */
    public static PropertyType of(String declared) {
        for (PropertyType type : values()) {
            if (type.declared.equals(declared)) {
                return type;
            }
        }
        return STRING;
    }

    /**
     * What is wrong with {@code value} as the property {@code name}, or
     * empty. A non-null value is assumed.
     */
    public Optional<TypeProblem> check(String name, String value) {
        return switch (this) {
            case BOOLEAN -> TRUE_OR_FALSE.matcher(value).matches() ? Optional.empty() : Optional.of(UNPARSED);
            case INTEGER -> checkInteger(name, value);
            case FLOAT -> {
                try {
                    Float.parseFloat(value);
                    yield Optional.empty();
                } catch (NumberFormatException e) {
                    yield Optional.of(UNPARSED);
                }
            }
            case STRING -> Optional.empty();
        };
    }

    private static Optional<TypeProblem> checkInteger(String name, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Optional.of(UNPARSED);
        }
        return DiscoverySettings.rangeError(name, parsed).isPresent()
                ? Optional.of(new Range(DiscoverySettings.min(name), DiscoverySettings.max(name)))
                : Optional.empty();
    }
}
