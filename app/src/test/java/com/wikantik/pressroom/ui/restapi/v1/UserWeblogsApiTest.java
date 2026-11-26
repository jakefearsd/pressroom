package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The session-only membership writes from a person's or agent's page ({@code POST/PATCH/DELETE
 * /v1/admin/users/{u}/weblogs}, SA-18), judged by the rules {@code UserWeblogsController} applies through
 * {@code Memberships}: a weblog keeps one admin, nothing new is granted while group blogging is off, a reader
 * is never granted, the site space is never offered. Who may call them is the interceptors' and is swept
 * elsewhere.
 */
class UserWeblogsApiTest {

    private static final String URL = "/v1/admin/users/carol/weblogs";

    private SessionOnlyApiSupport support;
    private UserManager users;
    private Weblog blog;
    private User carol;

    @BeforeEach
    void setUp() throws Exception {
        support = new SessionOnlyApiSupport(new UserWeblogsApi());
        users = support.tier.userManager();
        blog = new Weblog();
        blog.setId("b1");
        blog.setHandle("myblog");
        blog.setName("My blog");
        when(support.tier.weblogManager().getWeblogByHandle("myblog", null)).thenReturn(blog);
        carol = new User();
        carol.setUserName("carol");
        carol.setKind(User.Kind.PERSON);
        when(users.getUserByUserName("carol", null)).thenReturn(carol);
        when(users.checkPermission(any(), any())).thenReturn(true);
        when(support.tier.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "true"));
    }

    @AfterEach
    void tearDown() {
        support.detach();
    }

    private WeblogPermission holds(String userName, String... actions) {
        WeblogPermission permission = new WeblogPermission(blog, List.of(actions));
        permission.setUserName(userName);
        return permission;
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
            String body) throws Exception {
        return support.send(b, body);
    }

    private static void refused(ResultActions r, int status, String code) throws Exception {
        SessionOnlyApiSupport.refused(r, status, code);
    }

    @Test
    void grantGivesTheAccountTheLevelAndReportsItsMembership() throws Exception {
        when(users.getWeblogPermission(blog, carol)).thenReturn(holds("carol", "post"));

        ResultActions result = send(post(URL), "{\"handle\":\"myblog\",\"permission\":\"POST\"}");

        assertEquals(201, SessionOnlyApiSupport.status(result));
        assertEquals("POST", SessionOnlyApiSupport.body(result).path("permission").asString());
        assertEquals("myblog", SessionOnlyApiSupport.body(result).path("handle").asString());
        verify(users).grantWeblogPermission(blog, carol, List.of("post"));
        verify(support.tier.weblogger()).flush();
    }

    @Test
    void grantRefusesWhileGroupBloggingIsOff() throws Exception {
        when(support.tier.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));

        refused(send(post(URL), "{\"handle\":\"myblog\",\"permission\":\"POST\"}"), 409, "member.groupBloggingOff");
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantNamesAnUnknownOrSiteSpaceWeblogAndABadLevel() throws Exception {
        Weblog site = new Weblog();
        site.setHandle("site");
        site.setSiteSpace(true);
        when(support.tier.weblogManager().getWeblogByHandle("site", null)).thenReturn(site);

        ResultActions unknown = send(post(URL), "{\"handle\":\"nope\",\"permission\":\"OWNER\"}");
        assertEquals(400, SessionOnlyApiSupport.status(unknown));
        assertEquals("handle", SessionOnlyApiSupport.body(unknown).path("errors").path(0).path("field").asString());
        assertEquals("userWeblogs.weblogUnknown",
                SessionOnlyApiSupport.body(unknown).path("errors").path(0).path("code").asString());
        assertEquals("member.levelInvalid",
                SessionOnlyApiSupport.body(unknown).path("errors").path(1).path("code").asString());

        refused(send(post(URL), "{\"handle\":\"site\",\"permission\":\"POST\"}"), 400, "userWeblogs.weblogUnknown");
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantRefusesAReaderAccount() throws Exception {
        when(users.checkPermission(any(com.wikantik.pressroom.pojos.GlobalPermission.class), any())).thenReturn(false);

        refused(send(post(URL), "{\"handle\":\"myblog\",\"permission\":\"POST\"}"), 400, "member.readerAccount");
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void anUnknownAccountIs404() throws Exception {
        refused(send(post("/v1/admin/users/ghost/weblogs"), "{\"handle\":\"myblog\",\"permission\":\"POST\"}"), 404,
                "user.notFound");
    }

    @Test
    void changeGivesTheMemberTheSingleLevel() throws Exception {
        WeblogPermission current = holds("carol", "post");
        when(users.getWeblogPermission(blog, carol)).thenReturn(current);

        ResultActions result = send(patch(URL + "/myblog"), "{\"permission\":\"EDIT_DRAFT\"}");

        assertEquals(200, SessionOnlyApiSupport.status(result));
        verify(users).revokeWeblogPermission(blog, carol, WeblogPermission.ALL_ACTIONS);
        verify(users).grantWeblogPermission(blog, carol, List.of(WeblogPermission.EDIT_DRAFT));
    }

    @Test
    void changeToTheLevelHeldChangesNothing() throws Exception {
        when(users.getWeblogPermission(blog, carol)).thenReturn(holds("carol", "post"));

        assertEquals(200, SessionOnlyApiSupport.status(send(patch(URL + "/myblog"), "{\"permission\":\"POST\"}")));
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void changeOfANonMemberOrUnknownWeblogIs404AndABadLevelIs400() throws Exception {
        refused(send(patch(URL + "/myblog"), "{\"permission\":\"POST\"}"), 404, "member.notFound");
        refused(send(patch(URL + "/nope"), "{\"permission\":\"POST\"}"), 404, "member.notFound");
        when(users.getWeblogPermission(blog, carol)).thenReturn(holds("carol", "post"));
        refused(send(patch(URL + "/myblog"), "{\"permission\":\"OWNER\"}"), 400, "member.levelInvalid");
    }

    @Test
    void changeAndRemoveKeepOneAdmin() throws Exception {
        WeblogPermission only = holds("carol", "admin");
        when(users.getWeblogPermission(blog, carol)).thenReturn(only);
        when(users.getWeblogPermissions(blog)).thenReturn(List.of(only));

        refused(send(patch(URL + "/myblog"), "{\"permission\":\"POST\"}"), 409, "member.lastAdmin");
        refused(send(delete(URL + "/myblog"), null), 409, "member.lastAdmin");
        verify(users, never()).revokeWeblogPermission(any(), any(), any());
    }

    @Test
    void anAdminMayDemoteThemselvesHereBecauseTheAdminScreenHasNoSelfRule() throws Exception {
        // The person page (UserWeblogsController) applies only the last-admin rule, not the Members page's
        // self-change rule; with a second admin present the change goes through.
        WeblogPermission mine = holds("carol", "admin");
        when(users.getWeblogPermission(blog, carol)).thenReturn(mine);
        when(users.getWeblogPermissions(blog)).thenReturn(List.of(mine, holds("owen", "admin")));
        support.admin.setUserName("carol");

        assertEquals(200, SessionOnlyApiSupport.status(send(patch(URL + "/myblog"), "{\"permission\":\"POST\"}")));
    }

    @Test
    void removeRevokesAllActionsAndAnswers204() throws Exception {
        WeblogPermission current = holds("carol", "post");
        when(users.getWeblogPermission(blog, carol)).thenReturn(current);

        assertEquals(204, SessionOnlyApiSupport.status(send(delete(URL + "/myblog"), null)));
        verify(users).revokeWeblogPermission(blog, carol, WeblogPermission.ALL_ACTIONS);
        verify(support.tier.weblogger()).flush();
    }

    @Test
    void removeOfANonMemberIs404() throws Exception {
        refused(send(delete(URL + "/myblog"), null), 404, "member.notFound");
    }
}
