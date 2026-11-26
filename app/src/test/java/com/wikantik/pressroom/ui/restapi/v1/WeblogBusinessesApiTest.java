package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;

import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/weblogs/{handle}/businesses}: the business profiles a weblog's owner may point it
 * at, what the settings form's picker offers (spec 2026-11-20-api-gap-resources-design, Task 5).
 * Who may ask is pinned by the authorization matrix and {@link WeblogBusinessesApiSecurityTest}.
 */
class WeblogBusinessesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Weblogger weblogger;
    private BusinessManager businesses;
    private Weblog weblog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        businesses = mock(BusinessManager.class);
        when(weblogger.getBusinessManager()).thenReturn(businesses);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(weblogger.getWeblogPageManager()).thenReturn(mock(WeblogPageManager.class));
        when(weblogger.getUrlStrategy()).thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setName("My Blog");
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        WeblogBusinessesApi picker = new WeblogBusinessesApi();
        picker.weblogger = weblogger;
        WeblogSettingsApi settings = new WeblogSettingsApi();
        settings.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(picker, settings).setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Business business(String name, Business.BusinessType type) throws Exception {
        Business b = new Business();
        b.setName(name);
        b.setBusinessType(type);
        b.setTelephone("555 0100");
        when(businesses.getBusiness(b.getId())).thenReturn(b);
        return b;
    }

    private JsonNode list(String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/weblogs/myblog/businesses" + query)
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void eachChoiceCarriesItsIdNameAndTypeAndNothingElse() throws Exception {
        Business casa = business("Casa", Business.BusinessType.LocalBusiness);
        when(businesses.getBusinesses()).thenReturn(List.of(casa));

        JsonNode item = list("").get("items").get(0);

        assertEquals(List.of("id", "name", "businessType"), List.copyOf(item.propertyNames()));
        assertEquals(casa.getId(), item.get("id").asString());
        assertEquals("Casa", item.get("name").asString());
        assertEquals("LocalBusiness", item.get("businessType").asString());
    }

    @Test
    void sortedByNameIgnoringCaseThenIdWithTheEnvelope() throws Exception {
        Business bravo = business("bravo", Business.BusinessType.Organization);
        Business alpha = business("Alpha", Business.BusinessType.Organization);
        Business charlie = business("charlie", Business.BusinessType.Organization);
        when(businesses.getBusinesses()).thenReturn(List.of(charlie, bravo, alpha));

        JsonNode page = list("");

        assertEquals(List.of("Alpha", "bravo", "charlie"),
                page.get("items").valueStream().map(i -> i.get("name").asString()).toList());
        assertEquals(List.of("items", "offset", "limit", "hasMore", "total"), List.copyOf(page.propertyNames()));
        assertEquals(50, page.get("limit").asInt());
        assertFalse(page.get("hasMore").asBoolean());
        assertEquals(3, page.get("total").asInt());

        JsonNode second = list("?offset=1&limit=1");
        assertEquals("bravo", second.get("items").get(0).get("name").asString());
        assertTrue(second.get("hasMore").asBoolean());
        assertEquals(200, list("?limit=999").get("limit").asInt());
        mvc.perform(get("/v1/weblogs/myblog/businesses?limit=0").requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest());
    }

    /** Criterion 6: the id a choice carries is what PATCH /v1/weblogs/{handle} takes as businessId. */
    @Test
    void anIdFromThePickerIsAcceptedAsTheWeblogsBusinessId() throws Exception {
        Business casa = business("Casa", Business.BusinessType.LocalBusiness);
        Business other = business("Other", Business.BusinessType.Organization);
        when(businesses.getBusinesses()).thenReturn(List.of(other, casa));

        String picked = list("").get("items").get(0).get("id").asString();

        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(WebloggerRuntimeConfig.class);
                MockedStatic<CacheManager> _ = mockStatic(CacheManager.class)) {
            config.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
            config.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("https://blog.example.com");
            mvc.perform(patch("/v1/weblogs/myblog").requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"businessId\":\"" + picked + "\"}"))
                    .andExpect(status().isOk());
            String got = mvc.perform(get("/v1/weblogs/myblog").requestAttr("actionWeblog", weblog))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(casa.getId(), picked);
            assertEquals(picked, JSON.readTree(got).get("businessId").asString());
        }
        assertEquals(casa, weblog.getBusiness());
    }

    @Test
    void noActionWeblogIsANotFound() throws Exception {
        when(businesses.getBusinesses()).thenReturn(List.of());
        mvc.perform(get("/v1/weblogs/myblog/businesses")).andExpect(status().isNotFound());
    }
}
