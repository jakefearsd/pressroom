package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 2026-11-08 agent API parity, P5: a page saved through the API with a
 * {@code searchDescription} renders it as its meta description, through the
 * real PageServlet and theme.
 */
class PagesApiRenderTest {

    private static final String HANDLE = "pagesapirenderblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("pagesapirenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager().removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aSearchDescriptionSavedThroughTheApiIsThePagesMetaDescription() throws Exception {
        PagesApi api = new PagesApi();
        api.weblogger = TestUtils.weblogger();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/" + HANDLE + "/pages");
        request.setAttribute("actionWeblog", TestUtils.getManagedWebsite(weblog));
        PageDtos.PageWrite body = new ObjectMapper().readValue(
                "{\"slug\":\"services\",\"title\":\"Services\",\"text\":\"What we do.\",\"status\":\"PUBLISHED\","
                        + "\"searchDescription\":\"Guided walks on the coast.\"}",
                PageDtos.PageWrite.class);

        assertEquals(201, api.create(request, body).getStatusCode().value());
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE + "/services"));
        assertEquals(200, response.getStatus());
        String html = response.getContentAsString();
        assertTrue(html.contains("<meta name=\"description\" content=\"Guided walks on the coast.\">"),
                "the API-saved search description must be the meta description:\n" + html);
    }
}
