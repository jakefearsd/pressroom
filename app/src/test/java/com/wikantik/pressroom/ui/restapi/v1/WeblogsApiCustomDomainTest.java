package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code WeblogsApi}'s handling of {@code customDomain} on GET/PATCH
 * {@code /v1/weblogs/{handle}}. The point of this class is that the 400/409
 * outcomes come from {@link com.wikantik.pressroom.ui.controllers.CustomDomainRules}
 * -- the same class {@code WeblogConfigController} calls for the JSP Weblog
 * Settings form -- not from a second, API-side reimplementation of the
 * hostname rule that could drift from it.
 *
 * <p>Calls {@code WeblogsApi}'s handler methods directly rather than through
 * MockMvc (contrast {@code WeblogsApiTest}): {@code ApiException} is
 * unchecked, so a direct call lets {@code assertThrows} capture it and read
 * {@code getStatus()} without a round trip through {@code
 * ApiExceptionHandler} and a serialized problem-detail body.
 */
class WeblogsApiCustomDomainTest {

    private WeblogsApi weblogsApi;
    private WeblogManager weblogManager;
    private MockWeblogger mocks;
    private Weblog vhostblog;
    private Weblog plainblog;

    @BeforeEach
    void setUp() throws WebloggerException {
        // Installed into the static locator (not just this
        // controller's own field) because CustomDomainRules.isSiteHost (I4b)
        // is checked against WebloggerRuntimeConfig.getPropertyWithConfigFallback,
        // which reaches the business tier through TestUtils.weblogger()
        // rather than through weblogsApi.weblogger -- the same seam
        // EditorControllerTestSupport's givenRuntimeProperty relies on.
        mocks = MockWeblogger.attached();
        weblogManager = mocks.getWeblogManager();

        weblogsApi = new WeblogsApi();
        weblogsApi.weblogger = mocks.weblogger();

        vhostblog = aWeblog("vhostblog");
        vhostblog.setCustomDomain("vhost.example.com");
        plainblog = aWeblog("plainblog");

        // The only claimant relevant to these tests: a lookup for any other
        // hostname legitimately returns null (nobody holds it), matching a
        // fresh Mockito stub's default.
        when(weblogManager.getWeblogByCustomDomain("vhost.example.com")).thenReturn(vhostblog);
    }

    @AfterEach
    void tearDown() {
        mocks.detach();
    }

    /** Stubs the site.absoluteurl runtime property, same helper shape as EditorControllerTestSupport. */
    private void givenRuntimeProperty(String name, String value) throws WebloggerException {
        when(mocks.getPropertiesManager().getProperty(name))
                .thenReturn(new RuntimeConfigProperty(name, value));
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

    private HttpServletRequest requestFor(String handle) {
        Weblog weblog = "vhostblog".equals(handle) ? vhostblog : plainblog;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("actionWeblog")).thenReturn(weblog);
        return request;
    }

    private AdminDtos.WeblogView get(String handle) {
        return weblogsApi.get(requestFor(handle));
    }

    private AdminDtos.WeblogView patch(String handle, AdminDtos.WeblogPatch body) throws WebloggerException {
        return weblogsApi.update(requestFor(handle), body);
    }

    @Test
    void theWeblogViewCarriesTheCustomDomain() throws Exception {
        AdminDtos.WeblogView view = get("vhostblog");
        assertEquals("vhost.example.com", view.customDomain());
    }

    @Test
    void aPatchSetsTheCustomDomain() throws Exception {
        patch("vhostblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "moved.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals("moved.example.com",
                get("vhostblog").customDomain());
    }

    /** Same rules as the JSP editor, because both call CustomDomainRules. */
    @Test
    void aMalformedCustomDomainIsA400() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("vhostblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "not a hostname",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals(400, thrown.getStatus());
    }

    @Test
    void aDuplicateCustomDomainIsA409() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("plainblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "vhost.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals(409, thrown.getStatus());
    }

    /**
     * The uniqueness check finds the weblog itself as "the claimant" when a
     * PATCH re-submits its own already-stored domain unchanged -- that must
     * not be treated as a conflict with itself.
     */
    @Test
    void reSavingAWeblogsOwnUnchangedCustomDomainIsNotAConflict() throws Exception {
        AdminDtos.WeblogView view = patch("vhostblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "vhost.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals("vhost.example.com", view.customDomain());
    }

    /**
     * I4b: claiming the site's own hostname as a weblog's custom domain must
     * be rejected here too, not just in the JSP editor -- both surfaces call
     * CustomDomainRules.isSiteHost so the rule cannot drift between them.
     */
    @Test
    void aPatchClaimingTheSitesOwnHostnameIsA400() throws Exception {
        givenRuntimeProperty("site.absoluteurl", "https://blog.example.com");

        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("plainblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "blog.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals(400, thrown.getStatus());
    }

    /** With no site.absoluteurl configured, there is no site host to collide with. */
    @Test
    void aPatchIsNotRejectedAsTheSiteHostWhenSiteAbsoluteUrlIsUnconfigured() throws Exception {
        AdminDtos.WeblogView view = patch("plainblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "blog.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals("blog.example.com", view.customDomain());
    }

    @Test
    void aDuplicateCustomDomainNamesTheCustomDomainField() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("plainblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null, null,
                        "vhost.example.com",
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals(409, thrown.getStatus());
        assertEquals("customDomain", thrown.getFieldErrors().get(0).field());
    }
}
