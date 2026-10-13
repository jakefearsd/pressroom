package org.apache.roller.weblogger.ui.rendering.servlets;

import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link PageServlet#selectTemplate} does when the theme cannot be read
 * part-way through the choice. The theme lives on disk and can change under a
 * running server, so a lookup failure must end in a 404 or the default view,
 * never a 500 and never an exception out of the servlet.
 */
class PageServletTemplateFailureTest {

    private MockWeblogger weblogger;
    private Weblog weblog;
    private WeblogTheme theme;
    private WeblogPageRequest pageRequest;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        weblog = new Weblog();
        weblog.setHandle("failblog");
        theme = mock(WeblogTheme.class);
        when(weblogger.themeManager().getTheme(weblog)).thenReturn(theme);
        pageRequest = mock(WeblogPageRequest.class);
        when(pageRequest.getWeblog()).thenReturn(weblog);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    /** A tags index has no default to fall back to, so a failed lookup is a 404 (null), not a thrown error. */
    @Test
    void aTagsIndexLookupThatThrowsIsNotFound() throws Exception {
        when(pageRequest.getContext()).thenReturn("tags");
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(theme.getTemplateByAction(ComponentType.TAGSINDEX)).thenThrow(new WebloggerException("disk"));

        assertNull(PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /** A permalink may fall back, and a failed permalink lookup is treated like a missing template. */
    @Test
    void aPermalinkLookupThatThrowsFallsBackToTheDefaultTemplate() throws Exception {
        ThemeTemplate defaultTemplate = mock(ThemeTemplate.class);
        when(pageRequest.getWeblogAnchor()).thenReturn("some-post");
        when(theme.getTemplateByAction(ComponentType.PERMALINK)).thenThrow(new WebloggerException("disk"));
        when(theme.getDefaultTemplate()).thenReturn(defaultTemplate);

        assertSame(defaultTemplate, PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }
}
