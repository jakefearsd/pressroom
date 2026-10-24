package org.apache.roller.weblogger.business;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.RuntimeConfigAttachment;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The facts behind every footer: release date, copyright line, privacy link. */
class ColophonTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);

    private PropertiesManager properties;
    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private WeblogManager weblogManager;
    private URLStrategy urls;
    private String previousRelative;

    @BeforeEach
    void setUp() throws WebloggerException {
        previousRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
        properties = mock(PropertiesManager.class);
        runtimeConfig = RuntimeConfigAttachment.of(properties);
        weblogger = mock(Weblogger.class);
        weblogManager = mock(WeblogManager.class);
        urls = mock(URLStrategy.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getVersion()).thenReturn("0.4.0");
        when(weblogger.getRevision()).thenReturn("abcdef0123456");
        when(weblogger.getBuildTime()).thenReturn(
                String.valueOf(Instant.parse("2026-10-25T03:00:00Z").toEpochMilli()));
        given("site.name", "Site Name");
    }

    @AfterEach
    void tearDown() {
        runtimeConfig.close();
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelative);
    }

    private void given(String name, String value) throws WebloggerException {
        when(properties.getProperty(name)).thenReturn(new RuntimeConfigProperty(name, value));
    }

    private static Weblog weblog(String handle, String name, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        weblog.setCustomDomain(customDomain);
        return weblog;
    }

    @Test
    void releaseDateIsPacificAcrossUtcMidnight() {
        assertEquals("2026-10-24", Colophon.releaseDate(
                String.valueOf(Instant.parse("2026-10-25T03:00:00Z").toEpochMilli())));
    }

    @Test
    void releaseDateNullForUnknown() {
        assertNull(Colophon.releaseDate("UNKNOWN"));
    }

    @Test
    void releaseDateNullForUnfilteredPlaceholder() {
        assertNull(Colophon.releaseDate("${timestamp}"));
    }

    @Test
    void releaseDateNullForNull() {
        assertNull(Colophon.releaseDate(null));
    }

    @Test
    void versionRevisionAndDateComeFromTheFacade() {
        Colophon c = Colophon.of(weblogger, null, NOW);
        assertEquals("0.4.0", c.version());
        assertEquals("abcdef0", c.shortRevision());
        assertEquals("2026-10-24", c.releaseDate());
    }

    @Test
    void customCopyrightIsKept() {
        Weblog weblog = weblog("blog", "Blog", null);
        weblog.setCopyright("© 2024–2026 Maiia");
        Colophon c = Colophon.of(weblogger, weblog, NOW);
        assertEquals("© 2024–2026 Maiia", c.copyrightText());
        assertTrue(c.hasCustomCopyright());
    }

    @Test
    void blankCopyrightIsTreatedAsUnset() {
        Weblog weblog = weblog("blog", "Blog", null);
        weblog.setCopyright("   ");
        Colophon c = Colophon.of(weblogger, weblog, NOW);
        assertNull(c.copyrightText());
        assertFalse(c.hasCustomCopyright());
        assertEquals("Blog", c.copyrightHolder());
    }

    @Test
    void defaultCopyrightUsesBlogNameAndPacificYear() {
        Clock newYearUtc = Clock.fixed(Instant.parse("2027-01-01T05:00:00Z"), ZoneOffset.UTC);
        Colophon c = Colophon.of(weblogger, weblog("blog", "Wandering Pacific", null), newYearUtc);
        assertEquals("Wandering Pacific", c.copyrightHolder());
        assertEquals(2026, c.copyrightYear());
        assertNull(c.copyrightText());
        assertFalse(c.hasCustomCopyright());
    }

    @Test
    void adminColophonUsesSiteName() {
        assertEquals("Site Name", Colophon.of(weblogger, null, NOW).copyrightHolder());
    }

    @Test
    void privacyUrlNullWhenSettingBlank() throws WebloggerException {
        given("site.privacy.page", "  ");
        assertNull(Colophon.of(weblogger, null, NOW).privacyUrl());
    }

    @Test
    void privacyUrlBuiltFromFrontpageBlog() throws WebloggerException {
        Weblog front = weblog("front", "Front", null);
        given("site.privacy.page", "privacy");
        given("site.frontpage.weblog.handle", "front");
        when(weblogManager.getWeblogByHandle("front")).thenReturn(front);
        when(urls.getWeblogStaticPageURL(front, "privacy", true)).thenReturn("https://x.test/front/privacy");
        assertEquals("https://x.test/front/privacy", Colophon.of(weblogger, null, NOW).privacyUrl());
    }

    @Test
    void privacyUrlIsAbsoluteForAVirtualHostBlog() throws WebloggerException {
        Weblog front = weblog("front", "Front", "vhost.example.com");
        given("site.privacy.page", "privacy");
        given("site.frontpage.weblog.handle", "front");
        when(weblogManager.getWeblogByHandle("front")).thenReturn(front);
        when(weblogger.getUrlStrategy()).thenReturn(new MultiWeblogURLStrategy());
        assertEquals("https://vhost.example.com/privacy",
                Colophon.of(weblogger, weblog("other", "Other", null), NOW).privacyUrl());
    }

    @Test
    void privacyUrlNullWhenFrontpageHandleNoLongerResolves() throws WebloggerException {
        given("site.privacy.page", "privacy");
        given("site.frontpage.weblog.handle", "gone");
        when(weblogManager.getWeblogByHandle("gone")).thenReturn(null);
        assertNull(Colophon.of(weblogger, null, NOW).privacyUrl());
    }

    @Test
    void privacyUrlNullWhenNoFrontpageHandle() throws WebloggerException {
        given("site.privacy.page", "privacy");
        assertNull(Colophon.of(weblogger, null, NOW).privacyUrl());
    }

    @Test
    void privacyUrlNullWhenLookupThrows() throws WebloggerException {
        given("site.privacy.page", "privacy");
        given("site.frontpage.weblog.handle", "front");
        when(weblogManager.getWeblogByHandle(anyString())).thenThrow(new WebloggerException("down"));
        assertNull(Colophon.of(weblogger, null, NOW).privacyUrl());
    }

    @Test
    void ofNeverThrowsWhenRuntimeConfigThrows() throws WebloggerException {
        when(properties.getProperty(anyString())).thenThrow(new IllegalStateException("boom"));
        Colophon admin = Colophon.of(weblogger, null, NOW);
        assertEquals("0.4.0", admin.version());
        assertEquals("2026-10-24", admin.releaseDate());
        assertNull(admin.privacyUrl());
        assertEquals("", admin.copyrightHolder());
        assertEquals("Blog", Colophon.of(weblogger, weblog("b", "Blog", null), NOW).copyrightHolder());
    }

    @Test
    void ofNeverThrowsWhenTheFacadeThrows() {
        when(weblogger.getVersion()).thenThrow(new IllegalStateException("boom"));
        when(weblogger.getRevision()).thenThrow(new IllegalStateException("boom"));
        when(weblogger.getBuildTime()).thenThrow(new IllegalStateException("boom"));
        Colophon c = Colophon.of(weblogger, null, NOW);
        assertEquals("", c.version());
        assertEquals("", c.shortRevision());
        assertNull(c.releaseDate());
    }

    @Test
    void shortRevisionHandlesShortOrMissingRevision() {
        when(weblogger.getRevision()).thenReturn("abc");
        assertEquals("abc", Colophon.of(weblogger, null, NOW).shortRevision());
        when(weblogger.getRevision()).thenReturn(null);
        assertEquals("", Colophon.of(weblogger, null, NOW).shortRevision());
    }

    /**
     * Pre-bootstrap pages (install, upgrade, database error) have no
     * Weblogger. The version comes from the packaged resource and nothing
     * touches runtime config or the database.
     */
    @Test
    void nullWebloggerReadsTheVersionFromThePackagedProperties() throws Exception {
        java.util.Properties packaged = new java.util.Properties();
        try (var in = Colophon.class.getResourceAsStream("/roller-version.properties")) {
            packaged.load(in);
        }
        Colophon c = Colophon.of(null, null, NOW);
        assertEquals(packaged.getProperty("ro.version"), c.version());
        assertFalse(c.version().isEmpty());
        String revision = packaged.getProperty("ro.revision", "");
        assertEquals(revision.length() > 7 ? revision.substring(0, 7) : revision, c.shortRevision());
        assertEquals(Colophon.releaseDate(packaged.getProperty("ro.buildTime")), c.releaseDate());
        assertNull(c.privacyUrl());
        assertNull(c.copyrightText());
        assertEquals("", c.copyrightHolder());
        assertEquals(2026, c.copyrightYear());
    }
}
