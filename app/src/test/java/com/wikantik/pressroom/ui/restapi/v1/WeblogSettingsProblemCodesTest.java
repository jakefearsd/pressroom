package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.fieldCodes;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Every refusal of a weblog settings PATCH carries its own code, with the
 * English it always had (spec 2026-11-20-api-contract-design, Task 3). The
 * rules are WeblogSettingsRules', so each code here is a rule's meaning; the
 * English half passed before Task 3 (characterisation), the code half is what
 * it adds.
 */
class WeblogSettingsProblemCodesTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogManager weblogs = mock(WeblogManager.class);
    private final Weblog weblog = new Weblog();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        ThemeManager themes = mock(ThemeManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getBusinessManager()).thenReturn(mock(BusinessManager.class));
        when(weblogger.getWeblogPageManager()).thenReturn(mock(WeblogPageManager.class));
        when(weblogger.getThemeManager()).thenReturn(themes);
        List<SharedTheme> enabled = new ArrayList<>();
        for (String id : List.of("journal", "travel")) {
            SharedTheme theme = mock(SharedTheme.class);
            when(theme.getId()).thenReturn(id);
            enabled.add(theme);
        }
        when(themes.getEnabledThemesList()).thenReturn(enabled);
        when(themes.getTheme(weblog)).thenReturn(mock(WeblogTheme.class));
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setName("My Blog");
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        weblog.setEditorTheme("journal");
        WeblogSettingsApi controller = new WeblogSettingsApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode send(int status, String json) throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(WebloggerRuntimeConfig.class);
             MockedStatic<CacheManager> _ = mockStatic(CacheManager.class)) {
            config.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
            config.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("https://blog.example.com");
            String body = mvc.perform(patch("/v1/weblogs/myblog").requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(r -> assertEquals(status, r.getResponse().getStatus()))
                    .andReturn().getResponse().getContentAsString();
            return new ObjectMapper().readTree(body);
        }
    }

    private JsonNode refused(String json) throws Exception {
        return send(400, json);
    }

    @Test
    void theThemeRefusalsHaveTheirCodes() throws Exception {
        oneField(refused("{\"theme\":\"custom\"}"), "theme", "theme.customNotAllowed",
                "Custom themes are managed in the admin UI.");
        oneField(refused("{\"theme\":\"nosuchtheme\"}"), "theme", "theme.notOffered",
                "theme must be one of: journal, travel.");
    }

    @Test
    void theDisplayAndTextRulesHaveTheirCodes() throws Exception {
        oneField(refused("{\"name\":\" \"}"), "name", "field.blank", "name cannot be blank.");
        oneField(refused("{\"entryDisplayCount\":0}"), "entryDisplayCount", "entryDisplayCount.outOfRange",
                "entryDisplayCount must be between 1 and 30.");
        oneField(refused("{\"entryOrder\":\"SIDEWAYS\"}"), "entryOrder", "entryOrder.invalid",
                "entryOrder must be NEWEST or OLDEST.");
        oneField(refused("{\"pageWidth\":\"HUGE\"}"), "pageWidth", "pageWidth.invalid",
                "pageWidth must be STANDARD or WIDE.");
        oneField(refused("{\"copyright\":\"" + "c".repeat(256) + "\"}"), "copyright", "field.tooLong",
                "'copyright' must be 255 characters or fewer.");
        oneField(refused("{\"locale\":\"xx_YY\"}"), "locale", "locale.invalid",
                "locale must be one the settings form offers, for example en_US.");
        oneField(refused("{\"timeZone\":\"Mars/Base\"}"), "timeZone", "timeZone.invalid",
                "timeZone must be a time zone id, for example Europe/Berlin.");
        oneField(refused("{\"newsletterListUuid\":\"x\"}"), "newsletterListUuid", "field.notUuid",
                "newsletterListUuid must be a UUID.");
    }

    @Test
    void theBusinessAndFrontPageRulesHaveTheirCodes() throws Exception {
        oneField(refused("{\"businessId\":\"nope\"}"), "businessId", "business.unknown", "No such business.");
        oneField(refused("{\"frontPage\":\"nope\"}"), "frontPage", "frontPage.invalid",
                "frontPage must be the id of a published page of this blog.");
    }

    @Test
    void thePlaceRulesHaveTheirCodes() throws Exception {
        oneField(refused("{\"placeType\":\"Hotel\"}"), "placeType", "placeType.invalid",
                "placeType must be LodgingBusiness.");
        oneField(refused("{\"placeType\":\"LodgingBusiness\"}"), "placeLocality", "placeLocality.required",
                "A place needs a placeLocality (a town or city).");
        oneField(refused("{\"placeCountry\":\"USA\"}"), "placeCountry", "placeCountry.invalid",
                "placeCountry must be a two-letter country code.");
        oneField(refused("{\"bookingUrl\":\"ftp://x.example\"}"), "bookingUrl", "bookingUrl.invalid",
                "bookingUrl must be an http or https URL.");
    }

    /** The multi-field route: one 400 names every broken rule, each with its own code. */
    @Test
    void aLatitudeOffTheGlobeWithNoLongitudeIsInvalidFieldsWithEachRulesCode() throws Exception {
        JsonNode body = refused("{\"placeLat\":100}");
        assertEquals("placeLat must be between -90 and 90, given together with placeLng. "
                + "placeLng must be between -180 and 180, given together with placeLat.", body.get("detail").asString());
        assertEquals("request.invalidFields", body.get("code").asString());
        assertEquals(List.of("placeLat=placeLat.invalid", "placeLng=placeLng.invalid"), fieldCodes(body));
    }

    @Test
    void theCustomDomainRulesHaveTheirCodes() throws Exception {
        oneField(refused("{\"customDomain\":\"not a host\"}"), "customDomain", "customDomain.malformed",
                "customDomain must be a well-formed hostname.");
        oneField(refused("{\"customDomain\":\"blog.example.com\"}"), "customDomain", "customDomain.siteHost",
                "customDomain must not be the site's own hostname.");

        Weblog other = new Weblog();
        other.setHandle("other");
        when(weblogs.getWeblogByCustomDomain("taken.example")).thenReturn(other);
        oneField(send(409, "{\"customDomain\":\"taken.example\"}"), "customDomain", "customDomain.inUse",
                "customDomain is already in use by another weblog.");

        when(weblogs.getWeblogByCustomDomain("flaky.example")).thenThrow(new WebloggerException("down"));
        oneField(refused("{\"customDomain\":\"flaky.example\"}"), "customDomain", "customDomain.uncheckable",
                "customDomain could not be checked; try again.");
    }

    @Test
    void aNonFiniteCoordinateIsFieldNotFinite() {
        AdminDtos.WeblogPatch patch = new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, Double.NaN, null, null, null, null, null, null, null,
                null, null, null, null);

        ApiException e = assertThrows(ApiException.class,
                () -> AdminDtos.applyPatch(new WeblogConfigBean(), patch, java.util.Set.of()));

        assertEquals("placeLat must be a finite number.", e.getMessage());
        assertEquals("field.notFinite", e.toProblem(null).code());
        assertEquals("field.notFinite", e.toProblem(null).errors().get(0).code());
    }
}
