package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;

import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [contact] emits a placeholder div, never a form: the sanitizer strips
 * form elements from authored content by design, and #showAudienceAssets
 * builds the real form client-side. Same pattern as [map] and [video].
 */
class ContactShortcodeTest {

    private final ContactShortcode shortcode = new ContactShortcode();

    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() {
        // The endpoint the shortcode emits is built from this -- set it to a
        // known, non-empty value so the assertions below do not depend on
        // whatever some earlier-running test left behind in this shared
        // static field (see URLModelTest for the same discipline).
        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
    }

    private static ShortcodeContext context(String handle) {
        Weblog weblog = handle == null ? null : new Weblog();
        if (weblog != null) {
            weblog.setHandle(handle);
        }
        Weblog finalWeblog = weblog;
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return finalWeblog; }
            @Override public String getSlug() { return "contact"; }
            @Override public String getRawText() { return "[contact]"; }
        };
    }

    @Test
    void emitsAPlaceholderCarryingTheWeblogHandle() {
        String html = shortcode.render(Map.of(), null, context("travelblog"));

        assertTrue(html.contains("class=\"contact-form-slot\""), html);
        assertTrue(html.contains("data-weblog=\"travelblog\""), html);
        assertFalse(html.contains("<form"), "the macro injects the form, not the shortcode");
        assertFalse(html.contains("<input"), html);
    }

    /**
     * The submit endpoint is built here, server-side, from the context path
     * the {@code InitFilter} published -- not guessed by the client script.
     * A client-side guess (an earlier version scanned the page for a
     * stylesheet link) breaks the moment the page's first stylesheet is
     * something other than {@code /roller-ui/...} (a weblog's own theme CSS,
     * a webjar), which is every real page: it posts to the wrong origin with
     * no visible cause.
     */
    @Test
    void theEndpointCarriesTheContextPathServerSide() {
        String html = shortcode.render(Map.of(), null, context("travelblog"));

        assertTrue(html.contains("data-endpoint=\"/roller/roller-ui/rendering/contact.rol\""), html);
    }

    /**
     * The contact endpoint refuses the site space (there is no site inbox
     * yet), so a form slot on a site page would build a form whose every
     * message is lost. The author and the reader see a notice instead.
     */
    @Test
    void onTheSiteSpaceItShowsANoticeAndNoForm() {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        site.setLocale("en_US");
        ShortcodeContext onSite = new ShortcodeContext() {
            @Override public Weblog getWeblog() { return site; }
            @Override public String getSlug() { return "about"; }
            @Override public String getRawText() { return "[contact]"; }
        };

        String html = shortcode.render(Map.of(), null, onSite);

        assertFalse(html.contains("contact-form-slot"), html);
        assertFalse(html.contains("data-endpoint"), html);
        assertTrue(html.contains("class=\"contact-form-unavailable\""), html);
        assertTrue(html.contains("Contact forms are not available on site pages yet."), html);
    }

    @Test
    void withoutAWeblogItLeavesTheAuthorsTextVisible() {
        assertNull(shortcode.render(Map.of(), null, context(null)));
    }

    @Test
    void theCardIsDiscoverable() {
        ShortcodeCard card = shortcode.getCard();
        assertEquals("contact", card.name());
        assertTrue(card.snippet().startsWith("[contact"), card.snippet());
        assertFalse(card.snippet().contains("<"));
    }

    @Test
    void isRegisteredInTheDefaultExpander() {
        assertTrue(BuiltInExpanders.withMocks().cards().stream()
                .anyMatch(c -> "contact".equals(c.name())));
    }
}
