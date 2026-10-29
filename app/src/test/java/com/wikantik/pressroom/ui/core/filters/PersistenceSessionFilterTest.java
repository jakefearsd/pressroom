package com.wikantik.pressroom.ui.core.filters;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter that releases the per-request persistence session. It asks the
 * {@link WebloggerProvider} it is constructed with (DI wave, plan Task 6b)
 * rather than the static locator: before the tier is up there is nothing to
 * release, and afterwards the release goes to the provider's facade.
 */
class PersistenceSessionFilterTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final Weblogger weblogger = mock(Weblogger.class);
    private final PersistenceSessionFilter filter = new PersistenceSessionFilter(provider);

    @Test
    void afterTheChainRunsTheProvidersFacadeIsReleased() throws Exception {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "the request reaches the rest of the chain");
        verify(weblogger).release();
    }

    @Test
    void beforeBootstrapThereIsNothingToRelease() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        verify(provider, never()).getWeblogger();
    }
}
