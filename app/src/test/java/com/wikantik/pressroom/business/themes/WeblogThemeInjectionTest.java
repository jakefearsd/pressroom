package com.wikantik.pressroom.business.themes;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two {@code WeblogTheme} subclasses resolve a weblog's own (database)
 * templates through the {@code WeblogManager} they are constructed with, not
 * through a static locator (DI wave, plan Task 4). Each test is a unit test
 * with no business tier present, which is the point.
 */
class WeblogThemeInjectionTest {

    private final Weblog weblog = new Weblog();
    private final WeblogManager weblogManager = mock(WeblogManager.class);

    @Test
    void aCustomThemeListsTemplatesThroughTheManagerItWasGiven() throws WebloggerException {
        new WeblogCustomTheme(weblog, weblogManager).getTemplates();

        verify(weblogManager).getTemplates(weblog);
    }

    @Test
    void aCustomThemeResolvesALinkThroughTheManagerItWasGiven() throws WebloggerException {
        WeblogTemplate stored = new WeblogTemplate();
        when(weblogManager.getTemplateByLink(weblog, "about")).thenReturn(stored);

        assertSame(stored, new WeblogCustomTheme(weblog, weblogManager).getTemplateByLink("about"));
    }

    /**
     * A shared theme falls back to the weblog's own templates when the theme
     * itself has no template at that link -- and that fallback goes through
     * the injected manager.
     */
    @Test
    void aSharedThemeFallsBackToTheWeblogsOwnTemplatesThroughTheManagerItWasGiven()
            throws WebloggerException {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getTemplateByLink("about")).thenReturn(null);
        WeblogTemplate stored = new WeblogTemplate();
        when(weblogManager.getTemplateByLink(weblog, "about")).thenReturn(stored);

        assertSame(stored,
                new WeblogSharedTheme(weblog, theme, weblogManager).getTemplateByLink("about"));
    }
}
