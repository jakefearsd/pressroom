package com.wikantik.pressroom.ui.restapi.v1;

import java.math.BigDecimal;
import java.util.List;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import com.wikantik.pressroom.ui.controllers.WeblogSettingsRules;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

/**
 * Controller-level tests for {@link WeblogsApi}: routing, the ownership
 * -free (site-wide) reach, and the guards between raw client input and a
 * manager call. {@code actionWeblog} is injected as a request attribute
 * rather than resolved by a real interceptor, matching {@code
 * CategoriesApiTest}'s standalone style -- these do not touch the DB; they
 * run against a Mockito-mocked {@code Weblogger}.
 */
class WeblogsApiTest {

    private MockMvc mockMvc(WeblogsApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getBusinessManager()).thenReturn(mock(BusinessManager.class));
        return weblogger;
    }

    /** The two site settings every PATCH reads through the shared settings rules. */
    private static org.mockito.MockedStatic<WebloggerRuntimeConfig> siteConfig() {
        var config = mockStatic(WebloggerRuntimeConfig.class);
        config.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
        config.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                .thenReturn("https://blog.example.com");
        return config;
    }

    private org.springframework.test.web.servlet.ResultActions doPatch(Weblogger weblogger, Weblog weblog,
            String json) throws Exception {
        try (var _ = siteConfig(); var _ = mockStatic(CacheManager.class)) {
            return mockMvc(controllerFor(weblogger))
                    .perform(patch("/v1/weblogs/{handle}", weblog.getHandle())
                            .requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json));
        }
    }

    private static tools.jackson.databind.JsonNode json(org.springframework.test.web.servlet.ResultActions r)
            throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(r.andReturn().getResponse().getContentAsString());
    }

    private WeblogsApi controllerFor(Weblogger weblogger) {
        WeblogsApi controller = new WeblogsApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setName("Name " + handle);
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        return weblog;
    }

    @Test
    void listReturnsEveryWeblogRegardlessOfOwnership() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog mine = aWeblog("mine");
        Weblog theirs = aWeblog("theirs");
        when(weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, 50))
                .thenReturn(List.of(mine, theirs));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(2, json.size());
        assertEquals("mine", json.get(0).get("handle").asString());
        assertEquals("theirs", json.get(1).get("handle").asString());
    }

    @Test
    void listRejectsANegativeOffset() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs").param("offset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never())
                .getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listRejectsANonPositiveLimit() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never())
                .getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void getReturnsAWeblogViewForAResolvedHandle() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/{handle}", "myblog").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("myblog", json.get("handle").asString());
        assertEquals("myblog-id", json.get("id").asString());
    }

    /**
     * No {@code actionWeblog} attribute is what a real interceptor leaves
     * behind for an unknown or blank handle -- {@code
     * BaseApiController.requireActionWeblog} must turn that into a clean
     * 404, never an NPE two lines later against a null weblog.
     */
    @Test
    void getIsNotFoundWhenNoActionWeblogWasResolved() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/{handle}", "nosuch"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * WeblogPageCache has no CacheHandler and expires only lazily against
     * weblog.lastModified -- CacheManager.invalidate is the only thing that
     * bumps it (see WeblogsApi.update's javadoc), so a save with no
     * corresponding invalidate call would leave a cached home page serving
     * stale content indefinitely. CacheManager.invalidate is a static call,
     * so it is verified with a MockedStatic rather than a Mockito mock.
     */
    @Test
    void patchUpdatesTheGivenFieldsAndLeavesOthersAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setTagline("original tagline");

        String body;
        try (var _ = siteConfig(); var cache = mockStatic(CacheManager.class)) {
            body = mockMvc(controllerFor(weblogger))
                    .perform(patch("/v1/weblogs/{handle}", "myblog")
                            .requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"New Name\",\"active\":false}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            cache.verify(() -> CacheManager.invalidate(weblog));
        }

        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertEquals("New Name", weblog.getName());
        assertFalse(weblog.getActive());
        assertEquals("original tagline", weblog.getTagline(), "an omitted field must be left untouched");
        verify(weblogger).flush();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("New Name", json.get("name").asString());
        assertFalse(json.get("active").asBoolean());
    }

    /** Criterion 25 (settings half): the comment settings are patchable, shown, and null leaves them alone. */
    @Test
    void patchSetsTheCommentSettingsAndNullLeavesThemAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"commentsEnabled\":true,\"commentNotify\":false}")
                .andExpect(status().isOk());

        assertEquals(Boolean.TRUE, weblog.getCommentsEnabled());
        assertEquals(Boolean.FALSE, weblog.getCommentNotify());
        tools.jackson.databind.JsonNode json = json(result);
        assertTrue(json.get("commentsEnabled").asBoolean());
        assertFalse(json.get("commentNotify").asBoolean());

        doPatch(weblogger, weblog, "{\"name\":\"Other\"}").andExpect(status().isOk());
        assertEquals(Boolean.TRUE, weblog.getCommentsEnabled());
        assertEquals(Boolean.FALSE, weblog.getCommentNotify());
    }

    @Test
    void patchSetsTheNewSettingsAndGetShowsThem() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"about\":\"A blog\",\"icon\":\"https://x/i.png\","
                + "\"analyticsSiteId\":\"0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d\","
                + "\"placeType\":\"LodgingBusiness\",\"placeLocality\":\"Encinitas\",\"placeCountry\":\"us\","
                + "\"placeLat\":33.04,\"placeLng\":-117.29,\"bookingUrl\":\"https://book.example.com\"}")
                .andExpect(status().isOk());

        tools.jackson.databind.JsonNode json = json(result);
        assertEquals("A blog", json.get("about").asString());
        assertEquals("https://x/i.png", json.get("icon").asString());
        assertEquals("0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d", json.get("analyticsSiteId").asString());
        assertEquals("LodgingBusiness", json.get("placeType").asString());
        assertEquals("Encinitas", json.get("placeLocality").asString());
        assertEquals("US", json.get("placeCountry").asString());
        assertEquals(33.04, json.get("placeLat").asDouble(), 0.0001);
        assertEquals(-117.29, json.get("placeLng").asDouble(), 0.0001);
        assertEquals("https://book.example.com", json.get("bookingUrl").asString());

        assertEquals("A blog", weblog.getAbout());
        assertEquals("https://x/i.png", weblog.getIconPath());
        assertEquals("0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d", weblog.getAnalyticsSiteId());
        assertEquals("LodgingBusiness", weblog.getPlaceType());
        assertEquals("Encinitas", weblog.getPlaceLocality());
        assertEquals("US", weblog.getPlaceCountry());
        assertEquals(new BigDecimal("33.04"), weblog.getPlaceLat());
        assertEquals(new BigDecimal("-117.29"), weblog.getPlaceLng());
        assertEquals("https://book.example.com", weblog.getBookingUrl());
    }

    @Test
    void aCoordinateWithMorePrecisionThanTheColumnIsStoredAtTwoDecimals() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"placeType\":\"LodgingBusiness\","
                + "\"placeLocality\":\"Berlin\",\"placeLat\":52.123456789,\"placeLng\":13.4049}")
                .andExpect(status().isOk());

        assertEquals(new BigDecimal("52.12"), weblog.getPlaceLat());
        assertEquals(new BigDecimal("13.40"), weblog.getPlaceLng());
        assertEquals(52.12, json(result).get("placeLat").asDouble(), 0.0001);
    }

    @Test
    void aNonFiniteCoordinateIsRefused() {
        AdminDtos.WeblogPatch patch = new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, Double.NaN, null, null, null, null, null);

        ApiException e = assertThrows(ApiException.class,
                () -> AdminDtos.applyPatch(new WeblogConfigBean(), patch, java.util.Set.of()));

        assertEquals(400, e.getStatus());
        assertEquals("placeLat must be a finite number.", e.getMessage());
    }

    @Test
    void aRuleTheRequestBreaksIsA400AndChangesNothing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"placeType\":\"Hotel\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertEquals("placeType must be LodgingBusiness.", json(result).get("detail").asString());
        assertNull(weblog.getPlaceType());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void setsOnlyPlaceTypeOnAWeblogWithNoLocalityIsRefused() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"placeType\":\"LodgingBusiness\"}")
                .andExpect(status().isBadRequest());

        assertEquals("A place needs a placeLocality (a town or city).", json(result).get("detail").asString());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aStoredValueThatBreaksARuleDoesNotBlockAnUnrelatedChange() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setAnalyticsSiteId("legacy-not-a-uuid");

        doPatch(weblogger, weblog, "{\"tagline\":\"New\"}").andExpect(status().isOk());

        assertEquals("New", weblog.getTagline());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void clearRemovesValuesAndPlacePairClearsTogether() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setAbout("About");
        weblog.setPlaceType("LodgingBusiness");
        weblog.setPlaceLocality("Encinitas");
        weblog.setPlaceLat(new BigDecimal("33.04"));
        weblog.setPlaceLng(new BigDecimal("-117.29"));

        var result = doPatch(weblogger, weblog,
                "{\"clear\":[\"about\",\"placeType\",\"placeLocality\",\"placeLat\",\"placeLng\"]}")
                .andExpect(status().isOk());

        // Weblog.setAbout strips HTML and turns null into blank, for the form as much as the API.
        assertTrue(weblog.getAbout() == null || weblog.getAbout().isEmpty());
        assertNull(weblog.getPlaceType());
        assertNull(weblog.getPlaceLocality());
        assertNull(weblog.getPlaceLat());
        assertNull(weblog.getPlaceLng());
        tools.jackson.databind.JsonNode json = json(result);
        assertTrue(json.get("about") == null || json.get("about").asString().isEmpty());
        for (String name : List.of("placeType", "placeLocality", "placeLat", "placeLng")) {
            assertTrue(json.get(name) == null || json.get(name).isNull(), name + " must be gone");
        }
    }

    @Test
    void patchSetsCopyrightAndTheViewShowsIt() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"copyright\":\"\u00a9 2024\u20132026 Maiia\"}")
                .andExpect(status().isOk());

        assertEquals("\u00a9 2024\u20132026 Maiia", weblog.getCopyright());
        assertEquals("\u00a9 2024\u20132026 Maiia", json(result).get("copyright").asString());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void viewIncludesCopyright() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setCopyright("Mine");

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/{handle}", "myblog").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("Mine", new tools.jackson.databind.ObjectMapper().readTree(body).get("copyright").asString());
    }

    @Test
    void patchCopyrightOver255IsFieldError() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        doPatch(weblogger, weblog, "{\"copyright\":\"" + "c".repeat(256) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("copyright"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void clearRemovesCopyright() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setCopyright("Mine");

        doPatch(weblogger, weblog, "{\"clear\":[\"copyright\"]}").andExpect(status().isOk());

        assertNull(weblog.getCopyright());
    }

    static java.util.stream.Stream<String> clearableNames() {
        return AdminDtos.WEBLOG_CLEARABLE.stream();
    }

    private static void store(Weblog w, String name) {
        switch (name) {
            case "tagline" -> w.setTagline("T");
            case "copyright" -> w.setCopyright("(c) Me");
            case "customDomain" -> w.setCustomDomain("mine.example.com");
            case "icon" -> w.setIconPath("https://x/i.png");
            case "about" -> w.setAbout("About");
            case "analyticsSiteId" -> w.setAnalyticsSiteId("0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d");
            case "analyticsShareUrl" -> w.setAnalyticsShareUrl("https://share.example.com/x");
            case "newsletterListUuid" -> w.setNewsletterListUuid("0b5d3f9e-1c2a-4e7b-9a6d-2f1e0c9b8a7d");
            case "businessId" -> {
                com.wikantik.pressroom.pojos.Business b = new com.wikantik.pressroom.pojos.Business();
                b.setId("biz-1");
                w.setBusiness(b);
            }
            case "placeType" -> {
                w.setPlaceType("LodgingBusiness");
                w.setPlaceLocality("Encinitas");
            }
            case "placeLocality" -> w.setPlaceLocality("Encinitas");
            case "placeRegion" -> w.setPlaceRegion("CA");
            case "placeCountry" -> w.setPlaceCountry("US");
            case "placeLat", "placeLng" -> {
                w.setPlaceLat(new BigDecimal("33.04"));
                w.setPlaceLng(new BigDecimal("-117.29"));
            }
            case "bookingUrl" -> w.setBookingUrl("https://book.example.com");
            default -> throw new IllegalArgumentException("No fixture for " + name);
        }
    }

    private static Object stored(Weblog w, String name) {
        return switch (name) {
            case "tagline" -> w.getTagline();
            case "copyright" -> w.getCopyright();
            case "customDomain" -> w.getCustomDomain();
            case "icon" -> w.getIconPath();
            case "about" -> w.getAbout();
            case "analyticsSiteId" -> w.getAnalyticsSiteId();
            case "analyticsShareUrl" -> w.getAnalyticsShareUrl();
            case "newsletterListUuid" -> w.getNewsletterListUuid();
            case "businessId" -> w.getBusiness();
            case "placeType" -> w.getPlaceType();
            case "placeLocality" -> w.getPlaceLocality();
            case "placeRegion" -> w.getPlaceRegion();
            case "placeCountry" -> w.getPlaceCountry();
            case "placeLat" -> w.getPlaceLat();
            case "placeLng" -> w.getPlaceLng();
            case "bookingUrl" -> w.getBookingUrl();
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** Every name in the clearable list has a working arm: a name without one would be a 500. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("clearableNames")
    void everyClearableFieldCanBeClearedThroughPatch(String name) throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        store(weblog, name);
        assertNotNull(stored(weblog, name), name + " fixture must store a value");
        if ("businessId".equals(name)) {
            when(weblogger.getBusinessManager().getBusiness("biz-1")).thenReturn(weblog.getBusiness());
        }
        String names = "placeLat".equals(name) || "placeLng".equals(name)
                ? "\"placeLat\",\"placeLng\"" : "\"" + name + "\"";

        var result = doPatch(weblogger, weblog, "{\"clear\":[" + names + "]}").andExpect(status().isOk());

        Object after = stored(weblog, name);
        assertTrue(after == null || "".equals(after), name + " must be gone, was " + after);
        tools.jackson.databind.JsonNode value = json(result).get(name);
        assertTrue(value == null || value.isNull() || value.asString().isEmpty(), name + " must be gone in JSON");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void clearingTheLocalityWhileThePlaceTypeStaysIsRefused() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setPlaceType("LodgingBusiness");
        weblog.setPlaceLocality("Encinitas");

        doPatch(weblogger, weblog, "{\"clear\":[\"placeLocality\"]}").andExpect(status().isBadRequest());

        assertEquals("Encinitas", weblog.getPlaceLocality());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anUnknownBusinessIsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getBusinessManager().getBusiness("nope")).thenReturn(null);

        var result = doPatch(weblogger, weblog, "{\"businessId\":\"nope\"}")
                .andExpect(status().isBadRequest());

        assertEquals("No such business.", json(result).get("detail").asString());
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anUnknownTimeZoneIsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        var result = doPatch(weblogger, weblog, "{\"timeZone\":\"Mars/Olympus_Mons\"}")
                .andExpect(status().isBadRequest());

        assertEquals("timeZone must be a time zone id, for example Europe/Berlin.",
                json(result).get("detail").asString());
    }

    @Test
    void patchRejectsABlankName() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
        verify(weblogger, never()).flush();
    }

    /**
     * The lower half of the same guard {@code WeblogConfigController
     * .myValidate} applies to the JSP form (the upper half -- above {@code
     * site.pages.maxEntries} -- needs a bootstrapped runtime config to
     * exercise and is covered by the JSP controller's own tests; this
     * guard's shape is proven here at the boundary that does not need one).
     */
    @Test
    void patchRejectsAnEntryDisplayCountBelowOne() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        doPatch(weblogger, weblog, "{\"entryDisplayCount\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Review round 1, Important 2: weblog.locale is varchar(20) -- the
     * shortest-typed column any patchable field in this API lands in.
     * "pt-BR-x-something-plausible" clears 20 characters without looking
     * remotely hostile.
     */
    @Test
    void patchRejectsALocaleLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(21);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsATimeZoneLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(51);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsANameLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsAnEmailAddressLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(250) + "@example.test";

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailAddress\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsATaglineLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchIsNotFoundWhenNoActionWeblogWasResolved() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "nosuch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void declaresAdminAsTheRequiredGlobalPermissionAndNoWeblogRequirement() {
        assertEquals(List.of(GlobalPermission.ADMIN), new WeblogsApi().requiredGlobalPermissionActions());
        assertTrue(new WeblogsApi().isUserRequired());
        assertFalse(new WeblogsApi().isWeblogRequired(),
                "isWeblogRequired must be false -- GET /v1/weblogs (list) carries no {handle}, so a "
                        + "true value would make PressroomHandlerInterceptor 404 the list endpoint outright.");
    }

    /**
     * WeblogsApi is mapped at /v1/weblogs, not /v1/admin, so {@code
     * AdminScopedCoverageTest} does not force this -- see the class javadoc
     * for why it carries the annotation anyway: this reaches ANY weblog on
     * the site, the same reach {@code AdminApi} has over accounts, and a
     * token minted for routine single-weblog automation must not be able to
     * use it just because its user happens to hold GlobalPermission.ADMIN.
     */
    @Test
    void carriesAdminScoped() {
        assertTrue(WeblogsApi.class.isAnnotationPresent(AdminScoped.class),
                "WeblogsApi must carry @AdminScoped -- it reaches every weblog on the site, not just "
                        + "the caller's own, and ApiScopeInterceptor otherwise never applies the "
                        + "ADMIN-role ceiling to it.");
    }

    @Test
    void patchWithABlankNameNamesTheNameField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }

    @Test
    void aNonFiniteCoordinateNamesTheCoordinateField() {
        AdminDtos.WeblogPatch patch = new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, Double.NaN, null, null, null, null, null);

        ApiException e = assertThrows(ApiException.class,
                () -> AdminDtos.applyPatch(new WeblogConfigBean(), patch, java.util.Set.of()));

        assertEquals("placeLat", e.getFieldErrors().get(0).field());
    }

    @Test
    void aPlaceTypeWithNoLocalityNamesThePlaceLocalityField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        doPatch(weblogger, weblog, "{\"placeType\":\"LodgingBusiness\"}")
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("placeLocality"));
    }

    @Test
    void anOverlongTextFieldNamesItsField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        doPatch(weblogger, weblog, "{\"about\":\"" + "a".repeat(WeblogSettingsRules.MAX_TEXT + 1) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("about"));
    }

    /**
     * Every field a WeblogSettingsRules violation can name is a WeblogPatch component, so the
     * error's field is a name the caller sent. The list is the rules' own field names (grep
     * WeblogSettingsRules for new Violation / simple / tooLong / mustBeUuid); a new rule
     * on a new field must add its name here.
     */
    @Test
    void everyWeblogSettingsRuleFieldIsAWeblogPatchComponent() {
        List<String> ruleFields = List.of("entryDisplayCount", "locale", "timeZone", "icon", "about", "copyright",
                "analyticsShareUrl", "newsletterListUuid", "analyticsSiteId", "businessId", "placeType",
                "placeLocality", "placeRegion", "placeCountry", "placeLat", "placeLng", "bookingUrl",
                "customDomain");
        Set<String> components = Arrays.stream(AdminDtos.WeblogPatch.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        for (String field : ruleFields) {
            assertTrue(components.contains(field), field + " is not a WeblogPatch component");
        }
    }
}
