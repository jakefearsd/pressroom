package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit and traffic routes' refusals carry their own codes, with the
 * English they always had (spec 2026-11-20-api-contract-design, Task 3). The
 * English half passed before Task 3 (characterisation); the code half is what
 * it adds.
 */
class AuditTrafficProblemCodesTest {

    private final Weblogger weblogger = mock(Weblogger.class);

    private JsonNode refused(BaseApiController controller, MockHttpServletRequestBuilder request) throws Exception {
        controller.weblogger = weblogger;
        Weblog weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void aTrashedFilterOnTheSeoAuditIsAuditTrashedNotCovered() throws Exception {
        oneField(refused(new AuditApi(), get("/v1/weblogs/myblog/audit/seo").param("status", "TRASHED")),
                "status", "audit.trashedNotCovered",
                "The SEO audit does not cover trashed entries; status=TRASHED is not accepted here.");
    }

    @Test
    void anUnknownPeriodIsTrafficPeriodUnknown() throws Exception {
        oneField(refused(new TrafficApi(), get("/v1/weblogs/myblog/traffic").param("period", "1y")),
                "period", "traffic.periodUnknown", "period must be one of 24h, 7d, 30d, 90d.");
    }
}
