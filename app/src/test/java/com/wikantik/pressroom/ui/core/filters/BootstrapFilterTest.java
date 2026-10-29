package com.wikantik.pressroom.ui.core.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate that stands in front of an un-installed Roller.
 *
 * <p>While {@code installation.type} is "auto" and the business tier has not
 * been bootstrapped, every request is forwarded to the installer -- except the
 * handful the installer itself needs to render. That exception list is the
 * interesting part: too narrow and the install page loads without its
 * stylesheet, too wide and requests reach an application that has no database
 * behind it yet.
 *
 * <p>The install type is a static config read (stubbed -- Stage 2 of the DI
 * program); the bootstrap flag is asked of the {@link WebloggerProvider} the
 * filter is constructed with (DI wave, plan Task 6b). What is being tested is
 * the filter's decision, not where it reads the inputs from.
 */
class BootstrapFilterTest {

    private BootstrapFilter filter;
    private ServletContext context;
    private RequestDispatcher dispatcher;
    private FilterChain chain;
    private WebloggerProvider provider;

    @BeforeEach
    void createFilter() throws Exception {
        context = mock(ServletContext.class);
        dispatcher = mock(RequestDispatcher.class);
        chain = mock(FilterChain.class);
        when(context.getRequestDispatcher(any())).thenReturn(dispatcher);

        FilterConfig config = mock(FilterConfig.class);
        when(config.getServletContext()).thenReturn(context);

        provider = mock(WebloggerProvider.class);
        filter = new BootstrapFilter(provider);
        filter.init(config);
    }

    /** Runs the filter with the install type and the provider's bootstrap flag forced. */
    private void runWith(String installationType, boolean bootstrapped, String uri)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        when(provider.isBootstrapped()).thenReturn(bootstrapped);

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("installation.type"))
                    .thenReturn(installationType);

            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
    }

    private void assertForwardedToInstaller() throws Exception {
        verify(dispatcher).forward(any(), any());
        verify(chain, never()).doFilter(any(), any());
    }

    private void assertPassedThrough() throws Exception {
        verify(chain).doFilter(any(), any());
        verify(dispatcher, never()).forward(any(), any());
    }

    // --- when the gate is closed ------------------------------------------

    @Test
    void anUninstalledPressroomSendsEveryOrdinaryRequestToTheInstaller() throws Exception {
        runWith("auto", false, "/pressroom-ui/menu.rol");
        assertForwardedToInstaller();
    }

    @Test
    void aRequestWithNoUriIsTreatedAsOrdinaryAndForwarded() throws Exception {
        // getRequestURI can be null on a synthetic dispatch; isInstallUrl's null
        // check must not decide such a request is part of the installer
        runWith("auto", false, null);
        assertForwardedToInstaller();
    }

    // --- what the installer is allowed to load ----------------------------

    @Test
    void theInstallerSOwnPagesAreNotForwardedBackToItself() throws Exception {
        runWith("auto", false, "/pressroom-ui/install/bootstrap.rol");
        assertPassedThrough();
    }

    @Test
    void theCreateStepIsReachable() throws Exception {
        runWith("auto", false, "/pressroom-ui/install/create.rol");
        assertPassedThrough();
    }

    @Test
    void theUpgradeStepIsReachable() throws Exception {
        runWith("auto", false, "/pressroom-ui/install/upgrade.rol");
        assertPassedThrough();
    }

    @Test
    void theInstallPageKeepsItsScript() throws Exception {
        runWith("auto", false, "/pressroom-ui/scripts/install.js");
        assertPassedThrough();
    }

    @Test
    void theInstallPageKeepsItsStylesheet() throws Exception {
        runWith("auto", false, "/pressroom-ui/styles/roller.css");
        assertPassedThrough();
    }

    // --- when the gate is open --------------------------------------------

    @Test
    void anInstalledPressroomForwardsNothing() throws Exception {
        runWith("auto", true, "/pressroom-ui/menu.rol");
        assertPassedThrough();
    }

    @Test
    void aManualInstallationNeverRedirectsEvenBeforeBootstrap() throws Exception {
        // installation.type is only "auto" for the self-installing deployment;
        // anything else means an operator is driving the install themselves and
        // must not be bounced to the wizard
        runWith("manual", false, "/pressroom-ui/menu.rol");
        assertPassedThrough();
    }

    @Test
    void anUnsetInstallationTypeIsNotAuto() throws Exception {
        runWith(null, false, "/pressroom-ui/menu.rol");
        assertPassedThrough();
    }
}
