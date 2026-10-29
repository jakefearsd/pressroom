package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [video] parses URLs; it never fetches them. Every provider is matched by a
 * known URL shape and the id is validated against a strict character class,
 * so an author cannot smuggle markup through the id and there is no outbound
 * request for anyone to point at an internal address.
 */
class VideoShortcodeTest {

    private final VideoShortcode shortcode = new VideoShortcode();

    private String render(String url) {
        return shortcode.render(Map.of("url", url), null, null);
    }

    @Test
    void aWatchUrlYieldsAYouTubePlaceholder() {
        String html = render("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertTrue(html.contains("data-provider=\"youtube\""), html);
        assertTrue(html.contains("data-video-id=\"dQw4w9WgXcQ\""), html);
        assertTrue(html.contains("class=\"video-embed\""), html);
    }

    /**
     * The watch-URL pattern used to step over each earlier query parameter
     * with a repeated group, which java.util.regex runs by recursion: a few
     * thousand parameters overflowed the stack and took the page down.
     */
    @Test
    void aWatchUrlWithThousandsOfParametersStillYieldsItsId() {
        String html = render("https://www.youtube.com/watch?" + "a=1&".repeat(20_000) + "v=dQw4w9WgXcQ");

        assertTrue(html.contains("data-video-id=\"dQw4w9WgXcQ\""), html);
    }

    @Test
    void aShortYoutuBeUrlYieldsTheSameId() {
        assertTrue(render("https://youtu.be/dQw4w9WgXcQ")
                .contains("data-video-id=\"dQw4w9WgXcQ\""));
    }

    @Test
    void aVimeoUrlYieldsAVimeoPlaceholder() {
        String html = render("https://vimeo.com/123456789");

        assertTrue(html.contains("data-provider=\"vimeo\""), html);
        assertTrue(html.contains("data-video-id=\"123456789\""), html);
    }

    /**
     * No iframe is ever emitted. The sanitizer strips iframes, so emitting one
     * would produce an empty div and a mystery; the theme macro injects the
     * frame client-side on click instead.
     */
    @Test
    void noIframeIsEmitted() {
        assertFalse(render("https://youtu.be/dQw4w9WgXcQ").contains("<iframe"));
    }

    @Test
    void aThumbnailIsEmittedForYouTube() {
        String html = render("https://youtu.be/dQw4w9WgXcQ");

        assertTrue(html.contains("<img"), html);
        assertTrue(html.contains("i.ytimg.com/vi/dQw4w9WgXcQ/"), html);
        assertTrue(html.contains("loading=\"lazy\""),
                "the thumbnail must not block first paint: " + html);
    }

    /** Null means "leave the author's text visible" -- the can't-render signal. */
    @Test
    void anUnknownHostIsLeftAsTheAuthorWroteIt() {
        assertNull(render("https://example.com/video/1"));
    }

    @Test
    void aMissingUrlIsLeftAsTheAuthorWroteIt() {
        assertNull(shortcode.render(Map.of(), null, null));
    }

    /**
     * The id goes straight into an HTML attribute and into a thumbnail URL.
     * Anything outside the provider's own id alphabet is refused outright
     * rather than escaped, because a value that shape is not an id.
     */
    @Test
    void anIdCarryingMarkupIsRefused() {
        assertNull(render("https://youtu.be/abc\"><script>alert(1)</script>"));
    }

    @Test
    void aNonHttpSchemeIsRefused() {
        assertNull(render("javascript:alert(1)"));
    }

    @Test
    void aCaptionIsEscapedAndEmitted() {
        String html = shortcode.render(
                Map.of("url", "https://youtu.be/dQw4w9WgXcQ", "caption", "A & B <x>"),
                null, null);

        assertTrue(html.contains("<figcaption>"), html);
        assertTrue(html.contains("A &amp; B &lt;x&gt;"), html);
        assertFalse(html.contains("<x>"), html);
    }

    @Test
    void theCardIsDiscoverableAndInsertsWorkingSyntax() {
        ShortcodeCard card = shortcode.getCard();

        assertEquals("video", card.name());
        assertTrue(card.snippet().startsWith("[video "), card.snippet());
        assertFalse(card.usesMediaChooser());
        assertFalse(card.snippet().contains("<"), "snippet travels in an HTML attribute");
    }

    @Test
    void theHandlerIsRegisteredInTheDefaultExpander() {
        assertTrue(BuiltInExpanders.withMocks().cards().stream()
                        .anyMatch(c -> "video".equals(c.name())),
                "an unregistered shortcode is undiscoverable in the editor");
    }
}
