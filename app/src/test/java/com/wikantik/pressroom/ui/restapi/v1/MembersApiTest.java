package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/weblogs/{handle}/members}: who holds a role on the weblog, as the Members page
 * shows it (spec 2026-11-20-api-gap-resources-design, Task 2). Who may ask is the interceptor's and
 * is pinned by {@link MembersApiSecurityTest} and the authorization matrix.
 */
class MembersApiTest {

    private static final String URL = "/v1/weblogs/myblog/members";

    private UserManager users;
    private Weblog weblog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        MembersApi controller = new MembersApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogPermission holds(String userName, String... actions) {
        WeblogPermission permission = new WeblogPermission(weblog, List.of(actions));
        permission.setUserName(userName);
        return permission;
    }

    private User account(String userName, String screenName, User.Kind kind) throws WebloggerException {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName(screenName);
        user.setEmailAddress(userName + "@example.com");
        user.setKind(kind);
        when(users.getUserByUserName(userName)).thenReturn(user);
        return user;
    }

    private JsonNode body(String query) throws Exception {
        return new ObjectMapper().readTree(mvc.perform(get(URL + query).requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Criterion 3: exactly four members per item, the right level for each, an agent as AGENT. */
    @Test
    void eachMemberCarriesExactlyFourMembersAndItsStrongestLevel() throws Exception {
        account("alice", "Alice", User.Kind.PERSON);
        account("bot", "Bot", User.Kind.AGENT);
        account("carol", "Carol", User.Kind.PERSON);
        when(users.getWeblogPermissions(weblog)).thenReturn(List.of(
                holds("alice", WeblogPermission.EDIT_DRAFT, WeblogPermission.POST, WeblogPermission.ADMIN),
                holds("bot", WeblogPermission.EDIT_DRAFT, WeblogPermission.POST),
                holds("carol", WeblogPermission.EDIT_DRAFT)));

        JsonNode items = body("").get("items");

        assertEquals(3, items.size());
        JsonNode alice = items.get(0);
        assertEquals(List.of("userName", "screenName", "kind", "permission"), List.copyOf(alice.propertyNames()));
        assertEquals("alice", alice.get("userName").asString());
        assertEquals("Alice", alice.get("screenName").asString());
        assertEquals("PERSON", alice.get("kind").asString());
        assertEquals("ADMIN", alice.get("permission").asString());
        assertEquals("AGENT", items.get(1).get("kind").asString());
        assertEquals("POST", items.get(1).get("permission").asString());
        assertEquals("EDIT_DRAFT", items.get(2).get("permission").asString());
    }

    /** Sorted by userName, case-insensitively; the envelope says where the page sits. */
    @Test
    void sortedByUserNameIgnoringCaseWithTheHouseEnvelope() throws Exception {
        for (String name : List.of("bravo", "Alpha", "charlie")) {
            account(name, name, User.Kind.PERSON);
        }
        when(users.getWeblogPermissions(weblog)).thenReturn(List.of(
                holds("charlie", WeblogPermission.POST), holds("bravo", WeblogPermission.POST),
                holds("Alpha", WeblogPermission.POST)));

        JsonNode page = body("");

        assertEquals(List.of("Alpha", "bravo", "charlie"),
                page.get("items").valueStream().map(i -> i.get("userName").asString()).toList());
        assertEquals(0, page.get("offset").asInt());
        assertEquals(50, page.get("limit").asInt());
        assertFalse(page.get("hasMore").asBoolean());
        assertEquals(3, page.get("total").asInt());
    }

    /** Criterion 5: offset/limit slice the list, total counts every listed member, a big limit is capped. */
    @Test
    void pagingSlicesAndTotalCountsEveryMember() throws Exception {
        List<WeblogPermission> all = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "user" + i;
            account(name, name, User.Kind.PERSON);
            all.add(holds(name, WeblogPermission.POST));
        }
        when(users.getWeblogPermissions(weblog)).thenReturn(all);

        JsonNode page = body("?offset=1&limit=2");

        assertEquals(List.of("user1", "user2"),
                page.get("items").valueStream().map(i -> i.get("userName").asString()).toList());
        assertEquals(1, page.get("offset").asInt());
        assertEquals(2, page.get("limit").asInt());
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(5, page.get("total").asInt());
        assertEquals(200, body("?limit=999").get("limit").asInt());
        assertFalse(body("?offset=4&limit=2").get("hasMore").asBoolean());
    }

    @Test
    void aBadRangeIsA400OnTheField() throws Exception {
        mvc.perform(get(URL + "?limit=0").requestAttr("actionWeblog", weblog)).andExpect(status().isBadRequest());
        mvc.perform(get(URL + "?offset=-1").requestAttr("actionWeblog", weblog)).andExpect(status().isBadRequest());
    }

    /** Criterion 4: an account that does not resolve is absent, and the others are listed (total too). */
    @Test
    void aMembershipWhoseAccountDoesNotResolveIsLeftOut() throws Exception {
        account("alice", "Alice", User.Kind.PERSON);
        when(users.getUserByUserName("ghost")).thenReturn(null);
        when(users.getWeblogPermissions(weblog)).thenReturn(List.of(
                holds("ghost", WeblogPermission.POST), holds("alice", WeblogPermission.POST)));

        JsonNode page = body("");

        assertEquals(1, page.get("items").size());
        assertEquals("alice", page.get("items").get(0).get("userName").asString());
        assertEquals(1, page.get("total").asInt());
    }

    /** Criterion 4: a lookup that cannot run is a 500, never a shorter list. */
    @Test
    void aFailingMembershipLookupIsA500() throws Exception {
        when(users.getWeblogPermissions(weblog)).thenThrow(new WebloggerException("down"));

        mvc.perform(get(URL).requestAttr("actionWeblog", weblog)).andExpect(status().isInternalServerError());
    }

    @Test
    void aFailingAccountLookupIsA500() throws Exception {
        when(users.getWeblogPermissions(weblog)).thenReturn(List.of(holds("alice", WeblogPermission.POST)));
        when(users.getUserByUserName("alice")).thenThrow(new WebloggerException("down"));

        mvc.perform(get(URL).requestAttr("actionWeblog", weblog)).andExpect(status().isInternalServerError());
    }
}
