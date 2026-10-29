package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
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

        boolean bool(String key) {
            return getBooleanProp(key);
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
    void aBooleanSettingFollowsTheSamePrecedenceAndUnknownMeansFalse() {
        try (MockedStatic<WebloggerConfig> staticConfig = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class)) {
            staticConfig.when(() -> WebloggerConfig.getProperty("static.off")).thenReturn("false");
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("static.off")).thenReturn("true");
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("runtime.on")).thenReturn("TRUE");
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("runtime.junk")).thenReturn("yes please");

            Probe probe = probe();
            assertFalse(probe.bool("static.off"), "the static false must not be overridden by the runtime true");
            assertTrue(probe.bool("runtime.on"));
            assertFalse(probe.bool("runtime.junk"), "anything but true is false");
            assertFalse(probe.bool("unknown"));
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
}
