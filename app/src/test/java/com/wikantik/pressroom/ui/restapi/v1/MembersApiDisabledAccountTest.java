package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code GET .../members} shows for a member whose account is disabled,
 * against the real user manager (MembersApiTest mocks it, so it cannot tell a
 * disabled account from a deleted one). The by-name lookup is enabled-only,
 * so the membership is left out and {@code total} does not count it; enabling
 * the account again lists it. Characterisation (0.16.3): written to pin the
 * behaviour 0.16.0 shipped, it passed as first run.
 */
class MembersApiDisabledAccountTest {

    private User owner;
    private User member;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        owner = TestUtils.setupUser("memdisowner");
        member = TestUtils.setupUser("memdismember");
        weblog = TestUtils.setupWeblog("memdisblog", owner);
        TestUtils.weblogger().getUserManager().grantWeblogPermission(weblog, member,
                List.of(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(member.getUserName());
        TestUtils.teardownUser(owner.getUserName());
        TestUtils.endSession(true);
    }

    private JsonNode members() throws Exception {
        MembersApi controller = new MembersApi();
        controller.weblogger = TestUtils.weblogger();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .build();
        Weblog current = TestUtils.weblogger().getWeblogManager().getWeblogByHandle(weblog.getHandle());
        return new ObjectMapper().readTree(mvc.perform(get("/v1/weblogs/" + weblog.getHandle() + "/members")
                .requestAttr("actionWeblog", current)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private void setEnabled(boolean enabled) throws Exception {
        UserManager users = TestUtils.weblogger().getUserManager();
        User account = users.getUserByUserName(member.getUserName(), null);
        account.setEnabled(enabled);
        users.saveUser(account);
        TestUtils.endSession(true);
    }

    private static List<String> userNames(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("userName").asString()).toList();
    }

    @Test
    void aDisabledAccountsMembershipIsLeftOutUntilTheAccountIsEnabledAgain() throws Exception {
        assertEquals(List.of(member.getUserName(), owner.getUserName()), userNames(members()));

        setEnabled(false);
        JsonNode whileDisabled = members();
        assertEquals(List.of(owner.getUserName()), userNames(whileDisabled));
        assertEquals(1, whileDisabled.get("total").asInt());

        setEnabled(true);
        assertEquals(List.of(member.getUserName(), owner.getUserName()), userNames(members()));
    }
}
