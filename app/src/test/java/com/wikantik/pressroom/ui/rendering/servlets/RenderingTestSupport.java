package com.wikantik.pressroom.ui.rendering.servlets;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.jsp.JspApplicationContext;
import jakarta.servlet.jsp.JspEngineInfo;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.rendering.util.cache.SiteWideCache;
import com.wikantik.pressroom.ui.rendering.util.cache.WeblogFeedCache;
import com.wikantik.pressroom.ui.rendering.util.cache.WeblogPageCache;
import com.wikantik.pressroom.ui.rendering.velocity.PressroomVelocity;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

/**
 * Boots just enough of the container environment for the rendering servlets
 * to run in a plain JUnit JVM: a ServletContext rooted at src/main/webapp
 * (PressroomVelocity.initialize reads /WEB-INF/velocity.properties through it
 * and WebappResourceLoader still reads it from PressroomContext), a JspFactory (PageServlet and SearchServlet call
 * JspFactory.getDefaultFactory() unguarded), and the context URLs that
 * InitFilter would normally set. Installed once per JVM and never torn down:
 * the whole app suite shares one forked JVM and PressroomVelocity cannot be
 * re-initialized.
 */
public final class RenderingTestSupport {

    private static boolean runtimeReady;

    private RenderingTestSupport() {
    }

    public static synchronized void ensureRenderingRuntime() throws Exception {
        TestUtils.setupWeblogger();
        // The context URLs are JVM-global static state on WebloggerRuntimeConfig,
        // and three unrelated test classes null the absolute one out in their
        // teardown (URLModelTest, PreviewModelsTest, SiteModelTest, all under
        // ui.rendering.model) rather than restoring the prior value the way
        // PageModelTest does. If any of those runs before a rendering test in
        // this JVM, the guard below would otherwise skip re-setting the URLs
        // and every rendering test after it would render null-prefixed links.
        // So these two calls run on every invocation, not just the first --
        // do not move them back inside the runtimeReady guard.
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/pressroom");
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        if (runtimeReady) {
            return;
        }
        installServletContext();
        JspFactory.setDefaultFactory(new MapBackedJspFactory());
        runtimeReady = true;
    }

    /**
     * Render caches are per-JVM singletons and nothing clears them between
     * tests. The three servlet caches are also cleared by name: the cache
     * tests build their own instances under the same ids, and registering one
     * displaces the singleton from {@link CacheManager}, whose {@code clear()}
     * then no longer reaches the cache the servlets read (a feed rendered
     * earlier in the class came back from it).
     */
    public static void clearRenderCaches() {
        CacheManager.clear();
        WeblogPageCache.getInstance().clear();
        WeblogFeedCache.getInstance().clear();
        SiteWideCache.getInstance().clear();
    }

    private static void installServletContext() throws Exception {
        MockServletContext context = new WebappServletContext();
        Field field = PressroomContext.class.getDeclaredField("servletContext");
        field.setAccessible(true);
        field.set(null, context);
        // What PressroomLifecycle.start() does after bootstrap: the engine carries
        // the facade for the two Roller resource loaders. Idempotent, and the
        // engine is never torn down (see the class javadoc).
        PressroomVelocity.initialize(context, TestUtils.weblogger());
    }

    /**
     * Serves app/src/main/webapp. The Velocity webapp resource loader asks for
     * some paths without a leading slash ("templates/weblog/..."), which the
     * base MockServletContext would reject.
     */
    private static final class WebappServletContext extends MockServletContext {
        WebappServletContext() {
            super(webappRoot());
        }

        /**
         * Resolves src/main/webapp whether the JVM's working directory is
         * {@code app/} (the surefire default) or the repo root (common when
         * running a single test from an IDE), and returns it as an absolute
         * {@code file:} URI so MockServletContext doesn't have to guess.
         */
        private static String webappRoot() {
            Path candidate = Path.of("src", "main", "webapp");
            if (!Files.isDirectory(candidate)) {
                candidate = Path.of("app", "src", "main", "webapp");
            }
            return candidate.toAbsolutePath().toUri().toString();
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return super.getResourceAsStream(path.startsWith("/") ? path : "/" + path);
        }
    }

    /**
     * The PageContext stub is map-backed so setAttribute/findAttribute
     * round-trip for any model that stashes state on it mid-render.
     */
    private static final class MapBackedJspFactory extends JspFactory {
        @Override
        public PageContext getPageContext(Servlet servlet, ServletRequest request,
                ServletResponse response, String errorPageURL, boolean needsSession,
                int buffer, boolean autoflush) {
            Map<String, Object> attributes = new HashMap<>();
            PageContext pageContext = Mockito.mock(PageContext.class);
            Mockito.doAnswer(invocation -> {
                attributes.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(pageContext).setAttribute(Mockito.anyString(), Mockito.any());
            Mockito.when(pageContext.findAttribute(Mockito.anyString()))
                    .thenAnswer(invocation -> attributes.get(invocation.getArgument(0, String.class)));
            return pageContext;
        }

        @Override
        public void releasePageContext(PageContext pc) {
        }

        @Override
        public JspEngineInfo getEngineInfo() {
            return null;
        }

        @Override
        public JspApplicationContext getJspApplicationContext(ServletContext context) {
            return null;
        }
    }

    public static PageServlet pageServlet() throws ServletException {
        return init(new PageServlet(TestUtils.weblogger()));
    }

    static PreviewServlet previewServlet() throws ServletException {
        return init(new PreviewServlet(TestUtils.weblogger()));
    }

    public static FeedServlet feedServlet() throws ServletException {
        return init(new FeedServlet(TestUtils.weblogger()));
    }

    public static SearchServlet searchServlet() throws ServletException {
        return init(new SearchServlet(TestUtils.weblogger()));
    }

    public static MediaResourceServlet mediaResourceServlet() throws ServletException {
        return init(new MediaResourceServlet(TestUtils.weblogger()));
    }

    public static ResourceServlet resourceServlet() throws ServletException {
        return init(new ResourceServlet(TestUtils.weblogger()));
    }

    static PreviewResourceServlet previewResourceServlet() throws ServletException {
        return init(new PreviewResourceServlet(TestUtils.weblogger()));
    }

    private static <T extends HttpServlet> T init(T servlet) throws ServletException {
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));
        return servlet;
    }

    public static MockHttpServletRequest anonymousGet(String servletPath, String pathInfo) {
        return anonymousRequest("GET", servletPath, pathInfo);
    }

    private static MockHttpServletRequest anonymousRequest(String method,
            String servletPath, String pathInfo) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, "/pressroom" + servletPath + pathInfo);
        request.setContextPath("/pressroom");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        // honoured by PageServlet only; FeedServlet has no cache escape, use
        // clearRenderCaches() plus unique handles there
        request.setAttribute("skipCache", "true");
        return request;
    }

    public static MockHttpServletResponse execute(HttpServlet servlet,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            servlet.service(request, response);
        } finally {
            // what PersistenceSessionFilter would do at end of request
            TestUtils.endSession(false);
        }
        return response;
    }
}
