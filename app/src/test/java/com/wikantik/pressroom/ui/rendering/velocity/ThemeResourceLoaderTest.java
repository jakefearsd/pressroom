/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.rendering.velocity;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.themes.ThemeNotFoundException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;

import java.io.Reader;
import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.runtime.resource.ResourceCacheImpl;

import java.util.Date;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.util.ExtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ThemeResourceLoader}, focused on the two places a lookup
 * failure is re-thrown as Velocity's {@link ResourceNotFoundException}. Both
 * are already logged with the original exception, but the rethrow used to
 * discard it anyway (see CLAUDE.md's PreserveStackTrace note) -- these pin
 * that the cause now survives alongside the message.
 */
class ThemeResourceLoaderTest {

    private final ThemeResourceLoader loader = new ThemeResourceLoader();
    private ThemeManager themeManager;

    @BeforeEach
    void setUp() {
        themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);

        // ResourceLoader.log and rsvc (inherited from Velocity) are only set
        // by commonInit(), which the real engine calls during startup; this
        // test never starts the engine, so it calls both directly. The facade
        // arrives the way PressroomVelocity.initialize hands it over: as the
        // engine's application attribute, read in init().
        RuntimeServices rsvc = mock(RuntimeServices.class);
        when(rsvc.getLog(anyString())).thenReturn(LoggerFactory.getLogger("test"));
        when(rsvc.getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE)).thenReturn(weblogger);
        loader.commonInit(rsvc, new ExtProperties());
        loader.init(new ExtProperties());
    }

    @Test
    void aThemeNotFoundFailureIsWrappedWithItsCause() throws WebloggerException {
        ThemeNotFoundException cause = new ThemeNotFoundException("no such theme");
        when(themeManager.getTheme("missingtheme")).thenThrow(cause);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("missingtheme:sometemplate", "UTF-8"));

        assertEquals(cause, thrown.getCause());
    }

    @Test
    void aGenericLookupFailureIsWrappedWithItsCause() throws WebloggerException {
        WebloggerException cause = new WebloggerException("database unavailable");
        when(themeManager.getTheme("brokentheme")).thenThrow(cause);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("brokentheme:sometemplate", "UTF-8"));

        assertEquals(cause, thrown.getCause());
    }

    /**
     * The loader must tell Velocity the truth about when a theme last changed,
     * because that answer is the only thing standing between a cached parse
     * tree and a stale page.
     *
     * <p>Both methods used to be hardcoded -- {@code isSourceModified} returned
     * false and {@code getLastModified} returned 0 -- so caching had to be
     * switched off wholesale in {@code velocity.properties} to make theme
     * editing work at all. That meant Velocity re-read AND re-parsed every
     * theme template on every request, in production as well as in
     * development; the parser was around a tenth of render CPU in a profile.
     * With an honest answer here, the parse tree can be cached and rechecked
     * on an interval instead.
     */
    @Test
    void theLoaderReportsTheThemesOwnTimestamp() throws WebloggerException {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getLastModified()).thenReturn(new Date(1_500_000_000_000L));
        when(themeManager.getTheme("mytheme")).thenReturn(theme);

        assertEquals(1_500_000_000_000L, loader.getLastModified(resourceNamed("mytheme:weblog")),
                "Velocity is told the theme has never been modified, so a cached "
                        + "parse tree can never be invalidated");
    }

    @Test
    void aThemeReloadedFromDiskCountsAsModified() throws WebloggerException {
        SharedTheme theme = mock(SharedTheme.class);
        when(themeManager.getTheme("mytheme")).thenReturn(theme);

        Resource resource = resourceNamed("mytheme:weblog");
        when(theme.getLastModified()).thenReturn(new Date(1_000L));
        resource.setLastModified(loader.getLastModified(resource));
        assertFalse(loader.isSourceModified(resource),
                "nothing changed on disk, so nothing should be re-parsed");

        // what reLoadThemeFromDisk does: swap in a theme with a newer stamp
        when(theme.getLastModified()).thenReturn(new Date(2_000L));
        assertTrue(loader.isSourceModified(resource),
                "the theme was reloaded from disk; the cached parse tree is stale");
    }

    /**
     * A theme that cannot be looked up must not be reported as unchanged:
     * "unchanged" pins whatever is in the cache forever. Saying "modified"
     * costs one re-parse, which then raises the real error.
     */
    @Test
    void anUnresolvableThemeIsTreatedAsModifiedRatherThanUnchanged() throws WebloggerException {
        when(themeManager.getTheme(anyString())).thenThrow(new ThemeNotFoundException("gone"));
        Resource resource = resourceNamed("mytheme:weblog");
        resource.setLastModified(123L);
        assertTrue(loader.isSourceModified(resource));
        assertEquals(0L, loader.getLastModified(resource));
    }

    private static Resource resourceNamed(String name) {
        Resource r = new org.apache.velocity.Template();
        r.setName(name);
        return r;
    }

    /**
     * Velocity calls {@code getResourceReader(name, null)} -- with a NULL
     * encoding -- from {@code ResourceLoader.resourceExists()}, which
     * {@code ResourceManagerImpl.refreshResource()} uses to find the loader
     * that owns a cached resource.
     *
     * <p>This is only reachable when the loader caches, which is why it sat
     * here undetected: with caching off, refreshResource never runs. Turning
     * caching on surfaced it immediately as a {@code NullPointerException}
     * from {@code String.getBytes(null)}, which
     * {@code VelocityRendererFactory} turned into a null renderer and the
     * servlets turned into an intermittent 404 on a perfectly good page --
     * about 4 in every 60 concurrent requests, with the real cause visible
     * only at debug level.
     */
    @Test
    void aNullEncodingFallsBackToUtf8RatherThanThrowing() throws Exception {
        SharedTheme theme = mock(SharedTheme.class);
        ThemeTemplate template = mock(ThemeTemplate.class);
        TemplateRendition rendition = mock(TemplateRendition.class);
        when(rendition.getTemplate()).thenReturn("Hello \u00e9");
        when(template.getTemplateRendition(RenditionType.STANDARD)).thenReturn(rendition);
        when(theme.getTemplateByName("weblog")).thenReturn(template);
        when(themeManager.getTheme("mytheme")).thenReturn(theme);

        try (Reader reader = loader.getResourceReader("mytheme:weblog", null)) {
            StringBuilder read = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) { read.append((char) c); }
            assertEquals("Hello \u00e9", read.toString());
        }
    }

    /**
     * A resource with no name, or none at all, reports 0 rather than throwing.
     *
     * <p>Velocity asks for a last-modified time on paths this loader does not
     * own -- it is consulted for every resource until one claims it -- so an
     * unparseable name has to be an ordinary answer, not an exception thrown
     * out of a cache-freshness check. 0 then routes through isSourceModified
     * as "re-parse", which is the safe direction.
     */
    @Test
    void anUnnamedResourceReportsNoTimestampRatherThanThrowing() {
        assertEquals(0L, loader.getLastModified(null));
        assertEquals(0L, loader.getLastModified(new org.apache.velocity.Template()));
    }

    /**
     * A name that is not in {@code <theme>:<template>} shape belongs to one of
     * the other three loaders (webapp, roller, class), not this one.
     */
    @Test
    void aNameWithoutAThemePrefixReportsNoTimestamp() {
        assertEquals(0L, loader.getLastModified(resourceNamed("weblog.vm")),
                "a bare template name carries no theme, so there is no theme "
                        + "timestamp to report");
        assertEquals(0L, loader.getLastModified(resourceNamed(":weblog")),
                "an empty theme half is not a theme either");
    }

    /**
     * The rendition suffix is stripped before the theme is looked up, so both
     * renditions of a template share one theme timestamp.
     */
    @Test
    void theRenditionSuffixDoesNotHideTheTheme() throws WebloggerException {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getLastModified()).thenReturn(new Date(4_242L));
        when(themeManager.getTheme("mytheme")).thenReturn(theme);

        assertEquals(4_242L, loader.getLastModified(resourceNamed("mytheme:weblog|standard")));
    }
}
