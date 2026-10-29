/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.ui.controllers.ajax.ThemeDataServlet;
import com.wikantik.pressroom.ui.controllers.ajax.UserDataServlet;
import com.wikantik.pressroom.ui.core.PressroomSession;
import com.wikantik.pressroom.ui.core.filters.BootstrapFilter;
import com.wikantik.pressroom.ui.core.filters.CharEncodingFilter;
import com.wikantik.pressroom.ui.core.filters.ControlPlaneHostFilter;
import com.wikantik.pressroom.ui.core.filters.InitFilter;
import com.wikantik.pressroom.ui.core.filters.PersistenceSessionFilter;
import com.wikantik.pressroom.ui.core.filters.SpringFirewallExceptionFilter;
import com.wikantik.pressroom.ui.rendering.filters.RequestMappingFilter;
import com.wikantik.pressroom.ui.rendering.servlets.FeedServlet;
import com.wikantik.pressroom.ui.rendering.servlets.MediaResourceServlet;
import com.wikantik.pressroom.ui.rendering.servlets.PageServlet;
import com.wikantik.pressroom.ui.rendering.servlets.PreviewResourceServlet;
import com.wikantik.pressroom.ui.rendering.servlets.PreviewServlet;
import com.wikantik.pressroom.ui.rendering.servlets.ResourceServlet;
import com.wikantik.pressroom.ui.rendering.servlets.SearchServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.web.servlet.DispatcherServlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every {@code @Bean} method here is a plain, side-effect-free registration
 * builder -- no Spring context is needed to exercise them, just direct calls
 * against a fresh {@link ServletRegistrationConfig}. Pins the transcription
 * from {@code web.xml} the class javadoc describes: URL pattern, {@code
 * loadOnStartup} (via reflection -- {@link ServletRegistrationBean} has no
 * public getter, only the setter web.xml's ordering depends on), filter
 * order, and filter dispatcher types, for every servlet and filter this
 * config class registers.
 */
class ServletRegistrationConfigTest {

    private final ServletRegistrationConfig config = new ServletRegistrationConfig();

    /** The servlet and filter beans are built with a facade; the registrations never touch it. */
    private final Weblogger weblogger = mock(Weblogger.class);

    /** Likewise the provider the bootstrap-aware filters take (DI wave, plan Task 6b). */
    private final WebloggerProvider provider = mock(WebloggerProvider.class);

    /**
     * {@link ServletRegistrationBean} exposes no getter for {@code
     * loadOnStartup} (only {@code setLoadOnStartup}), so this reads the
     * private field directly -- the same private-field-reflection pattern
     * {@code RenderingTestSupport} already uses elsewhere in this suite.
     */
    private static int loadOnStartupOf(ServletRegistrationBean<?> bean) throws ReflectiveOperationException {
        Field field = ServletRegistrationBean.class.getDeclaredField("loadOnStartup");
        field.setAccessible(true);
        return field.getInt(bean);
    }

    private static void assertServlet(ServletRegistrationBean<?> bean, Class<?> servletType,
            String urlPattern, int expectedLoadOnStartup) throws ReflectiveOperationException {
        assertInstanceOf(servletType, bean.getServlet());
        assertEquals(Set.of(urlPattern), bean.getUrlMappings());
        assertEquals(expectedLoadOnStartup, loadOnStartupOf(bean),
                "loadOnStartup for " + servletType.getSimpleName());
    }

    // ---------------------------------------------------- rendering servlets
    // loadOnStartup values transcribed verbatim from web.xml (see the class
    // javadoc); values below match ServletRegistrationConfig's own comments.

    @Test
    void renderingServletsCarryTheirWebXmlPatternAndLoadOrder() throws Exception {
        assertServlet(config.pageServletRegistration(config.pageServlet(weblogger)), PageServlet.class,
                "/pressroom-ui/rendering/page/*", 5);
        assertServlet(config.feedServletRegistration(config.feedServlet(weblogger)), FeedServlet.class,
                "/pressroom-ui/rendering/feed/*", 5);
        assertServlet(config.resourceServletRegistration(config.resourceServlet(weblogger)), ResourceServlet.class,
                "/pressroom-ui/rendering/resources/*", 5);
        assertServlet(config.mediaResourceServletRegistration(config.mediaResourceServlet(weblogger)), MediaResourceServlet.class,
                "/pressroom-ui/rendering/media-resources/*", 5);
        assertServlet(config.searchServletRegistration(config.searchServlet(weblogger)), SearchServlet.class,
                "/pressroom-ui/rendering/search/*", 5);
        assertServlet(config.previewServletRegistration(config.previewServlet(weblogger)), PreviewServlet.class,
                "/pressroom-ui/authoring/preview/*", 9);
        assertServlet(config.previewResourceServletRegistration(config.previewResourceServlet(weblogger)), PreviewResourceServlet.class,
                "/pressroom-ui/authoring/previewresource/*", 9);
    }

    @Test
    void ajaxServletsCarryTheirWebXmlPatternAndTheDefaultLoadOrder() throws Exception {
        // No <load-on-startup> element in web.xml for these two -- left at
        // ServletRegistrationBean's own constructor default, -1 (verified via
        // javap against spring-boot-4.1.0.jar: the no-arg and (T, String...)
        // constructors both set loadOnStartup = -1).
        assertServlet(config.userDataServletRegistration(config.userDataServlet(weblogger)), UserDataServlet.class,
                "/pressroom-ui/authoring/userdata/*", -1);
        assertServlet(config.themeDataServletRegistration(config.themeDataServlet(weblogger)), ThemeDataServlet.class,
                "/pressroom-ui/authoring/themedata/*", -1);
    }

    /**
     * Enumerates every url-pattern this class registers a servlet under.
     * Used to pin the absence of the comment endpoints below rather than
     * asserting a specific bean method no longer exists -- the point is that
     * nothing reachable answers to a "comment"-shaped path, not the mechanics
     * of how that got removed.
     */
    private List<String> registeredPatterns() throws ReflectiveOperationException {
        List<String> patterns = new ArrayList<>();
        patterns.addAll(config.pageServletRegistration(config.pageServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.feedServletRegistration(config.feedServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.resourceServletRegistration(config.resourceServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.mediaResourceServletRegistration(config.mediaResourceServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.searchServletRegistration(config.searchServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.previewServletRegistration(config.previewServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.previewResourceServletRegistration(config.previewResourceServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.userDataServletRegistration(config.userDataServlet(weblogger)).getUrlMappings());
        patterns.addAll(config.themeDataServletRegistration(config.themeDataServlet(weblogger)).getUrlMappings());
        return patterns;
    }

    /**
     * The comment servlet, the comment authenticator servlet and the comment
     * ajax servlet's registration are all gone with the comment subsystem --
     * no url-pattern this class registers should mention "comment" in either
     * case.
     */
    @Test
    void noCommentServletsAreRegistered() throws Exception {
        assertTrue(registeredPatterns().stream()
                        .noneMatch(p -> p.contains("comment") || p.contains("Comment")),
                "comment servlets must be unregistered: " + registeredPatterns());
    }

    // -------------------------------------------------------- listener bean

    @Test
    void rollerSessionListenerRegistersTheListenerType() {
        ServletListenerRegistrationBean<PressroomSession> bean = config.rollerSessionListener();
        assertInstanceOf(PressroomSession.class, bean.getListener());
    }

    // --------------------------------------------------- DispatcherServlet

    @Test
    void dispatcherServletIsMappedToRolSuffixWithLoadOnStartupOne() throws Exception {
        DispatcherServlet dispatcherServlet = new DispatcherServlet();

        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(dispatcherServlet, noopMultipartProvider());

        assertSame(dispatcherServlet, bean.getServlet());
        assertEquals("*.rol", bean.getPath());
        assertEquals(1, loadOnStartupOf(bean));
    }

    /**
     * The SEO endpoints (SeoController) ride on extra container mappings
     * that DispatcherServletRegistrationBean's throwing
     * {@code addUrlMappings()} forces through the {@code configure()} hook,
     * so they are only observable at registration time -- hence the mocked
     * ServletContext.
     */
    @Test
    void dispatcherServletAlsoClaimsTheSeoUrlPatterns() throws Exception {
        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(new DispatcherServlet(), noopMultipartProvider());

        ServletContext servletContext = mock(ServletContext.class);
        ServletRegistration.Dynamic registration = mock(ServletRegistration.Dynamic.class);
        when(servletContext.addServlet(anyString(), any(DispatcherServlet.class)))
                .thenReturn(registration);

        bean.onStartup(servletContext);

        verify(registration).addMapping("*.rol");
        verify(registration).addMapping(ServletRegistrationConfig.SEO_URL_PATTERNS);
    }

    @Test
    void dispatcherServletAppliesAnAvailableMultipartConfig() throws Exception {
        MultipartConfigElement multipartConfig = new MultipartConfigElement("");

        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(new DispatcherServlet(), availableMultipartProvider(multipartConfig));

        assertSame(multipartConfig, bean.getMultipartConfig());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultipartConfigElement> noopMultipartProvider() {
        ObjectProvider<MultipartConfigElement> provider = mock(ObjectProvider.class);
        // ifAvailable's default mocked behaviour (no-op) is exactly "nothing
        // available" -- exercising the call is enough to cover the line, no
        // stubbing needed.
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultipartConfigElement> availableMultipartProvider(
            MultipartConfigElement element) {
        ObjectProvider<MultipartConfigElement> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<MultipartConfigElement> consumer = invocation.getArgument(0);
            consumer.accept(element);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    // --------------------------------------------------------------- filters

    @Test
    void charEncodingFilterRunsFirstOnEveryRequestAndForward() {
        FilterRegistrationBean<CharEncodingFilter> bean = config.charEncodingFilterRegistration();
        assertInstanceOf(CharEncodingFilter.class, bean.getFilter());
        assertEquals(10, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD),
                bean.determineDispatcherTypes());
    }

    @Test
    void springFirewallExceptionFilterRunsBeforeSecurityAtOrderThirty() {
        FilterRegistrationBean<SpringFirewallExceptionFilter> bean =
                config.springFirewallExceptionFilterRegistration();
        assertInstanceOf(SpringFirewallExceptionFilter.class, bean.getFilter());
        assertEquals(30, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD),
                bean.determineDispatcherTypes());
    }

    /**
     * Order 35 -- between SpringFirewallExceptionFilter (30) and the Spring
     * Security chain (40, Boot-managed) -- is load-bearing: see the class
     * javadoc on {@link ControlPlaneHostFilter} for why it must run ahead of
     * security rather than after it.
     */
    @Test
    void controlPlaneHostFilterRunsBetweenTheFirewallFilterAndSecurityAtOrderThirtyFive() {
        FilterRegistrationBean<ControlPlaneHostFilter> bean = config.controlPlaneHostFilterRegistration(
                config.controlPlaneHostFilter(provider, weblogger));
        assertInstanceOf(ControlPlaneHostFilter.class, bean.getFilter());
        assertEquals(35, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void bootstrapFilterRunsAfterSecurityAtOrderFifty() {
        FilterRegistrationBean<BootstrapFilter> bean = config.bootstrapFilterRegistration(
                config.bootstrapFilter(provider));
        assertInstanceOf(BootstrapFilter.class, bean.getFilter());
        assertEquals(50, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void persistenceSessionFilterRunsAtOrderSixty() {
        FilterRegistrationBean<PersistenceSessionFilter> bean = config.persistenceSessionFilterRegistration(
                config.persistenceSessionFilter(provider));
        assertInstanceOf(PersistenceSessionFilter.class, bean.getFilter());
        assertEquals(60, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void initFilterRunsAtOrderSeventy() {
        FilterRegistrationBean<InitFilter> bean = config.initFilterRegistration(
                config.initFilter(provider, weblogger));
        assertInstanceOf(InitFilter.class, bean.getFilter());
        assertEquals(70, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void requestMappingFilterRunsLastAtOrderEighty() {
        FilterRegistrationBean<RequestMappingFilter> bean = config.requestMappingFilterRegistration(
                config.requestMappingFilter(provider, weblogger));
        assertInstanceOf(RequestMappingFilter.class, bean.getFilter());
        assertEquals(80, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }
}
