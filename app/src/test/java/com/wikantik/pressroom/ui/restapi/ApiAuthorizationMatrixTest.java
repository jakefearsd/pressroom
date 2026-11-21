package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.boot.SecurityConfig;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.restapi.auth.ApiAuthenticationEntryPoint;
import com.wikantik.pressroom.ui.restapi.mcp.ApiCall;
import com.wikantik.pressroom.ui.restapi.mcp.ApiReply;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRoute;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRouteTable;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRouteTables;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolCatalog;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolExecutor;
import com.wikantik.pressroom.ui.restapi.mcp.ResolvedAction;
import com.wikantik.pressroom.ui.restapi.mcp.ResolvedTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.pojos.ApiToken.Role.ADMIN;
import static com.wikantik.pressroom.pojos.ApiToken.Role.POST;
import static com.wikantik.pressroom.pojos.ApiToken.Role.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The API authorization matrix (spec 2026-11-20, caller kind and auth matrix,
 * Task 4): for every route in the API route table and every kind of caller,
 * whether the authorization layers let the request through and, if not,
 * which refusal it gets. It is the oracle a new route, or a new client such
 * as a React admin, is checked against.
 *
 * <p><b>The expected answers are hand-written, not derived from the code they
 * check.</b> {@link #ROWS} holds one row per route stating what that route
 * requires (the token role, the global permission, the weblog permission,
 * whether a password or no credential at all is accepted, whether a pinned
 * token may call it although it names no weblog), taken from the public
 * contract in {@code docs/api/README.md} and the JSP pages the routes mirror,
 * never read from {@code UISecurityEnforced}, {@code @AdminScoped} or
 * {@code @WeblogScopeExempt}. Each cell is computed from its row and its
 * caller by {@link #expected}, a few lines that restate the error contract.
 * A route with no row fails, named by method and path.
 *
 * <p><b>Harness.</b> Requests go through the real security chains
 * ({@link SecurityConfig}, via {@code springSecurity()}, as in
 * {@code ApiPasswordScopeTest}) and a real {@code DispatcherServlet} built
 * from the production {@link WebMvcConfig}, so {@code
 * PressroomHandlerInterceptor} and {@code ApiScopeInterceptor} decide in their
 * real order (the recipe of {@code WeblogSettingsApiDispatchTest}). Every real
 * API controller is registered (collaborators null), so the routes dispatched
 * are exactly the route table's. A test-only terminal interceptor, ordered
 * after the production ones, answers {@value #REACHED} to any request that
 * gets to it, so no handler (business code) ever runs.
 *
 * <p><b>Body binding.</b> Interceptors run before Spring resolves handler
 * arguments, so the terminal interceptor answers before any {@code
 * @RequestBody} is read or validated: a 400 from body parsing cannot mask an
 * authorization answer (criterion 3). Writes still carry a well-formed body
 * ({@code {}}, or a multipart content type for the upload route, whose
 * mapping requires one).
 *
 * <p><b>What "reached" means.</b> The authorization layers let the request
 * through. A refusal a handler makes itself is outside this matrix and
 * pinned where it is made: {@code TokensApi} refuses every token caller
 * (403, {@code TokensApiTest}), and {@code AdminAccountGuard} refuses changes
 * to admin and agent accounts ({@code AdminApiTest}).
 *
 * <p><b>The site.</b> Every {@code {handle}} names the weblog {@value #TARGET}.
 * {@code root} is a site admin. {@code owen} owns {@value #TARGET} and
 * {@value #OTHER} (weblog {@code ADMIN} on both). {@code abe} holds {@code
 * POST} on {@value #TARGET}, {@code dora} {@code EDIT_DRAFT}, {@code sam}
 * nothing; {@code robot} is an agent account holding {@code POST} on
 * {@value #TARGET}. Everyone but {@code root} holds the ordinary global
 * {@code weblog} role, so the global column tells a site admin from everyone
 * else and nothing finer: no caller here lacks the {@code login} action. The
 * permission stub answers with the real {@code implies} ladders over these
 * grants.
 *
 * <p><b>Adding a route</b>: add its row to {@link #ROWS}, stating what it
 * should require; if the matrix disagrees, either the row or the route is
 * wrong, and the difference is the conversation to have.
 */
class ApiAuthorizationMatrixTest {

    /** What the terminal interceptor answers: the request passed every authorization layer. */
    static final int REACHED = 299;
    private static final String REACHED_BODY = "{\"matrix\":\"reached\"}";

    /** The weblog every {@code {handle}} names. */
    private static final String TARGET = "own";
    /** A second weblog {@code owen} owns, to pin a token to. */
    private static final String OTHER = "other";

    // ------------------------------------------------------------------ the oracle

    /** Global permissions, weakest first. */
    private static final List<String> GLOBAL_LADDER =
            List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.ADMIN);
    /** Weblog permissions, weakest first. */
    private static final List<String> WEBLOG_LADDER =
            List.of(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST, WeblogPermission.ADMIN);

    private static final String NO_GLOBAL = null;
    private static final String SITE_LOGIN = GlobalPermission.LOGIN;
    private static final String SITE_ADMIN = GlobalPermission.ADMIN;
    private static final String NO_WEBLOG = null;
    private static final String ON_DRAFT = WeblogPermission.EDIT_DRAFT;
    private static final String ON_POST = WeblogPermission.POST;
    private static final String ON_ADMIN = WeblogPermission.ADMIN;

    /** What a route accepts beyond a bearer token. */
    enum Flag {
        /** No credential needed ({@code permitAll}); nothing below the chain asks for one. */
        PUBLIC,
        /** HTTP Basic is accepted too (the token resource). */
        PASSWORD_TOO,
        /** A pinned token may call it although it names no weblog. */
        PIN_EXEMPT
    }

    /**
     * What one route requires. {@code role} is the least token role, {@code
     * global} the global permission ({@code null}: none asked), {@code weblog}
     * the permission on the {@code {handle}} weblog ({@code null}: none asked).
     * Whether it names a weblog is read off its path.
     */
    record Row(String key, ApiToken.Role role, String global, String weblog, Set<Flag> flags) {
        boolean namesAWeblog() {
            return key.contains("{handle}");
        }

        boolean has(Flag flag) {
            return flags.contains(flag);
        }
    }

    private static Row row(String route, ApiToken.Role role, String global, String weblog, Flag... flags) {
        return new Row(route.trim().replaceAll("\\s+", " "), role, global, weblog, Set.of(flags));
    }

    /** One row per route of the API route table. Sections follow {@code docs/api/README.md}. */
    static final List<Row> ROWS = List.of(
            // --- Meta: ping needs nothing; me any token, pinned or not
            row("GET    /v1/ping",                                    READ,  NO_GLOBAL,  NO_WEBLOG, Flag.PUBLIC,
                    Flag.PIN_EXEMPT),
            row("GET    /v1/me",                                      READ,  NO_GLOBAL,  NO_WEBLOG, Flag.PIN_EXEMPT),

            // --- The token resource: a password too. Not pin-exempt, and the
            //     role ceiling still applies to a token; TokensApi then refuses
            //     every token itself (outside this matrix). The role and pin
            //     columns here record what the interceptors do today, not a
            //     published contract: the contract is that no token gets in.
            row("GET    /v1/tokens",                                  READ,  NO_GLOBAL,  NO_WEBLOG, Flag.PASSWORD_TOO),
            row("POST   /v1/tokens",                                  POST,  NO_GLOBAL,  NO_WEBLOG, Flag.PASSWORD_TOO),
            row("DELETE /v1/tokens/{id}",                             POST,  NO_GLOBAL,  NO_WEBLOG, Flag.PASSWORD_TOO),

            // --- Entries: reads READ; writes a POST- or ADMIN-role token and, on the weblog, what the JSP
            //     editor asks: EDIT_DRAFT, the contributor's level (spec 2026-11-21 owner decisions, Task 3).
            //     Past this layer the handler holds a contributor to the editor's rules (a publish is
            //     submitted for review, only their own drafts and pending entries change); the sentinel
            //     stops before that, so EntriesWriteApiDispatchTest pins it. Restore and delete-forever
            //     take POST, as the Trash page does (EntriesTrashApi).
            row("GET    /v1/weblogs/{handle}/entries",                READ,  SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/entries/{id}",           READ,  SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/entries/by-anchor/{anchor}", READ, SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/entries/{id}/revisions", READ,  SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}", READ, SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries",                POST,  SITE_LOGIN, ON_DRAFT),
            row("PATCH  /v1/weblogs/{handle}/entries/{id}",           POST,  SITE_LOGIN, ON_DRAFT),
            row("DELETE /v1/weblogs/{handle}/entries/{id}",           POST,  SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries/{id}/restore",   POST,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/entries/{id}/delete-forever", POST, SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/entries/{id}/preview",   POST,  SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries/preview",        POST,  SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/restore", POST, SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/keep", POST, SITE_LOGIN, ON_DRAFT),
            row("POST   /v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/unkeep", POST, SITE_LOGIN, ON_DRAFT),

            // --- Categories and tags: blog-wide structure, a publisher's (POST), as on the Categories page
            row("GET    /v1/weblogs/{handle}/categories",             READ,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/categories",             POST,  SITE_LOGIN, ON_POST),
            row("PATCH  /v1/weblogs/{handle}/categories/{id}",        POST,  SITE_LOGIN, ON_POST),
            row("DELETE /v1/weblogs/{handle}/categories/{id}",        POST,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/tags",                   READ,  SITE_LOGIN, ON_POST),

            // --- Media: as the Media Files pages (POST)
            row("GET    /v1/weblogs/{handle}/media",                  READ,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/media/{id}",             READ,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/media",                  POST,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/media/imports",          POST,  SITE_LOGIN, ON_POST),
            row("PATCH  /v1/weblogs/{handle}/media/{id}",             POST,  SITE_LOGIN, ON_POST),
            row("DELETE /v1/weblogs/{handle}/media/{id}",             POST,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/media/directories",      READ,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/media/directories",      POST,  SITE_LOGIN, ON_POST),

            // --- Pages: as the Pages page (POST)
            row("GET    /v1/weblogs/{handle}/pages",                  READ,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/pages/{id}",             READ,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages",                  POST,  SITE_LOGIN, ON_POST),
            row("PATCH  /v1/weblogs/{handle}/pages/{id}",             POST,  SITE_LOGIN, ON_POST),
            row("DELETE /v1/weblogs/{handle}/pages/{id}",             POST,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages/{id}/restore",     POST,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages/{id}/delete-forever", POST, SITE_LOGIN, ON_POST),
            row("PUT    /v1/weblogs/{handle}/pages/{id}/parts",       POST,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/pages/{id}/revisions",   READ,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}", READ, SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/restore", POST, SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/keep", POST, SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/unkeep", POST, SITE_LOGIN, ON_POST),

            // --- Redirects: POST permission (docs/dev/routing.md)
            row("GET    /v1/weblogs/{handle}/redirects",              READ,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/redirects",              POST,  SITE_LOGIN, ON_POST),
            row("DELETE /v1/weblogs/{handle}/redirects/{id}",         POST,  SITE_LOGIN, ON_POST),

            // --- Comments: moderation is for the weblog's authors (POST), as on the Comments page
            row("GET    /v1/weblogs/{handle}/comments",               READ,  SITE_LOGIN, ON_POST),
            row("GET    /v1/weblogs/{handle}/comments/{id}",          READ,  SITE_LOGIN, ON_POST),
            row("PATCH  /v1/weblogs/{handle}/comments/{id}",          POST,  SITE_LOGIN, ON_POST),
            row("DELETE /v1/weblogs/{handle}/comments/{id}",          POST,  SITE_LOGIN, ON_POST),
            row("POST   /v1/weblogs/{handle}/comments/{id}/replies",  POST,  SITE_LOGIN, ON_POST),

            // --- Audits: reads, READ is enough
            row("GET    /v1/weblogs/{handle}/audit/seo",              READ,  SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/audit/seo/entries/{id}", READ,  SITE_LOGIN, ON_DRAFT),
            row("GET    /v1/weblogs/{handle}/audit/media",            READ,  SITE_LOGIN, ON_DRAFT),

            // --- Traffic: a weblog admin, as the Traffic page; a READ token is enough
            row("GET    /v1/weblogs/{handle}/traffic",                READ,  SITE_LOGIN, ON_ADMIN),

            // --- Weblogs: the list is the site admin's; create follows the Create Weblog page;
            //     one weblog's settings are its owner's, with an ADMIN token
            row("GET    /v1/weblogs",                                 ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("POST   /v1/weblogs",                                 POST,  SITE_LOGIN, NO_WEBLOG),
            row("GET    /v1/weblogs/{handle}",                        ADMIN, SITE_LOGIN, ON_ADMIN),
            row("PATCH  /v1/weblogs/{handle}",                        ADMIN, SITE_LOGIN, ON_ADMIN),

            // --- Site administration: a site admin with an ADMIN token
            row("GET    /v1/admin/users",                             ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("POST   /v1/admin/users",                             ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("PATCH  /v1/admin/users/{userName}",                  ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("GET    /v1/admin/config",                            ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("PATCH  /v1/admin/config",                            ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("GET    /v1/admin/revisions",                         ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("POST   /v1/admin/revisions/sweep",                   ADMIN, SITE_ADMIN, NO_WEBLOG),

            // --- Maintenance actions: the site admin's, as the Maintenance screen. They name a
            //     weblog, so a token pinned to it may run them (the pin is a ceiling on weblogs);
            //     they ask no permission on it. The README once said every /admin/** route refuses
            //     a pinned token; it was narrowed to the routes that name no weblog (2026-11-20)
            row("POST   /v1/admin/weblogs/{handle}/actions/flush-cache",  ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("POST   /v1/admin/weblogs/{handle}/actions/rebuild-index", ADMIN, SITE_ADMIN, NO_WEBLOG),
            row("POST   /v1/admin/weblogs/{handle}/actions/regenerate-renditions", ADMIN, SITE_ADMIN, NO_WEBLOG));

    /** How a caller authenticates. */
    enum Kind { ANONYMOUS, PASSWORD, TOKEN }

    /** A person in the fixture: their global role and their weblog roles. */
    record Person(String name, String global, Map<String, String> weblogs, boolean agent) {
        boolean hasGlobal(String asked) {
            return GLOBAL_LADDER.indexOf(global) >= GLOBAL_LADDER.indexOf(asked);
        }

        /** A site admin holds every weblog permission. */
        boolean holds(String handle, String asked) {
            if (GlobalPermission.ADMIN.equals(global)) {
                return true;
            }
            String held = weblogs.get(handle);
            return held != null && WEBLOG_LADDER.indexOf(held) >= WEBLOG_LADDER.indexOf(asked);
        }
    }

    private static final Person ROOT = new Person("root", GlobalPermission.ADMIN, Map.of(), false);
    private static final Person OWEN = new Person("owen", GlobalPermission.WEBLOG,
            Map.of(TARGET, WeblogPermission.ADMIN, OTHER, WeblogPermission.ADMIN), false);
    private static final Person ABE = new Person("abe", GlobalPermission.WEBLOG,
            Map.of(TARGET, WeblogPermission.POST), false);
    private static final Person DORA = new Person("dora", GlobalPermission.WEBLOG,
            Map.of(TARGET, WeblogPermission.EDIT_DRAFT), false);
    private static final Person SAM = new Person("sam", GlobalPermission.WEBLOG, Map.of(), false);
    private static final Person ROBOT = new Person("robot", GlobalPermission.WEBLOG,
            Map.of(TARGET, WeblogPermission.POST), true);
    private static final List<Person> PEOPLE = List.of(ROOT, OWEN, ABE, DORA, SAM, ROBOT);

    /** One kind of caller: how it authenticates, as whom, and its token's ceiling. */
    record Who(String name, Kind kind, Person person, ApiToken.Role role, String pin) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Who token(String name, Person person, ApiToken.Role role, String pin) {
        return new Who(name, Kind.TOKEN, person, role, pin);
    }

    static final List<Who> CALLERS = List.of(
            new Who("anonymous", Kind.ANONYMOUS, null, null, null),
            new Who("password(site admin)", Kind.PASSWORD, ROOT, null, null),
            token("READ token(site admin)", ROOT, READ, null),
            token("POST token(site admin)", ROOT, POST, null),
            token("ADMIN token(site admin)", ROOT, ADMIN, null),
            token("ADMIN token(site admin, pinned here)", ROOT, ADMIN, TARGET),
            token("ADMIN token(owner)", OWEN, ADMIN, null),
            token("ADMIN token(owner, pinned here)", OWEN, ADMIN, TARGET),
            token("ADMIN token(owner, pinned elsewhere)", OWEN, ADMIN, OTHER),
            token("ADMIN token(POST member)", ABE, ADMIN, null),
            token("ADMIN token(POST member, pinned elsewhere)", ABE, ADMIN, OTHER),
            token("ADMIN token(EDIT_DRAFT member)", DORA, ADMIN, null),
            token("ADMIN token(no role)", SAM, ADMIN, null),
            token("POST token(agent, POST member)", ROBOT, POST, null));

    /**
     * The cell: what {@code who} gets on {@code row}. The error contract, in
     * the order the layers apply it: the chain (401), the global permission
     * (403), the weblog permission (404 for a caller who may not see the
     * weblog: no role on it, or a token pinned elsewhere; 403 for a member
     * below the level), then the token's ceiling: its pin (404) and its role
     * (403). The global refusal comes first by design: on a site-admin route
     * that names no weblog, a pinned token whose owner is not a site admin
     * gets 403, and only a site admin's pinned token reaches the pin's 404.
     */
    static int expected(Row row, Who who) {
        if (row.has(Flag.PUBLIC)) {
            return REACHED;
        }
        if (who.kind() == Kind.ANONYMOUS || (who.kind() == Kind.PASSWORD && !row.has(Flag.PASSWORD_TOO))) {
            return 401;
        }
        Person person = who.person();
        if (row.global() != null && !person.hasGlobal(row.global())) {
            return 403;
        }
        boolean pinAllowsTarget = who.pin() == null || TARGET.equals(who.pin());
        if (row.weblog() != null && (!pinAllowsTarget || !person.holds(TARGET, row.weblog()))) {
            boolean member = pinAllowsTarget && person.holds(TARGET, WeblogPermission.EDIT_DRAFT);
            return member ? 403 : 404;
        }
        if (who.kind() == Kind.TOKEN) {
            boolean pinAllowsRoute = row.namesAWeblog() ? pinAllowsTarget : who.pin() == null || row.has(Flag.PIN_EXEMPT);
            if (!pinAllowsRoute) {
                return 404;
            }
            if (who.role().ordinal() < row.role().ordinal()) {
                return 403;
            }
        }
        return REACHED;
    }

    // ------------------------------------------------------------------ the harness

    private static MockWeblogger tier;
    private static WebloggerProvider provider;
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, ApiToken> TOKENS = new HashMap<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final String RAW_TOKEN_PREFIX = "prs_matrix_";
    /** Routes whose mapping requires a multipart body. */
    private static final Set<String> MULTIPART = Set.of("POST /v1/weblogs/{handle}/media");

    /** Answers {@value #REACHED} to whatever gets past the production interceptors. */
    static final class Terminal implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            response.setStatus(REACHED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(REACHED_BODY);
            return false;
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class MatrixConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WebloggerProvider webloggerProvider() {
            return provider;
        }

        @Bean
        PressroomHandlerInterceptor pressroomHandlerInterceptor() {
            return new PressroomHandlerInterceptor(provider, tier.weblogger());
        }

        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor pressroomHandlerInterceptor) {
            return new WebMvcConfig(pressroomHandlerInterceptor);
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }

        /** The terminal interceptor, after the production ones (the registry sorts by order, stably). */
        @Bean
        WebMvcConfigurer terminalInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new Terminal()).order(Ordered.LOWEST_PRECEDENCE);
                }
            };
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        tier = MockWeblogger.create();
        provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        stubTheSite();

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MatrixConfig.class);
        // Registered as finished singletons, so nothing is injected into them: only their mappings matter.
        context.addBeanFactoryPostProcessor(factory -> ApiRouteTables.controllers()
                .forEach(controller -> factory.registerSingleton(controller.getClass().getName(), controller)));
        context.refresh();
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private static void stubTheSite() throws Exception {
        Map<String, Weblog> weblogs = new HashMap<>();
        for (String handle : List.of(TARGET, OTHER)) {
            Weblog weblog = new Weblog();
            weblog.setId("weblog-" + handle);
            weblog.setHandle(handle);
            weblog.setName("Blog " + handle);
            weblog.setActive(Boolean.TRUE);
            weblog.setSiteSpace(false);
            weblogs.put(handle, weblog);
            when(tier.weblogManager().getWeblogByHandle(handle)).thenReturn(weblog);
        }
        Map<String, Person> byName = new HashMap<>();
        Map<String, User> users = new HashMap<>();
        for (Person person : PEOPLE) {
            User user = new User();
            user.setUserName(person.name());
            user.setPassword(TestUtils.TEST_PASSWORD_HASH);
            user.setEnabled(Boolean.TRUE);
            if (person.agent()) {
                user.setKind(User.Kind.AGENT);
            }
            byName.put(person.name(), person);
            users.put(person.name(), user);
            when(tier.userManager().getUserByUserName(person.name())).thenReturn(user);
            when(tier.userManager().getRoles(user)).thenReturn(
                    GlobalPermission.ADMIN.equals(person.global()) ? List.of("admin") : List.of("editor"));
        }
        // The real ladders (GlobalPermission/WeblogPermission.implies) over the fixture's grants.
        when(tier.userManager().checkPermission(any(), any())).thenAnswer(call -> {
            Person person = byName.get(((User) call.getArgument(1)).getUserName());
            if (call.getArgument(0) instanceof GlobalPermission asked) {
                return new GlobalPermission(List.of(person.global())).implies(asked);
            }
            if (call.getArgument(0) instanceof WeblogPermission asked) {
                if (GlobalPermission.ADMIN.equals(person.global())) {
                    return true;
                }
                String level = person.weblogs().get(asked.getObjectId());
                return level != null && new WeblogPermission(weblogs.get(asked.getObjectId()), List.of(level))
                        .implies(asked);
            }
            return false;
        });
        for (Who who : CALLERS) {
            if (who.kind() == Kind.TOKEN) {
                ApiToken token = new ApiToken();
                token.setId("token-" + TOKENS.size());
                token.setUser(users.get(who.person().name()));
                token.setScopeRole(who.role());
                token.setScopeWeblog(who.pin());
                TOKENS.put(Integer.toString(CALLERS.indexOf(who)), token);
            }
        }
        // Each request presents a fresh raw token naming its caller, so the throttle (keyed by token) never trips.
        when(tier.apiTokenManager().authenticate(anyString())).thenAnswer(call -> {
            String raw = call.getArgument(0);
            return raw.startsWith(RAW_TOKEN_PREFIX)
                    ? TOKENS.get(raw.substring(RAW_TOKEN_PREFIX.length(), raw.lastIndexOf('_'))) : null;
        });
    }

    /**
     * One request as {@code who}. Each comes from its own address, so the
     * throttle (keyed by address without a token) never trips either.
     */
    private static MvcResult send(Who who, String method, String path, String query, String body,
                                  String contentType) throws Exception {
        int n = REQUESTS.incrementAndGet();
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), URI.create("/api" + path + query))
                .servletPath("/api").pathInfo(UriUtils.decode(path, StandardCharsets.UTF_8))
                .with(r -> {
                    r.setRemoteAddr("10.29." + n / 250 + "." + (n % 250 + 1));
                    return r;
                });
        if (who.kind() == Kind.PASSWORD) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (who.person().name() + ":" + TestUtils.TEST_PASSWORD).getBytes(StandardCharsets.UTF_8)));
        } else if (who.kind() == Kind.TOKEN) {
            request.header("Authorization", "Bearer " + RAW_TOKEN_PREFIX + CALLERS.indexOf(who) + "_" + n);
        }
        if (body != null) {
            request.contentType(contentType).content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String concretePath(String template) {
        return template.replace("{handle}", TARGET).replaceAll("\\{([^}/]+)\\}", "$1-1");
    }

    private static MvcResult send(Who who, ApiRoute route) throws Exception {
        String path = concretePath(route.pathTemplate());
        boolean write = !"GET".equals(route.method());
        if (MULTIPART.contains(route.key())) {
            return send(who, route.method(), path, "", "", "multipart/form-data; boundary=matrix");
        }
        return send(who, route.method(), path, "", write ? "{}" : null, MediaType.APPLICATION_JSON_VALUE);
    }

    private static String label(int status) {
        return status == REACHED ? "reached" : Integer.toString(status);
    }

    private static Map<String, Row> rowsByKey() {
        Map<String, Row> rows = new LinkedHashMap<>();
        ROWS.forEach(r -> rows.put(r.key(), r));
        return rows;
    }

    /** The routes the dispatch context serves, read from its own handler mapping. */
    private static ApiRouteTable liveRoutes() {
        return ApiRouteTable.from(context.getBean(RequestMappingHandlerMapping.class));
    }

    private static void assertCell(int expected, MvcResult result, String cell) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(label(expected), label(status), cell + " answered " + label(status) + ": " + body);
        if (expected != REACHED) {
            String type = result.getResponse().getContentType();
            assertTrue(type != null && type.startsWith("application/problem+json"),
                    cell + ": a refusal under /api is problem+json, was " + type + ": " + body);
        }
    }

    // ------------------------------------------------------------------ criterion 1: every route has a row

    @Test
    void everyRouteHasExactlyOneRowAndEveryRowNamesALiveRoute() {
        Set<String> live = new TreeSet<>();
        liveRoutes().all().forEach(r -> live.add(r.key()));
        Set<String> real = new TreeSet<>();
        ApiRouteTables.real().all().forEach(r -> real.add(r.key()));
        assertEquals(real, live, "the matrix dispatches to a different route table than ApiRouteTables.real()");

        Set<String> rows = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        ROWS.forEach(r -> {
            if (!rows.add(r.key())) {
                duplicated.add(r.key());
            }
        });
        Set<String> missing = new TreeSet<>(live);
        missing.removeAll(rows);
        Set<String> stale = new TreeSet<>(rows);
        stale.removeAll(live);
        assertEquals(List.of(), duplicated, "routes with two rows");
        assertEquals(Set.of(), missing, "routes with no row in the authorization matrix");
        assertEquals(Set.of(), stale, "rows naming no live route");
    }

    // ------------------------------------------------------------------ criteria 2-4: the cells

    @TestFactory
    Stream<DynamicTest> everyRouteAnswersEveryCallerAsItsRowSays() {
        Map<String, Row> rows = rowsByKey();
        List<DynamicTest> cells = new ArrayList<>();
        for (ApiRoute route : liveRoutes().all()) {
            Row row = rows.get(route.key());
            if (row == null) {
                cells.add(DynamicTest.dynamicTest(route.key() + " | no row",
                        () -> fail(route.key() + " has no row in ApiAuthorizationMatrixTest.ROWS")));
                continue;
            }
            for (Who who : CALLERS) {
                int expected = expected(row, who);
                String cell = route.key() + " | " + who + " | expect " + label(expected);
                cells.add(DynamicTest.dynamicTest(cell, () -> assertCell(expected, send(who, route), cell)));
            }
        }
        return cells.stream();
    }

    /** The rule is not vacuous: across the matrix every outcome occurs. */
    @Test
    void theMatrixExercisesEveryOutcome() {
        Set<Integer> outcomes = new TreeSet<>();
        ROWS.forEach(row -> CALLERS.forEach(who -> outcomes.add(expected(row, who))));
        assertEquals(new TreeSet<>(List.of(REACHED, 401, 403, 404)), outcomes);
    }

    // ------------------------------------------------------------------ criterion 5: MCP

    /** A tool call as the MCP endpoint makes it, for a token caller: one action per controller. */
    record McpSample(String tool, String action, Map<String, Object> args) { }

    private static final Map<String, Object> W = Map.of("weblog", TARGET);
    private static final Map<String, Object> W_ID = Map.of("weblog", TARGET, "id", "id-1");

    static final List<McpSample> MCP_SAMPLES = List.of(
            new McpSample("whoami", "", Map.of()),
            new McpSample("weblogs", "list", Map.of()),
            new McpSample("weblogs", "create", Map.of()),
            new McpSample("weblogs", "update", W),
            new McpSample("entries", "get", W_ID),
            new McpSample("entries", "create", W),
            new McpSample("entries", "restore", W_ID),
            new McpSample("categories", "create", W),
            new McpSample("media", "import", W),
            new McpSample("pages", "update", W_ID),
            new McpSample("redirects", "delete", W_ID),
            new McpSample("comments", "reply", W_ID),
            new McpSample("audits", "seo", W),
            new McpSample("traffic", "get", W),
            new McpSample("users", "list", Map.of()),
            new McpSample("maintenance", "flush_cache", W));

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static ResolvedAction.Variant variant(McpSample sample) {
        return tool(sample.tool()).actions().get(sample.action()).select(sample.args());
    }

    /**
     * Every controller an MCP tool reaches has a sample, so a new controller
     * family behind a tool gains one here.
     */
    @Test
    void theMcpSamplesCoverEveryControllerBehindATool() {
        Set<String> behindTools = new TreeSet<>();
        TOOLS.forEach(t -> t.actions().values().forEach(a -> a.variants()
                .forEach(v -> behindTools.add(v.route().handler().getBeanType().getSimpleName()))));
        Set<String> sampled = new TreeSet<>();
        MCP_SAMPLES.forEach(s -> sampled.add(variant(s).route().handler().getBeanType().getSimpleName()));
        assertEquals(behindTools, sampled);
    }

    /**
     * A tool call answers exactly as its route's cell, for every token caller.
     * The dispatcher stands in for the container forward
     * ({@code InProcessApiDispatcher}): it sends the call through the same
     * chains and interceptors with the same token, as the forward runs with
     * the MCP request's security context (pinned in a real container by
     * {@code InProcessDispatchContainerTest} and {@code McpIT}).
     */
    @TestFactory
    Stream<DynamicTest> aToolCallAnswersAsItsRouteDoes() {
        Map<String, Row> rows = rowsByKey();
        List<DynamicTest> cells = new ArrayList<>();
        for (McpSample sample : MCP_SAMPLES) {
            String routeKey = variant(sample).route().key();
            for (Who who : CALLERS) {
                if (who.kind() != Kind.TOKEN) {
                    continue;
                }
                int expected = expected(rows.get(routeKey), who);
                String cell = "mcp " + sample.tool() + (sample.action().isEmpty() ? "" : "." + sample.action())
                        + " -> " + routeKey + " | " + who + " | expect " + label(expected);
                cells.add(DynamicTest.dynamicTest(cell, () -> assertToolCell(expected, callTool(who, sample), cell)));
            }
        }
        return cells.stream();
    }

    private static CallToolResult callTool(Who who, McpSample sample) {
        Map<String, Object> args = new HashMap<>(sample.args());
        if (!sample.action().isEmpty()) {
            args.put("action", sample.action());
        }
        McpToolExecutor executor = new McpToolExecutor((mcpRequest, call) -> dispatch(who, call));
        return executor.call(new MockHttpServletRequest("POST", "/api/mcp"), tool(sample.tool()), args);
    }

    private static ApiReply dispatch(Who who, ApiCall call) {
        StringBuilder query = new StringBuilder();
        call.query().forEach((name, values) -> values.forEach(value -> query.append(query.isEmpty() ? '?' : '&')
                .append(UriUtils.encode(name, StandardCharsets.UTF_8)).append('=')
                .append(UriUtils.encode(value, StandardCharsets.UTF_8))));
        try {
            MvcResult result = send(who, call.method(), call.path(), query.toString(), call.jsonBody(),
                    MediaType.APPLICATION_JSON_VALUE);
            return new ApiReply(result.getResponse().getStatus(), result.getResponse().getContentType(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertToolCell(int expected, CallToolResult result, String cell) {
        String text = ((TextContent) result.content().get(0)).text();
        if (expected == REACHED) {
            assertFalse(result.isError(), cell + ": " + text);
            assertEquals(REACHED_BODY, text, cell);
            return;
        }
        assertTrue(result.isError(), cell + ": " + text);
        assertEquals(expected, JSON.readTree(text).path("status").asInt(), cell + ": " + text);
    }

    /**
     * The endpoint itself admits tokens only: no credential and a password are
     * the chain's 401 (the password one saying where a password is accepted).
     * A token passes the chain; with no MCP transport in this harness, the
     * dispatcher then finds no handler (404). {@code McpEndpointServletTest}
     * pins the servlet's own token-only check behind it.
     */
    @TestFactory
    Stream<DynamicTest> theMcpEndpointAdmitsTokensOnly() {
        return CALLERS.stream().map(who -> {
            int expected = who.kind() == Kind.TOKEN ? 404 : 401;
            String cell = "POST /api/mcp | " + who + " | expect " + expected;
            return DynamicTest.dynamicTest(cell, () -> {
                MvcResult result = send(who, "POST", "/mcp", "", "{}", MediaType.APPLICATION_JSON_VALUE);
                String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertEquals(expected, result.getResponse().getStatus(), cell + ": " + body);
                if (who.kind() == Kind.PASSWORD) {
                    assertEquals(ApiAuthenticationEntryPoint.PASSWORD_REFUSED_DETAIL,
                            JSON.readTree(body).path("detail").asString(), cell);
                }
            });
        });
    }
}
