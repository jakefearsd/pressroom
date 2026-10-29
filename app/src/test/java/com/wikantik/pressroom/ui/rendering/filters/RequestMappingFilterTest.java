package com.wikantik.pressroom.ui.rendering.filters;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter that hands weblog urls to {@code WeblogRequestMapper}. Since the
 * DI wave (plan Task 6b) it constructs that one mapper directly with the
 * provider and facade it is given -- the reflective
 * {@code rendering.rollerRequestMappers}/{@code userRequestMappers} lists
 * named a single class and had no way to pass a dependency, so they are gone.
 * What is pinned here is the filter's own contract: a url the mapper declines
 * continues down the chain, one it routes does not.
 */
class RequestMappingFilterTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    private RequestMappingFilter filter() throws Exception {
        RequestMappingFilter filter = new RequestMappingFilter(provider, weblogger);
        filter.init(new MockFilterConfig());
        return filter;
    }

    /** An application path is never a weblog url: the mapper declines and the chain runs. */
    @Test
    void anApplicationPathContinuesDownTheChain() throws Exception {
        when(provider.isBootstrapped()).thenReturn(true);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller-ui/menu.rol");
        request.setContextPath("");

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    /**
     * Before the tier is up the mapper cannot ask whether a segment is a weblog
     * handle, so it declines rather than touching the facade -- the request
     * continues down the chain (to BootstrapFilter's install forward, in the
     * real chain) instead of failing here.
     */
    @Test
    void beforeBootstrapAWeblogShapedUrlIsNotRoutedAndNotAnError() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/someblog/entry/x");
        request.setContextPath("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "declined, so the chain continues");
        assertNull(response.getForwardedUrl());
    }
}
