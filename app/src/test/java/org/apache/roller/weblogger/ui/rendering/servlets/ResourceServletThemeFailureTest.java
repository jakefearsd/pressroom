package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A theme that cannot be read is a server problem, not a missing file: the
 * reader gets a 500 rather than a 404 that would be cached and believed.
 */
class ResourceServletThemeFailureTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("resfailuser");
        weblog = TestUtils.setupWeblog("resfailblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aThemeLookupThatFailsIsA500NotA404() throws Exception {
        ThemeManager broken = mock(ThemeManager.class);
        when(broken.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class)))
                .thenThrow(new WebloggerException("theme directory unreadable"));
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(real.getWeblogManager());
        when(weblogger.getThemeManager()).thenReturn(broken);
        ResourceServlet servlet = new ResourceServlet(weblogger);
        servlet.init(new MockServletConfig(RollerContext.getServletContext()));

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/resfailblog/style.css");
        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, request);

        assertEquals(500, response.getStatus());
    }
}
