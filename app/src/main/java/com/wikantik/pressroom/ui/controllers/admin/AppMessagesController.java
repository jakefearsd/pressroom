package com.wikantik.pressroom.ui.controllers.admin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The resolved {@code ApplicationResources} bundle as one JSON object, for the
 * React admin's {@code t()}: the base keys with the locale's overrides on top,
 * the base alone for a locale that has no file. The URL carries the application
 * version ({@code v}), so the answer is cacheable for a year and a release
 * changes it. Nothing here is per-user, so no login is needed.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class AppMessagesController {

    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    /** The bundle for {@code locale} (a language tag, {@code zh_CN} also accepted). */
    @GetMapping("/app-messages.rol")
    @ResponseBody
    public ResponseEntity<Map<String, String>> messagesFor(
            @RequestParam(name = "locale", required = false, defaultValue = "") String locale,
            @RequestParam(name = "v", required = false, defaultValue = "") String version) {
        ResponseEntity<Map<String, String>> found =
                messages(Locale.forLanguageTag(locale.replace('_', '-')));
        return ResponseEntity.ok().contentType(JSON_UTF8)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .body(found.getBody());
    }

    /** The merged bundle for {@code locale}; no fallback to the JVM's own default locale. */
    ResponseEntity<Map<String, String>> messages(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle("ApplicationResources", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Map<String, String> all = new TreeMap<>();
        for (String key : bundle.keySet()) {
            all.put(key, bundle.getString(key));
        }
        return ResponseEntity.ok(all);
    }
}
