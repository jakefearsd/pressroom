package com.wikantik.pressroom.ui.controllers.editor;

import org.junit.jupiter.api.Test;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blogs are registered with Umami automatically, so the Settings form offers
 * no analytics fields: not on the bean, not in the React screen, not in the messages.
 */
class WeblogSettingsFormTest {

    @Test
    void settingsFormHasNoAnalyticsFields() throws Exception {
        String screen = Files.readString(Path.of("frontend/src/admin/screens/WeblogSettings.tsx"));
        assertFalse(screen.contains("analyticsSiteId"));
        assertFalse(screen.contains("analyticsShareUrl"));
        assertFalse(screen.contains("settings-analytics"));
        assertFalse(screen.contains("websiteSettings.analytics"));

        boolean onBean = Arrays.stream(Introspector.getBeanInfo(WeblogConfigBean.class).getPropertyDescriptors())
                .map(PropertyDescriptor::getName)
                .anyMatch(n -> n.startsWith("analytics"));
        assertFalse(onBean, "WeblogConfigBean must carry no analytics property");

        Properties messages = new Properties();
        try (var in = Files.newBufferedReader(Path.of("src/main/resources/ApplicationResources.properties"))) {
            messages.load(in);
        }
        assertTrue(messages.stringPropertyNames().stream().noneMatch(k -> k.startsWith("websiteSettings.analytics")));
    }
}
