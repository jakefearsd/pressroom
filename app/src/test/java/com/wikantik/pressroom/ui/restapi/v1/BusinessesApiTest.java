package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code /v1/admin/businesses}: the site's business profiles, created, read, changed and deleted
 * by a site admin (spec 2026-11-20-api-gap-resources-design, Task 5). Who may call is the
 * interceptors' and is pinned by the authorization matrix. The manager is an in-memory fake, so a
 * write is read back as a later request sees it.
 */
class BusinessesApiTest {

    private static final String URL = "/v1/admin/businesses";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Business> store = new LinkedHashMap<>();
    private final Map<String, Long> uses = new HashMap<>();
    private BusinessManager manager;
    private Weblogger weblogger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        manager = mock(BusinessManager.class);
        when(weblogger.getBusinessManager()).thenReturn(manager);
        when(manager.getBusinesses()).thenAnswer(call -> new ArrayList<>(store.values()));
        when(manager.getBusiness(any())).thenAnswer(call -> store.get((String) call.getArgument(0)));
        when(manager.countWeblogsUsing(any())).thenAnswer(
                call -> uses.getOrDefault(((Business) call.getArgument(0)).getId(), 0L));
        when(manager.getWeblogsUsing(any())).thenReturn(List.of());
        doAnswer(call -> {
            Business b = call.getArgument(0);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            if (b.getCreated() == null) {
                b.setCreated(now);
            }
            b.setLastModified(now);
            store.put(b.getId(), b);
            return null;
        }).when(manager).saveBusiness(any());
        doAnswer(call -> store.remove(((Business) call.getArgument(0)).getId())).when(manager).removeBusiness(any());
        BusinessesApi controller = new BusinessesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        try (MockedStatic<CacheManager> _ = mockStatic(CacheManager.class)) {
            return mvc.perform(request).andReturn();
        }
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    /** The errors of a 400, as field and code. */
    private static List<String> errors(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        List<String> found = new ArrayList<>();
        json(result).get("errors").forEach(e -> found.add(e.get("field").asString() + " " + e.get("code").asString()));
        return found;
    }

    private Business stored(String name) {
        Business b = new Business();
        b.setName(name);
        store.put(b.getId(), b);
        return b;
    }

    private static final String FULL = """
            {"name": " Casa Azul ", "businessType": "LocalBusiness", "websiteUrl": "https://casa.example.com",
             "bookingUrl": "https://book.example.com/casa", "telephone": "+351 912 345 678",
             "email": "hi@casa.example.com", "logoUrl": "https://casa.example.com/logo.png",
             "sameAs": ["https://a.example.com", " https://b.example.com "], "areaServed": "Azores",
             "description": "A guest house."}""";

    // ---------------------------------------------------------- criterion 1: the round trip

    @Test
    void createThenGetAndListRoundTripEveryField() throws Exception {
        MvcResult created = send(post(URL), FULL);

        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode view = json(created);
        String id = view.get("id").asString();
        assertEquals(List.of("id", "name", "businessType", "websiteUrl", "bookingUrl", "telephone", "email",
                "logoUrl", "sameAs", "areaServed", "description", "weblogCount", "created", "lastModified"),
                List.copyOf(view.propertyNames()));
        JsonNode got = json(send(get(URL + "/" + id)));
        for (JsonNode one : List.of(view, got, json(send(get(URL))).get("items").get(0))) {
            assertEquals("Casa Azul", one.get("name").asString());
            assertEquals("LocalBusiness", one.get("businessType").asString());
            assertEquals("https://casa.example.com", one.get("websiteUrl").asString());
            assertEquals("https://book.example.com/casa", one.get("bookingUrl").asString());
            assertEquals("+351 912 345 678", one.get("telephone").asString());
            assertEquals("hi@casa.example.com", one.get("email").asString());
            assertEquals("https://casa.example.com/logo.png", one.get("logoUrl").asString());
            assertEquals(List.of("https://a.example.com", "https://b.example.com"), strings(one.get("sameAs")));
            assertEquals("Azores", one.get("areaServed").asString());
            assertEquals("A guest house.", one.get("description").asString());
            assertEquals(0, one.get("weblogCount").asLong());
            assertFalse(one.get("created").isNull());
            assertFalse(one.get("lastModified").isNull());
        }
        assertEquals("https://a.example.com\nhttps://b.example.com", store.get(id).getSameAs());
    }

    @Test
    void createDefaultsTheTypeToOrganizationAndLeavesTheRestEmpty() throws Exception {
        JsonNode view = json(send(post(URL), "{\"name\":\"Plain\"}"));

        assertEquals("Organization", view.get("businessType").asString());
        assertTrue(view.get("websiteUrl").isNull());
        assertEquals(0, view.get("sameAs").size());
        assertTrue(view.get("description").isNull());
    }

    @Test
    void createRefusesAMissingNameAndClear() throws Exception {
        assertEquals(List.of("name field.required"), errors(send(post(URL), "{\"businessType\":\"Organization\"}")));
        MvcResult cleared = send(post(URL), "{\"name\":\"X\",\"clear\":[\"email\"]}");
        assertEquals(400, cleared.getResponse().getStatus());
        assertEquals("field.updateOnly", json(cleared).get("code").asString());
        assertTrue(store.isEmpty());
    }

    @Test
    void aPatchChangesOnlyTheFieldsSent() throws Exception {
        String id = json(send(post(URL), FULL)).get("id").asString();

        JsonNode view = json(send(patch(URL + "/" + id), "{\"telephone\":\"555 0100\",\"sameAs\":[\"https://c.example.com\"]}"));

        assertEquals("555 0100", view.get("telephone").asString());
        assertEquals(List.of("https://c.example.com"), strings(view.get("sameAs")));
        assertEquals("Casa Azul", view.get("name").asString());
        assertEquals("LocalBusiness", view.get("businessType").asString());
        assertEquals("https://casa.example.com", view.get("websiteUrl").asString());
        assertEquals("hi@casa.example.com", view.get("email").asString());
        assertEquals("A guest house.", view.get("description").asString());
        assertEquals("555 0100", store.get(id).getTelephone());
    }

    @Test
    void clearRemovesAnOptionalFieldAndIsRefusedForNameAndType() throws Exception {
        String id = json(send(post(URL), FULL)).get("id").asString();

        JsonNode view = json(send(patch(URL + "/" + id), "{\"clear\":[\"websiteUrl\",\"sameAs\",\"description\"]}"));
        assertTrue(view.get("websiteUrl").isNull());
        assertEquals(0, view.get("sameAs").size());
        assertTrue(view.get("description").isNull());
        assertEquals("hi@casa.example.com", view.get("email").asString());

        for (String field : List.of("name", "businessType")) {
            MvcResult refused = send(patch(URL + "/" + id), "{\"clear\":[\"" + field + "\"]}");
            assertEquals(400, refused.getResponse().getStatus(), field);
            assertEquals("clear.notClearable", json(refused).get("code").asString(), field);
        }
        assertEquals("Casa Azul", store.get(id).getName());
    }

    @Test
    void deleteRemovesAnUnusedBusiness() throws Exception {
        Business b = stored("Gone");

        MvcResult result = send(delete(URL + "/" + b.getId()));

        assertEquals(204, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertFalse(store.containsKey(b.getId()));
        verify(weblogger).flush();
    }

    // ---------------------------------------------------------- the list

    @Test
    void theListIsSortedByNameIgnoringCaseThenIdWithCountsAndTheEnvelope() throws Exception {
        Business bravo = stored("bravo");
        Business alphaOne = stored("Alpha");
        Business charlie = stored("charlie");
        uses.put(charlie.getId(), 3L);
        Business alphaTwo = stored("alpha");
        String firstAlpha = alphaOne.getId().compareTo(alphaTwo.getId()) < 0 ? alphaOne.getId() : alphaTwo.getId();

        JsonNode page = json(send(get(URL)));

        assertEquals(List.of("alpha", "alpha", "bravo", "charlie"), page.get("items").valueStream()
                .map(i -> i.get("name").asString().toLowerCase(java.util.Locale.ROOT)).toList());
        assertEquals(firstAlpha, page.get("items").get(0).get("id").asString());
        assertEquals(bravo.getId(), page.get("items").get(2).get("id").asString());
        assertEquals(3, page.get("items").get(3).get("weblogCount").asLong());
        assertEquals(List.of("items", "offset", "limit", "hasMore", "total"), List.copyOf(page.propertyNames()));
        assertEquals(0, page.get("offset").asInt());
        assertEquals(50, page.get("limit").asInt());
        assertFalse(page.get("hasMore").asBoolean());
        assertEquals(4, page.get("total").asInt());
    }

    @Test
    void theListPagesCapsAndRefusesARangeOutOfBounds() throws Exception {
        for (int i = 0; i < 5; i++) {
            stored("b" + i);
        }
        JsonNode page = json(send(get(URL).param("offset", "1").param("limit", "2")));
        assertEquals(List.of("b1", "b2"), page.get("items").valueStream().map(i -> i.get("name").asString()).toList());
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(5, page.get("total").asInt());
        assertEquals(200, json(send(get(URL).param("limit", "500"))).get("limit").asInt());
        assertEquals(400, send(get(URL).param("limit", "0")).getResponse().getStatus());
        assertEquals(400, send(get(URL).param("offset", "-1")).getResponse().getStatus());
    }

    // ---------------------------------------------------------- criterion 4: unknown ids and in use

    @Test
    void anUnknownIdIsA404OnGetPatchAndDelete() throws Exception {
        for (MvcResult result : List.of(send(get(URL + "/ghost")), send(patch(URL + "/ghost"), "{\"name\":\"X\"}"),
                send(delete(URL + "/ghost")))) {
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("business.notFound", json(result).get("code").asString());
        }
        verify(manager, never()).saveBusiness(any());
        verify(manager, never()).removeBusiness(any());
    }

    @Test
    void deletingABusinessInUseIsA409NamingHowManyAndRemovesNothing() throws Exception {
        Business b = stored("Busy");
        uses.put(b.getId(), 2L);

        MvcResult result = send(delete(URL + "/" + b.getId()));

        assertEquals(409, result.getResponse().getStatus());
        JsonNode problem = json(result);
        assertEquals("business.inUse", problem.get("code").asString());
        assertTrue(problem.get("detail").asString().contains("2"), problem.get("detail").asString());
        assertTrue(store.containsKey(b.getId()));
        verify(manager, never()).removeBusiness(any());
    }

    // ---------------------------------------------------------- criterion 3: the save's effects

    @Test
    void aSaveTouchesTheManagerAndDropsTheSiteWideCacheOfEveryBlogUsingIt() throws Exception {
        Business b = stored("Shared");
        Weblog one = new Weblog();
        Weblog two = new Weblog();
        when(manager.getWeblogsUsing(b)).thenReturn(List.of(one, two));
        List<Weblog> invalidated = new ArrayList<>();

        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            cache.when(() -> CacheManager.invalidate(any(Weblog.class)))
                    .thenAnswer(call -> invalidated.add(call.getArgument(0)));
            assertEquals(200, mvc.perform(patch(URL + "/" + b.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"areaServed\":\"Azores\"}")).andReturn().getResponse().getStatus());
        }
        verify(manager).saveBusiness(b);
        verify(weblogger).flush();
        assertEquals(List.of(one, two), invalidated);
    }

    @Test
    void aFailingInvalidationAfterTheSaveIsStillA200() throws Exception {
        Business b = stored("Shared");
        when(manager.getWeblogsUsing(b)).thenThrow(new WebloggerException("down"));

        assertEquals(200, send(patch(URL + "/" + b.getId()), "{\"areaServed\":\"Azores\"}").getResponse().getStatus());
    }

    // ---------------------------------------------------------- criterion 2: one test per rule

    @Test
    void aBlankNameIsRefused() throws Exception {
        assertEquals(List.of("name field.blank"), errors(send(post(URL), "{\"name\":\"  \"}")));
    }

    @Test
    void aNameOver255IsRefused() throws Exception {
        assertEquals(List.of("name field.tooLong"), errors(send(post(URL), "{\"name\":\"" + "n".repeat(256) + "\"}")));
    }

    @Test
    void anUnknownTypeIsRefused() throws Exception {
        assertEquals(List.of("businessType businessType.unknown"),
                errors(send(post(URL), "{\"name\":\"X\",\"businessType\":\"Hotel\"}")));
    }

    @Test
    void eachUrlThatIsNotAPublicHttpUrlIsRefused() throws Exception {
        assertEquals(List.of("websiteUrl field.notPublicUrl", "bookingUrl field.notPublicUrl",
                        "logoUrl field.notPublicUrl"),
                errors(send(post(URL), "{\"name\":\"X\",\"websiteUrl\":\"javascript:alert(1)\","
                        + "\"bookingUrl\":\"http://localhost/book\",\"logoUrl\":\"logo.png\"}")));
    }

    @Test
    void eachUrlOver255IsRefused() throws Exception {
        String url = "https://example.com/" + "p".repeat(240);
        assertEquals(List.of("websiteUrl field.tooLong", "bookingUrl field.tooLong", "logoUrl field.tooLong"),
                errors(send(post(URL), "{\"name\":\"X\",\"websiteUrl\":\"" + url + "\",\"bookingUrl\":\"" + url
                        + "\",\"logoUrl\":\"" + url + "\"}")));
    }

    @Test
    void aTelephoneOffThePatternIsRefused() throws Exception {
        assertEquals(List.of("telephone telephone.invalid"),
                errors(send(post(URL), "{\"name\":\"X\",\"telephone\":\"call me\"}")));
    }

    @Test
    void anInvalidEmailIsRefused() throws Exception {
        assertEquals(List.of("email email.invalid"), errors(send(post(URL), "{\"name\":\"X\",\"email\":\"nope\"}")));
    }

    @Test
    void anEmailOver255IsRefused() throws Exception {
        String label = "d".repeat(60);
        String email = "l".repeat(64) + "@" + label + "." + label + "." + label + "." + label + ".com";
        assertEquals(List.of("email field.tooLong"),
                errors(send(post(URL), "{\"name\":\"X\",\"email\":\"" + email + "\"}")));
    }

    @Test
    void anAreaServedOver255IsRefused() throws Exception {
        assertEquals(List.of("areaServed field.tooLong"),
                errors(send(post(URL), "{\"name\":\"X\",\"areaServed\":\"" + "a".repeat(256) + "\"}")));
    }

    @Test
    void moreThanTenSameAsLinksAreRefused() throws Exception {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            links.append(i == 0 ? "" : ",").append("\"https://p").append(i).append(".example.com\"");
        }
        assertEquals(List.of("sameAs sameAs.tooMany"),
                errors(send(post(URL), "{\"name\":\"X\",\"sameAs\":[" + links + "]}")));
    }

    @Test
    void aSameAsLinkThatIsNotHttpOrOver255IsRefused() throws Exception {
        String overlong = "https://example.com/" + "q".repeat(240);
        assertEquals(List.of("sameAs sameAs.entryInvalid", "sameAs sameAs.entryInvalid"),
                errors(send(post(URL), "{\"name\":\"X\",\"sameAs\":[\"https://ok.example.com\",\"ftp://x.com\",\""
                        + overlong + "\"]}")));
    }

    /** Every broken rule of one request is reported together, and nothing is stored. */
    @Test
    void everyViolationOfOneRequestIsReportedTogether() throws Exception {
        MvcResult result = send(post(URL), "{\"name\":\"\",\"businessType\":\"Hotel\",\"websiteUrl\":\"x\","
                + "\"telephone\":\"x\",\"email\":\"x\",\"sameAs\":[\"x\"]}");

        assertEquals(List.of("name field.blank", "businessType businessType.unknown", "websiteUrl field.notPublicUrl",
                "telephone telephone.invalid", "email email.invalid", "sameAs sameAs.entryInvalid"), errors(result));
        assertEquals("request.invalidFields", json(result).get("code").asString());
        assertTrue(store.isEmpty());
    }

    /** A PATCH is judged as the business will be: a bad value sent is refused and nothing changes. */
    @Test
    void aPatchBreakingARuleIsRefusedAndChangesNothing() throws Exception {
        Business b = stored("Kept");
        b.setEmail("kept@example.com");

        assertEquals(List.of("email email.invalid", "name field.blank").stream().sorted().toList(),
                errors(send(patch(URL + "/" + b.getId()), "{\"name\":\" \",\"email\":\"nope\"}")).stream().sorted()
                        .toList());
        assertEquals("Kept", b.getName());
        assertEquals("kept@example.com", b.getEmail());
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void theViewCarriesTheTimestampsTheStoreSet() throws Exception {
        JsonNode view = json(send(post(URL), "{\"name\":\"Dated\"}"));
        assertNotNull(store.get(view.get("id").asString()).getCreated());
        assertEquals(store.get(view.get("id").asString()).getCreated().toInstant().toString(),
                view.get("created").asString());
    }

    /** An empty clear list on create is no clear at all; a null link in sameAs is skipped. */
    @Test
    void anEmptyClearOnCreateIsAcceptedAndANullLinkSkipped() throws Exception {
        MvcResult created = send(post(URL), "{\"name\":\"X\",\"clear\":[],\"sameAs\":[null,\"https://a.example.com\"]}");
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        assertEquals(List.of("https://a.example.com"), strings(json(created).get("sameAs")));
    }

    /** A stored record with no name or type still lists (first) and reads, its type null. */
    @Test
    void aRecordWithNoNameOrTypeListsFirstWithANullType() throws Exception {
        stored("Named");
        Business bare = stored(null);
        bare.setBusinessType(null);

        JsonNode first = json(send(get(URL))).get("items").get(0);

        assertEquals(bare.getId(), first.get("id").asString());
        assertTrue(first.get("businessType").isNull());
    }
}
