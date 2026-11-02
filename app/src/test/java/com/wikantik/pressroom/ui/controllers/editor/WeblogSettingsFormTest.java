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
 * no analytics fields: not on the bean, not in the page, not in the messages.
 */
class WeblogSettingsFormTest {

    @Test
    void settingsFormHasNoAnalyticsFields() throws Exception {
        String jsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp"));
        assertFalse(jsp.contains("analyticsSiteId"));
        assertFalse(jsp.contains("analyticsShareUrl"));
        assertFalse(jsp.contains("settings-analytics"));
        assertFalse(jsp.contains("websiteSettings.analytics"));

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
