package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.auth.PasswordCallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the token resource carries its own code, with the English
 * it always had (spec 2026-11-20-api-contract-design, Task 3). The English
 * half passed before Task 3 (characterisation); the code half is what it adds.
 */
class TokenProblemCodesTest {

    private UserManager users;
    private ApiTokenManager tokens;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        tokens = mock(ApiTokenManager.class);
        users = mock(UserManager.class);
        when(weblogger.getApiTokenManager()).thenReturn(tokens);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        User owner = new User();
        owner.setUserName("owner");
        when(users.getUserByUserName("owner")).thenReturn(owner);
        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new TokensApi(weblogger))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void basic() {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("owner").password("n/a").authorities(List.of()).build();
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        auth.setDetails(PasswordCallerDetails.SOURCE.buildDetails(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void bearer() {
        var auth = new UsernamePasswordAuthenticationToken("owner", null, List.of());
        auth.setDetails(new ApiPrincipal("owner", null, ApiToken.Role.ADMIN, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private JsonNode mint(String body) throws Exception {
        return answer(status().isBadRequest(), post("/v1/tokens").content(body));
    }

    @Test
    void aTokenCallerIsRefusedWithTheTokenCodes() throws Exception {
        bearer();
        problem(answer(status().isForbidden(), post("/v1/tokens").content("{\"label\":\"x\",\"role\":\"READ\"}")),
                "token.mintRefused", "A token cannot be used to mint another token.");
        problem(answer(status().isForbidden(), get("/v1/tokens")),
                "token.managementNeedsPassword", "Token management requires Basic authentication.");
        problem(answer(status().isForbidden(), delete("/v1/tokens/t1")),
                "token.managementNeedsPassword", "Token management requires Basic authentication.");
    }

    @Test
    void aReaderAccountIsAuthApiNotAllowed() throws Exception {
        basic();
        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(false);
        problem(answer(status().isForbidden(), get("/v1/tokens")),
                "auth.apiNotAllowed", "This account cannot use the API.");
    }

    @Test
    void theMintFieldRefusalsHaveTheirCodes() throws Exception {
        basic();
        oneField(mint("{\"label\":\"x\",\"role\":\"OWNER\"}"), "role", "token.roleUnknown", "Unknown role: OWNER");
        oneField(mint("{\"role\":\"READ\"}"), "label", "field.required", "label is required.");
        oneField(mint("{\"label\":\"x\",\"role\":\"READ\",\"weblog\":\"elsewhere\"}"), "weblog",
                "token.weblogNotMember", "weblog must name a weblog you are a member of; 'elsewhere' is not one.");
        oneField(mint("{\"label\":\"x\",\"role\":\"READ\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}"), "expiresAt",
                "token.expiryNotInFuture", "expiresAt must be in the future.");
    }

    @Test
    void anUnknownTokenIsTokenNotFound() throws Exception {
        basic();
        problem(answer(status().isNotFound(), delete("/v1/tokens/nope")), "token.notFound", "No such token.");
    }
}
