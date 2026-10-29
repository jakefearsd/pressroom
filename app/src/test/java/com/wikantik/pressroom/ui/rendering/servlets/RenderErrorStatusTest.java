package com.wikantik.pressroom.ui.rendering.servlets;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCaches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template that fails mid-render shows the reader the error page (or the
 * error block spliced where a failed {@code #parse} would have gone), and
 * that page used to go out as a 200 and into the page cache. The cache
 * expires only when {@code weblog.lastModified} moves, so one transient
 * failure -- a database blip inside a model getter -- stayed pinned in front
 * of every reader until an author next saved something.
 *
 * <p>An error render is now a 500 and is never cached. The good template in
 * {@link #aTemplateThatRendersIsStillCached} is the control: without it,
 * "not in the cache" could pass because the cache is simply off.
 */
class RenderErrorStatusTest {

    private static final String HANDLE = "rendererrorblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("rendererroruser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /** The constructor path: a template that does not parse renders error-page.vm. */
    @Test
    void aTemplateThatDoesNotParseIsAServerErrorAndIsNotCached() throws Exception {
        saveCustomTemplate("unparsable", "<p>before</p>\n#if($model.weblog.name\n<p>never closed</p>");

        MockHttpServletRequest request = get("unparsable");
        MockHttpServletResponse response = render(request);

        assertEquals(500, response.getStatus(), "an error page is not a 200");
        assertTrue(response.getContentAsString().contains("<!-- error-page -->"),
                "the reader still gets the error page's body:\n" + response.getContentAsString());
        assertNull(cached(request), "an error page must never be cached");
    }

    /** The merge path: a failure mid-render splices in error-parse.vm. */
    @Test
    void aTemplateThatFailsMidRenderIsAServerErrorAndIsNotCached() throws Exception {
        saveCustomTemplate("failing", "<p>before</p>\n#parse(\"no-such-template.vm\")\n<p>after</p>");

        MockHttpServletRequest request = get("failing");
        MockHttpServletResponse response = render(request);

        assertEquals(500, response.getStatus(), "a page with an error block is not a 200");
        assertTrue(response.getContentAsString().contains("<!-- error-parse -->"),
                "the reader still gets the error block:\n" + response.getContentAsString());
        assertNull(cached(request), "a page with an error block must never be cached");
    }

    /**
     * A broken template is never cached, so it is re-rendered on every
     * request; a full ERROR stack trace each time would bury every other
     * line of the log under one crawler. The first failure for a template
     * (and for each new save of it) is an ERROR with the trace, the repeats
     * are DEBUG. Both paths, the parse failure and the mid-render one.
     */
    @Test
    void aTemplateThatKeepsFailingLogsItsStackTraceOnce() throws Exception {
        saveCustomTemplate("unparsable2", "#if($model.weblog.name\n<p>never closed</p>");
        saveCustomTemplate("failing2", "#parse(\"no-such-template.vm\")");
        List<LogEvent> events = new ArrayList<>();
        String loggerName = "com.wikantik.pressroom.ui.rendering.velocity.VelocityRenderer";
        AbstractAppender appender = new AbstractAppender("velocity-capture", null, null, true,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig(loggerName, Level.DEBUG, false);
        lc.addAppender(appender, Level.DEBUG, null);
        ctx.getConfiguration().addLogger(loggerName, lc);
        ctx.updateLoggers();
        try {
            for (String link : new String[]{"unparsable2", "failing2"}) {
                events.clear();
                for (int i = 0; i < 3; i++) {
                    assertEquals(500, render(get(link)).getStatus());
                }
                List<LogEvent> errors = events.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
                assertEquals(1, errors.size(), link + ": one ERROR, not one per request: " + events);
                assertNotNull(errors.get(0).getThrown(), link + ": the one ERROR carries the stack trace");
                assertEquals(2, events.stream().filter(e -> e.getLevel() == Level.DEBUG
                        && e.getMessage().getFormattedMessage().contains("again")).count(),
                        link + ": the repeats are DEBUG: " + events);
            }
        } finally {
            ctx.getConfiguration().removeLogger(loggerName);
            ctx.updateLoggers();
            appender.stop();
        }
    }

    @Test
    void aTemplateThatRendersIsStillCached() throws Exception {
        saveCustomTemplate("working", "<p>fine</p>");

        MockHttpServletRequest request = get("working");
        MockHttpServletResponse response = render(request);

        assertEquals(200, response.getStatus());
        assertNotNull(cached(request), "the control: a good render is cached");
    }

    /** A request the servlet may cache: RenderingTestSupport opts every request out by default. */
    private static MockHttpServletRequest get(String link) {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/page", "/" + HANDLE + "/page/" + link);
        request.removeAttribute("skipCache");
        return request;
    }

    private static MockHttpServletResponse render(MockHttpServletRequest request) throws Exception {
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    private static Object cached(MockHttpServletRequest request) throws Exception {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        WeblogPageRequest pageRequest = new WeblogPageRequest(TestUtils.weblogger(), request);
        Weblog current = TestUtils.weblogger().getWeblogManager().getWeblogByHandle(HANDLE);
        return cache.get(cache.generateKey(pageRequest), cache.lastModified(current));
    }

    private void saveCustomTemplate(String name, String contents) throws Exception {
        WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(TestUtils.getManagedWebsite(weblog));
        template.setAction(ComponentType.CUSTOM);
        template.setName(name);
        template.setLink(name);
        template.setDescription(name);
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        wmgr.saveTemplate(template);
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate(contents);
        rendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
        wmgr.saveTemplateRendition(rendition);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
        // Each save bumps the weblog's lastModified, up to a millisecond past
        // the clock (touchLastModified only moves forward), and the page cache
        // treats an entry cached in the same millisecond as a change as stale.
        // Render only once the clock has passed it, or the control test's good
        // render reads back as uncached (docs/dev/build-and-ci.md, as
        // PageServletCachingTest does).
        long stamped = TestUtils.weblogger().getWeblogManager()
                .getWeblogByHandle(HANDLE).getLastModified().getTime();
        TestUtils.endSession(true);
        while (System.currentTimeMillis() <= stamped) {
            Thread.onSpinWait();
        }
    }
}
