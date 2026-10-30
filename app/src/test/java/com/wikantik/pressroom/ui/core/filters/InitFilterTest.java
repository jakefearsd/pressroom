/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.ui.core.filters;

import java.util.List;

import jakarta.servlet.http.HttpServlet;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * @author Kohei Nozaki
 */
public class InitFilterTest  {

    private static final String SERVER_NAME = "pressroom.example.com";

    /**
     * A filter over a tier that is not up: the custom-domain question is
     * skipped, which is what every non-vhost test here wants (DI wave, plan
     * Task 6b -- the filter takes its provider and facade by constructor).
     */
    private static InitFilter plainFilter() {
        WebloggerProvider notBootstrapped = mock(WebloggerProvider.class);
        return new InitFilter(notBootstrapped, null);
    }

    /** A filter over a bootstrapped tier whose facade is the given mock's. */
    private static InitFilter filterOver(MockWeblogger mocks) {
        WebloggerProvider bootstrapped = mock(WebloggerProvider.class);
        when(bootstrapped.isBootstrapped()).thenReturn(true);
        when(bootstrapped.getWeblogger()).thenReturn(mocks.weblogger());
        return new InitFilter(bootstrapped, mocks.weblogger());
    }

    @Test
    public void testGetAbsoluteUrlOnRootWithHttp() throws Exception {
        boolean secure = false;
        String contextPath = "";
        String requestURI = "/";
        String requestURL = "http://pressroom.example.com/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://pressroom.example.com", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlOnRootWithHttps() throws Exception {
        boolean secure = true;
        String contextPath = "";
        String requestURI = "/";
        String requestURL = "https://pressroom.example.com/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://pressroom.example.com", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstPermalinkWithHttps() throws Exception {
        boolean secure = true;
        String contextPath = "/pressroom";
        String requestURI = "/pressroom/handle/entry/title";
        String requestURL = "https://pressroom.example.com/pressroom/handle/entry/title";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://pressroom.example.com/pressroom", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlPreservesAnExplicitHttpsPort() throws Exception {
        boolean secure = true;
        String contextPath = "/pressroom";
        String requestURI = "/pressroom/";
        String requestURL = "https://pressroom.example.com:8443/pressroom/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://pressroom.example.com:8443/pressroom", absoluteUrl);
    }

    @Test
    public void testASecureRequestWhoseUrlStillSaysHttpIsUpgraded() throws Exception {
        // a connector that sets the secure flag without rewriting the scheme
        boolean secure = true;
        String contextPath = "/pressroom";
        String requestURI = "/pressroom/";
        String requestURL = "http://pressroom.example.com/pressroom/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://pressroom.example.com/pressroom", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstTop() throws Exception {
        boolean secure = false;
        String contextPath = "/pressroom";
        String requestURI = "/pressroom/";
        String requestURL = "http://pressroom.example.com/pressroom/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://pressroom.example.com/pressroom", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstPermalink() throws Exception {
        boolean secure = false;
        String contextPath = "/pressroom";
        String requestURI = "/pressroom/handle/entry/title";
        String requestURL = "http://pressroom.example.com/pressroom/handle/entry/title";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://pressroom.example.com/pressroom", absoluteUrl);
    }

    @Test
    public void testRemoveTrailingSlash() throws Exception {
        assertEquals("http://www.example.com", InitFilter.removeTrailingSlash("http://www.example.com/"));
        assertEquals("http://www.example.com", InitFilter.removeTrailingSlash("http://www.example.com"));
    }

    // ------------------------------------------------- forwarded-proto chain
    //
    // Production terminates TLS at Caddy and proxies to the app over plain
    // HTTP, signalling the original scheme in X-Forwarded-Proto.
    // server.forward-headers-strategy=framework (application.properties)
    // registers Spring's ForwardedHeaderFilter at highest precedence for
    // every URL pattern, so by the time InitFilter captures the absolute
    // context URL the request already reports https. These tests run the
    // exact same two-filter chain against a mock request.

    @Test
    public void forwardedProtoHttpsYieldsAnHttpsAbsoluteContextUrl() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            MockHttpServletRequest request = proxiedRequest();
            request.addHeader("X-Forwarded-Proto", "https");

            new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, plainFilter()));

            assertEquals("https://photos.example.com/pressroom",
                    WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "X-Forwarded-Proto: https must produce an https absolute context URL");
            assertEquals("/pressroom", WebloggerRuntimeConfig.getRelativeContextURL());
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    @Test
    public void plainHttpWithoutTheHeaderStaysHttp() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            new ForwardedHeaderFilter().doFilter(proxiedRequest(), new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, plainFilter()));

            assertEquals("http://photos.example.com/pressroom",
                    WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "plain-http dev behavior must be unchanged when no proxy header is present");
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    /** A plain-http request as the app sees it behind the reverse proxy. */
    private static MockHttpServletRequest proxiedRequest() {
        return proxiedRequest("/pressroom");
    }

    private static MockHttpServletRequest proxiedRequest(String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + "/");
        request.setServerName("photos.example.com");
        request.setServerPort(80);
        request.setContextPath(contextPath);
        return request;
    }

    // ------------------------------------------------- the root context
    //
    // The shipped default is the root context (application.properties), so
    // these are the ordinary case rather than an exotic one. Serving from the
    // root is what lets a weblog own a bare hostname -- https://example.com/
    // rather than https://example.com/pressroom/ -- which is both what a reverse
    // proxy in front of this app is usually for and what keeps a canonical
    // url free of a deployment artefact nobody can ever remove later without
    // a redirect map.
    //
    // CHARACTERISATION: every test in this section passed the moment it was
    // written. InitFilter was already correct at the root and already followed
    // X-Forwarded-Host; nothing here was fixed, and these exist to pin that
    // before the default context path moved onto them. Do not read them as
    // tests written after the code they cover -- read them as the reason the
    // move was safe. (WeblogRequestMapper, which builds a redirect rather than
    // reading a request, was NOT already correct; see its own test.)

    @Test
    public void theAbsoluteUrlAtTheRootContextCarriesNoPathAtAll() throws Exception {
        String absoluteUrl = InitFilter.getAbsoluteUrl(false, SERVER_NAME, "",
                "/handle/entry/title", "http://pressroom.example.com/handle/entry/title");
        assertEquals("http://pressroom.example.com", absoluteUrl);
    }

    @Test
    public void theAbsoluteUrlAtTheRootContextKeepsAnExplicitPort() throws Exception {
        String absoluteUrl = InitFilter.getAbsoluteUrl(false, SERVER_NAME, "",
                "/handle/", "http://pressroom.example.com:8083/handle/");
        assertEquals("http://pressroom.example.com:8083", absoluteUrl);
    }

    /**
     * The relative context url is the prefix every in-page link is built from,
     * and at the root it is the EMPTY string -- not "/". A "/" here would make
     * every url Roller emits carry a doubled slash ("//handle/entry/x"), which
     * a browser reads as a protocol-relative url pointing at a host called
     * "handle".
     */
    @Test
    public void theRelativeContextUrlAtTheRootIsEmptyNotASlash() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            new ForwardedHeaderFilter().doFilter(proxiedRequest(""), new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, plainFilter()));

            assertEquals("", WebloggerRuntimeConfig.getRelativeContextURL());
            assertEquals("http://photos.example.com",
                    WebloggerRuntimeConfig.getAbsoluteContextURL());
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    /**
     * The proxy owns the public hostname, not just the scheme. Caddy sends
     * X-Forwarded-Host along with X-Forwarded-Proto; without honouring it the
     * absolute context url would be built from the container-internal name the
     * app was actually reached by, and every canonical/og:url/sitemap loc would
     * name a host that does not resolve on the internet.
     */
    @Test
    public void theForwardedHostDecidesTheAbsoluteUrlAtTheRootContext() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.setServerName("app");
            request.setServerPort(8080);
            request.setContextPath("");
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", "maiiavorobiova.com");

            new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, plainFilter()));

            assertEquals("https://maiiavorobiova.com",
                    WebloggerRuntimeConfig.getAbsoluteContextURL());
            assertEquals("", WebloggerRuntimeConfig.getRelativeContextURL());
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    /**
     * A host UrlValidator rejects (a LAN name such as {@code docker1.lan}: no
     * public suffix) may not latch the absolute url, but the relative one is
     * only the context path and the same on every host. It used to stay null
     * until a public request arrived, so every theme's {@code $url.site}
     * printed literally and custom CSS was linked as {@code null/<handle>/...}
     * -- and the page cache kept serving that broken render afterwards.
     */
    @Test
    public void aHostTheValidatorRejectsStillSetsTheRelativeContextUrl() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            WebloggerRuntimeConfig.setRelativeContextURL(null);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.setServerName("docker1.lan");
            request.setServerPort(8084);
            request.setContextPath("");

            new MockFilterChain(new HttpServlet() { }, plainFilter())
                    .doFilter(request, new MockHttpServletResponse());

            assertEquals("", WebloggerRuntimeConfig.getRelativeContextURL());
            assertEquals(priorAbsolute, WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "a rejected host must still not latch the absolute url");
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    // -------------------------------------------------- I2 (optional half):
    // refuse to latch a host VirtualHostRegistry resolves
    //
    // Without this, a custom-domain request that happens to be the FIRST
    // request after boot latches THAT weblog's own hostname as the site's
    // absolute context url -- and with site.absoluteurl unset (I2's main
    // fix warns about exactly this), every domain-less weblog then inherits
    // it in its own canonical url/og:url/feed id/sitemap/robots.txt/
    // password-reset links.

    /**
     * A request that arrives on a weblog's own custom domain must not latch
     * that hostname as the site's absolute context url.
     */
    @Test
    public void aRequestOnACustomDomainIsNotLatchedAsTheAbsoluteContextUrl() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        WebloggerRuntimeConfig.setRelativeContextURL(null);

        MockWeblogger mocks = MockWeblogger.attached();
        try {
            Weblog vhostblog = new Weblog();
            vhostblog.setHandle("vhostblog");
            vhostblog.setCustomDomain("vhost.example.com");
            when(mocks.getWeblogManager().getWeblogs(null, null, null, null, 0, -1))
                    .thenReturn(List.of(vhostblog));
            // The filter asks the facade it is constructed with for the
            // registry -- hand it one over the mock manager.
            when(mocks.weblogger().getVirtualHostRegistry())
                    .thenReturn(new VirtualHostRegistry(mocks.getWeblogManager()));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/entry/my-post");
            request.setServerName("vhost.example.com");
            request.addHeader("Host", "vhost.example.com");
            request.setContextPath("");

            filterOver(mocks).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertNull(WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "a request on a weblog's own custom domain must not latch the site's "
                            + "absolute context url from it");
        } finally {
            mocks.detach();
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    /**
     * The converse: an UNCLAIMED host (no weblog owns it) must still latch
     * normally -- this is the ordinary site-host request, and every other
     * test in this class already exercises it implicitly, but this one
     * pins it explicitly at the same "VirtualHostRegistry is populated"
     * setup the test above uses, so the two are read together.
     */
    @Test
    public void aRequestOnAnUnclaimedHostStillLatchesNormally() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        WebloggerRuntimeConfig.setRelativeContextURL(null);

        MockWeblogger mocks = MockWeblogger.attached();
        try {
            Weblog vhostblog = new Weblog();
            vhostblog.setHandle("vhostblog");
            vhostblog.setCustomDomain("vhost.example.com");
            when(mocks.getWeblogManager().getWeblogs(null, null, null, null, 0, -1))
                    .thenReturn(List.of(vhostblog));
            // The filter asks the facade it is constructed with for the
            // registry -- hand it one over the mock manager.
            when(mocks.weblogger().getVirtualHostRegistry())
                    .thenReturn(new VirtualHostRegistry(mocks.getWeblogManager()));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.setServerName("pressroom.example.com");
            request.addHeader("Host", "pressroom.example.com");
            request.setContextPath("");

            filterOver(mocks).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertEquals("http://pressroom.example.com", WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "an unclaimed host must still latch, unaffected by the custom-domain refusal");
        } finally {
            mocks.detach();
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

}
