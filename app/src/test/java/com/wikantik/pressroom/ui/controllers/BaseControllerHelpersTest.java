package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import java.util.Locale;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * The configuration lookups every controller shares (static config wins over
 * the runtime, database-backed one; an unknown key reads as itself or false),
 * and the flash helpers that must accumulate rather than overwrite.
 */
class BaseControllerHelpersTest {

    private static final class Probe extends BaseController {
        String prop(String key) {
            return getProp(key);
        }

        void flashMessage(RedirectAttributesModelMap redirect, String key, String param, MockHttpServletRequest request) {
            addFlashMessage(redirect, key, param, request);
        }

        void flashError(RedirectAttributesModelMap redirect, String key, String param, MockHttpServletRequest request) {
            addFlashError(redirect, key, param, request);
        }

        boolean errorsPresent(ExtendedModelMap model) {
            return hasErrors(model);
        }

        void violation(ExtendedModelMap model, RuleViolation violation, MockHttpServletRequest request) {
            addError(model, violation, request);
        }
    }

    private static Probe probe() {
        Probe probe = new Probe();
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        probe.messageSource = messages;
        return probe;
    }

    @Test
    void aStaticConfigValueWinsOverTheRuntimeOne() {
        try (MockedStatic<WebloggerConfig> staticConfig = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class)) {
            staticConfig.when(() -> WebloggerConfig.getProperty("k")).thenReturn("from-file");
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("k")).thenReturn("from-db");

            assertEquals("from-file", probe().prop("k"));
        }
    }

    @Test
    void theRuntimeValueIsUsedWhenTheStaticOneIsAbsentAndAnUnknownKeyIsItsOwnName() {
        try (var _ = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class)) {
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("only.runtime")).thenReturn("from-db");

            assertEquals("from-db", probe().prop("only.runtime"));
            assertEquals("nowhere", probe().prop("nowhere"));
        }
    }

    @Test
    void flashMessagesAccumulateAcrossCallsRatherThanReplaceEachOther() {
        Probe probe = probe();
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        probe.flashMessage(redirect, "first.key", "a", request);
        probe.flashMessage(redirect, "second.key", "b", request);
        probe.flashError(redirect, "bad.key", "c", request);

        assertEquals(List.of("first.key", "second.key"), List.copyOf((List<?>) redirect.getFlashAttributes().get("messages")));
        assertEquals(1, ((List<?>) redirect.getFlashAttributes().get("errors")).size());
    }

    @Test
    void anEmptyErrorListIsNotAnError() {
        ExtendedModelMap model = new ExtendedModelMap();
        Probe probe = probe();

        assertFalse(probe.errorsPresent(model));
        model.addAttribute("errors", new java.util.ArrayList<String>());
        assertFalse(probe.errorsPresent(model));
        model.addAttribute("errors", List.of("x"));
        assertTrue(probe.errorsPresent(model));
    }

    /**
     * A rule's {@link RuleViolation.Label} argument is the field's label in the reader's language, the
     * bundle's own text and so not escaped; every other argument is a raw value and is escaped.
     */
    @Test
    void aLabelArgumentIsResolvedUnescapedAndARawOneEscaped() {
        Probe probe = probe();
        StaticMessageSource messages = (StaticMessageSource) probe.messageSource;
        messages.addMessage("label.key", Locale.ENGLISH, "Lien de réservation");
        messages.addMessage("rule.key", Locale.ENGLISH, "{0} | {1} | {2}");
        ExtendedModelMap model = new ExtendedModelMap();

        probe.violation(model, RuleViolation.invalid("f", "rule.key",
                List.of(new RuleViolation.Label("label.key"), "<b>", 255), ProblemCode.FIELD_TOO_LONG, "f", "255"),
                new MockHttpServletRequest());

        assertEquals(List.of("Lien de réservation | &lt;b&gt; | 255"), model.getAttribute("errors"));
    }

    /**
     * A number argument stays a number, so the message formats it in the
     * reader's locale, grouped (a strand of 1,000 parts, the pin limit);
     * a string argument is still escaped (0.16.3: every argument had become
     * an escaped string, so 1000 lost its grouping).
     */
    @Test
    void aNumberArgumentIsFormattedGroupedAndAStringOneStillEscaped() {
        Probe probe = probe();
        StaticMessageSource messages = (StaticMessageSource) probe.messageSource;
        messages.addMessage("rule.key", Locale.ENGLISH, "{0} | {1} | {2}");
        ExtendedModelMap model = new ExtendedModelMap();

        probe.violation(model, RuleViolation.invalid("f", "rule.key", List.of(1000L, 12345, "1000 <b>"),
                ProblemCode.STRAND_HAS_PARTS, "1000", "{id}"), new MockHttpServletRequest());

        assertEquals(List.of("1,000 | 12,345 | 1000 &lt;b&gt;"), model.getAttribute("errors"));
    }
}
