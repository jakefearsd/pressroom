package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which template the preview servlet picks, and what it refuses to preview.
 *
 * <p>PreviewServletRenderingTest covers the happy paths -- front page,
 * permalink, another theme. What was untested is the template <em>selection</em>
 * above them: preview has its own ladder (named page, then tags index, then
 * permalink, then the theme default, then 404) and each rung was reached only
 * incidentally, if at all.
 *
 * <p>The theme fixture is "journal", which ships a stylesheet template and no
 * tags-index template. Both facts are load-bearing below and are asserted
 * rather than assumed where they can be.
 */
class PreviewServletDecisionTest {

    private static final String PREVIEW_SERVLET = "/pressroom-ui/authoring/preview";

    private final String handle = "previewdecisionblog";
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewdecisionuser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse preview(String pathInfo, String... params) throws Exception {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PREVIEW_SERVLET, pathInfo);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        return RenderingTestSupport.execute(RenderingTestSupport.previewServlet(), request);
    }

    // --- the template ladder ----------------------------------------------

    @Test
    void aNamedThemeTemplateCanBePreviewedDirectly() throws Exception {
        MockHttpServletResponse response =
                preview("/" + handle + "/page/journal-custom.css");

        assertEquals(200, response.getStatus(),
                "a template the theme actually ships must be previewable by name");
    }

    /**
     * The one rung that deliberately does not fall through. Every other miss
     * lands on the theme's default template; a tags-index request without a
     * custom tags template 404s instead, because rendering the front page for
     * a /tags request would tell the author their tags page works when the
     * theme has no such page at all.
     */
    @Test
    void previewingTheTagsIndexIsNotFoundWhenTheThemeHasNoTagsTemplate() throws Exception {
        MockHttpServletResponse response = preview("/" + handle + "/tags");

        assertEquals(404, response.getStatus(),
                "journal ships no tagsIndex template, so this must 404 rather than "
                        + "silently falling through to the default page");
    }

    @Test
    void anUnknownPageNameFallsBackToTheThemeDefault() throws Exception {
        MockHttpServletResponse response =
                preview("/" + handle + "/page/no-such-template");

        assertEquals(200, response.getStatus(),
                "an unknown named page falls back to the theme's default template rather "
                        + "than 404ing -- the opposite of the tags rung above");
    }

    // --- which theme gets previewed ---------------------------------------

    @Test
    void anUnknownThemeNameLeavesTheWeblogOnItsOwnTheme() throws Exception {
        MockHttpServletResponse response =
                preview("/" + handle, "theme", "nosuchtheme");

        assertEquals(200, response.getStatus(),
                "a theme name that resolves to nothing must not break the preview; the "
                        + "weblog stays on the theme it already has");
    }

    /**
     * "custom" is not a shared theme to look up; it is an instruction to
     * preview the weblog's OWN templates, and the servlet handles it as its own
     * branch for exactly that reason.
     *
     * <p>This weblog is on a shared theme and has defined no templates of its
     * own, so there is genuinely nothing for that branch to render and the
     * preview 404s. That is the correct answer rather than a defect -- but it
     * is worth pinning, because the branch is easy to read as "fall back to
     * whatever the weblog was already using", which would render the shared
     * theme and tell an author their custom templates work when they have
     * none.
     */
    @Test
    void previewingCustomTemplatesOnAWeblogThatHasNoneIsNotFound() throws Exception {
        MockHttpServletResponse response =
                preview("/" + handle, "theme", "custom");

        assertEquals(404, response.getStatus(),
                "nothing to preview, so nothing is rendered -- not the shared theme the "
                        + "weblog happens to be on");
    }

    @Test
    void aDisabledOrMissingThemeStillRendersTheWeblog() throws Exception {
        MockHttpServletResponse response = preview("/" + handle, "theme", "");

        assertEquals(200, response.getStatus(),
                "an empty theme parameter is no theme preview at all");
        assertTrue(response.getContentType().startsWith("text/html"));
    }
}
