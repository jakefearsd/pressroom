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
package org.apache.roller.weblogger.boot;

import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletRegistration;

import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.ui.controllers.ajax.ThemeDataServlet;
import org.apache.roller.weblogger.ui.controllers.ajax.UserDataServlet;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.weblogger.ui.core.filters.BootstrapFilter;
import org.apache.roller.weblogger.ui.core.filters.CharEncodingFilter;
import org.apache.roller.weblogger.ui.core.filters.ControlPlaneHostFilter;
import org.apache.roller.weblogger.ui.core.filters.InitFilter;
import org.apache.roller.weblogger.ui.core.filters.PersistenceSessionFilter;
import org.apache.roller.weblogger.ui.core.filters.SpringFirewallExceptionFilter;
import org.apache.roller.weblogger.ui.rendering.filters.RequestMappingFilter;
import org.apache.roller.weblogger.ui.rendering.servlets.FeedServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.MediaResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PageServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.ResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.SearchServlet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Java-config transcription of {@code web.xml}'s {@code <servlet>}/
 * {@code <filter>}/{@code <servlet-mapping>}/{@code <filter-mapping>}
 * elements, none of which Boot's embedded container reads from
 * {@code web.xml} itself. One {@link ServletRegistrationBean} per servlet
 * row and one {@link FilterRegistrationBean} per filter row, transcribed
 * exactly from the normative tables in the Stage 1B Task 3 brief (in turn
 * transcribed from {@code web.xml}'s own load-bearing ordering comments —
 * see the filter registrations below for why the order matters).
 */
@Configuration
public class ServletRegistrationConfig {

    // ------------------------------------------------------------------
    // Rendering / AJAX servlets (org.apache.roller.weblogger.ui.rendering.servlets
    // unless noted). loadOnStartup values transcribed verbatim from web.xml;
    // the three ajax servlets had no <load-on-startup> element, so
    // setLoadOnStartup is simply never called for them, leaving
    // ServletRegistrationBean's own default of -1 (lazy init on first
    // request) in effect -- the closest equivalent to "element absent" and
    // left alone here rather than guessing a value web.xml never specified.
    // ------------------------------------------------------------------
    //
    // Each servlet is a bean of its own, constructed here with the Weblogger
    // facade and then handed to its ServletRegistrationBean. The @Lazy on the
    // Weblogger parameter is load-bearing: these beans are built at context
    // refresh, before WebloggerStartup.prepare() has run, and the lazy proxy
    // defers building the business tier to first use (the same reason
    // BaseController's field is @Lazy). ContextRefreshDoesNotBootstrapTest
    // pins that. Boot's own Servlet-bean adapter does not double-register
    // them: a servlet already referenced by a ServletRegistrationBean is
    // skipped by ServletContextInitializerBeans.
    // ------------------------------------------------------------------

    @Bean
    public PageServlet pageServlet(@Lazy Weblogger weblogger) {
        return new PageServlet(weblogger);
    }

    @Bean
    public FeedServlet feedServlet(@Lazy Weblogger weblogger) {
        return new FeedServlet(weblogger);
    }

    @Bean
    public ResourceServlet resourceServlet(@Lazy Weblogger weblogger) {
        return new ResourceServlet(weblogger);
    }

    @Bean
    public MediaResourceServlet mediaResourceServlet(@Lazy Weblogger weblogger) {
        return new MediaResourceServlet(weblogger);
    }

    @Bean
    public SearchServlet searchServlet(@Lazy Weblogger weblogger) {
        return new SearchServlet(weblogger);
    }

    @Bean
    public PreviewServlet previewServlet(@Lazy Weblogger weblogger) {
        return new PreviewServlet(weblogger);
    }

    @Bean
    public PreviewResourceServlet previewResourceServlet(@Lazy Weblogger weblogger) {
        return new PreviewResourceServlet(weblogger);
    }

    @Bean
    public UserDataServlet userDataServlet(@Lazy Weblogger weblogger) {
        return new UserDataServlet(weblogger);
    }

    @Bean
    public ThemeDataServlet themeDataServlet(@Lazy Weblogger weblogger) {
        return new ThemeDataServlet(weblogger);
    }

    @Bean
    public ServletRegistrationBean<PageServlet> pageServletRegistration(PageServlet pageServlet) {
        ServletRegistrationBean<PageServlet> registration =
                new ServletRegistrationBean<>(pageServlet, "/roller-ui/rendering/page/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<FeedServlet> feedServletRegistration(FeedServlet feedServlet) {
        ServletRegistrationBean<FeedServlet> registration =
                new ServletRegistrationBean<>(feedServlet, "/roller-ui/rendering/feed/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<ResourceServlet> resourceServletRegistration(ResourceServlet resourceServlet) {
        ServletRegistrationBean<ResourceServlet> registration =
                new ServletRegistrationBean<>(resourceServlet, "/roller-ui/rendering/resources/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<MediaResourceServlet> mediaResourceServletRegistration(MediaResourceServlet mediaResourceServlet) {
        ServletRegistrationBean<MediaResourceServlet> registration =
                new ServletRegistrationBean<>(mediaResourceServlet, "/roller-ui/rendering/media-resources/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<SearchServlet> searchServletRegistration(SearchServlet searchServlet) {
        ServletRegistrationBean<SearchServlet> registration =
                new ServletRegistrationBean<>(searchServlet, "/roller-ui/rendering/search/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<PreviewServlet> previewServletRegistration(PreviewServlet previewServlet) {
        ServletRegistrationBean<PreviewServlet> registration =
                new ServletRegistrationBean<>(previewServlet, "/roller-ui/authoring/preview/*");
        registration.setLoadOnStartup(9);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<PreviewResourceServlet> previewResourceServletRegistration(PreviewResourceServlet previewResourceServlet) {
        ServletRegistrationBean<PreviewResourceServlet> registration =
                new ServletRegistrationBean<>(previewResourceServlet, "/roller-ui/authoring/previewresource/*");
        registration.setLoadOnStartup(9);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<UserDataServlet> userDataServletRegistration(UserDataServlet userDataServlet) {
        return new ServletRegistrationBean<>(userDataServlet, "/roller-ui/authoring/userdata/*");
    }

    @Bean
    public ServletRegistrationBean<ThemeDataServlet> themeDataServletRegistration(ThemeDataServlet themeDataServlet) {
        return new ServletRegistrationBean<>(themeDataServlet, "/roller-ui/authoring/themedata/*");
    }

    // ------------------------------------------------------------------
    // DispatcherServlet -- mapped to *.rol, exactly as web.xml's springMvc
    // <servlet-mapping> did. Naming this bean method (and therefore the
    // bean) "dispatcherServletRegistration" is load-bearing: Boot's own
    // DispatcherServletAutoConfiguration.DispatcherServletRegistrationCondition
    // backs off its default "/"-mapped DispatcherServletRegistrationBean
    // specifically when a bean named "dispatcherServletRegistration"
    // already exists (verified via javap against
    // spring-boot-webmvc-4.1.0.jar), so a differently-named bean here would
    // leave BOTH registrations active.
    // ------------------------------------------------------------------

    /**
     * Extra url-patterns routing the crawler-facing SEO endpoints
     * ({@code SeoController}) to the dispatcher alongside {@code *.rol}.
     *
     * <p>The servlet spec allows only exact, prefix ({@code /foo/*}) and
     * extension ({@code *.ext}) patterns; a middle wildcard like
     * {@code /sitemap-*.xml} would be matched as an exact literal, so the
     * per-weblog sitemaps at {@code /sitemap-<handle>.xml} ride on a
     * {@code *.xml} extension mapping. Extension mappings lose to the
     * longer-prefix rendering mappings above, and weblog-shaped URLs are
     * forwarded by RequestMappingFilter before servlet resolution, so the
     * observable effect is confined to root-level .xml paths: the container
     * default servlet used to serve or 404 them (the only real static .xml
     * files were the {@code /themes/<name>/theme.xml} metadata files, which
     * have no business being crawlable); now the dispatcher 404s anything
     * SeoController does not handle.
     *
     * <p>These cannot go through {@code addUrlMappings()}:
     * DispatcherServletRegistrationBean overrides it (and setUrlMappings) to
     * throw UnsupportedOperationException (verified via javap against
     * spring-boot-webmvc-4.1.0.jar), so the bean below adds them in the
     * protected {@code configure()} hook, directly on the container's
     * ServletRegistration.
     */
    static final String[] SEO_URL_PATTERNS = {"/sitemap.xml", "*.xml", "/robots.txt"};

    /**
     * Routes the public newsletter subscribe endpoint
     * ({@code NewsletterController}) to the dispatcher. Unlike the SEO
     * patterns above this is a legal servlet-spec <em>prefix</em> mapping,
     * which has one Spring MVC consequence worth writing down: for a
     * prefix-matched request the servlet path prefix is treated like an
     * extension of the context path and stripped from the lookup path
     * ({@code ServletRequestPathUtils.parse}, "the returned RequestPath will
     * have both the contextPath and any servletPath prefix omitted"). The
     * controller's {@code @PostMapping} is therefore written relative to
     * {@code /newsletter} -- {@code /subscribe}, not
     * {@code /newsletter/subscribe}. The {@code newsletter} path root is
     * reserved in {@code rollerProtectedUrls} so no weblog handle can shadow
     * it.
     */
    static final String[] NEWSLETTER_URL_PATTERNS = {"/newsletter/*"};

    /**
     * Routes the automation API ({@code ui.restapi.v1}) to the dispatcher.
     *
     * <p>A legal servlet-spec <em>prefix</em> mapping, with the same Spring
     * MVC consequence {@code NEWSLETTER_URL_PATTERNS} documents above: the
     * servlet path prefix is stripped from the lookup path, so every API
     * controller is mapped relative to {@code /api} -- {@code /v1/...}, not
     * {@code /api/v1/...}. The {@code api} path root is reserved in
     * {@code rendering.weblogMapper.rollerProtectedUrls} so no weblog handle
     * can shadow it.
     */
    // List.of(...), not a String[]: this is read cross-package by
    // ApiMountingTest, so it cannot simply drop to package-private the way
    // MS_PKGPROTECT would otherwise suggest (SEO_URL_PATTERNS/
    // NEWSLETTER_URL_PATTERNS above have no such reader and stay
    // package-private) -- an immutable List, unlike an array, has no way for
    // a caller to mutate the shared constant, so public visibility is safe
    // either way.
    public static final List<String> API_URL_PATTERNS = List.of("/api/*");

    @Bean
    public DispatcherServletRegistrationBean dispatcherServletRegistration(
            DispatcherServlet dispatcherServlet,
            ObjectProvider<MultipartConfigElement> multipartConfig) {
        DispatcherServletRegistrationBean registration =
                new DispatcherServletRegistrationBean(dispatcherServlet, "*.rol") {
                    @Override
                    protected void configure(ServletRegistration.Dynamic servletRegistration) {
                        super.configure(servletRegistration);
                        servletRegistration.addMapping(SEO_URL_PATTERNS);
                        servletRegistration.addMapping(NEWSLETTER_URL_PATTERNS);
                        servletRegistration.addMapping(API_URL_PATTERNS.toArray(new String[0]));
                    }
                };
        registration.setName("springMvc");
        registration.setLoadOnStartup(1);
        multipartConfig.ifAvailable(registration::setMultipartConfig);
        return registration;
    }

    // ------------------------------------------------------------------
    // Filters. Order and dispatcher types are the contract -- transcribed
    // verbatim from web.xml's <filter-mapping> order (which IS significant,
    // per web.xml's own "order IS important here" comment) into explicit
    // setOrder() values 10-80. Two ordering comments from web.xml carry
    // forward here verbatim because they explain *why* the order matters,
    // not just what it is:
    //   - CharEncodingFilter must run first: anything ahead of it must not
    //     cause request parsing, since that would lock in the wrong
    //     encoding.
    //   - SpringFirewallExceptionFilter must sit immediately before Spring
    //     Security (order 30, security at 40): it converts a
    //     RequestRejectedException thrown by Spring Security's firewall
    //     into a plain 404 instead of letting the exception propagate.
    // Order 40 (springSecurityFilterChain) is Boot-managed, not registered
    // here -- see application.properties' spring.security.filter.order /
    // spring.security.filter.dispatcher-types.
    // ------------------------------------------------------------------

    @Bean
    public FilterRegistrationBean<CharEncodingFilter> charEncodingFilterRegistration() {
        FilterRegistrationBean<CharEncodingFilter> registration = new FilterRegistrationBean<>(new CharEncodingFilter());
        registration.setOrder(10);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SpringFirewallExceptionFilter> springFirewallExceptionFilterRegistration() {
        FilterRegistrationBean<SpringFirewallExceptionFilter> registration =
                new FilterRegistrationBean<>(new SpringFirewallExceptionFilter());
        registration.setOrder(30);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        return registration;
    }

    /**
     * Order 35: between SpringFirewallExceptionFilter (30) and the Spring
     * Security chain (spring.security.filter.order=40). Running after security
     * would let an unauthenticated admin request be 302'd to a login page on
     * the custom domain before this filter ever sees it. Running this early is
     * only possible because VirtualHostRegistry reads an in-memory map and
     * needs no EntityManager -- PersistenceSessionFilter is order 60.
     */
    // The five filters that touch the business tier are beans of their own,
    // constructed here and handed to their FilterRegistrationBean, exactly as
    // the servlets above are (DI wave, plan Task 6b). The WebloggerProvider
    // is what a filter that may run BEFORE bootstrap asks first
    // (ControlPlaneHostFilter at 35 and InitFilter at 70 see requests the
    // install wizard has not yet satisfied); the @Lazy on the Weblogger
    // parameter is load-bearing for the same reason the servlets' is. Boot
    // does not double-register a Filter bean that a FilterRegistrationBean
    // already references (ServletContextInitializerBeans skips it).

    @Bean
    public ControlPlaneHostFilter controlPlaneHostFilter(WebloggerProvider provider,
            @Lazy Weblogger weblogger) {
        return new ControlPlaneHostFilter(provider, weblogger);
    }

    @Bean
    public FilterRegistrationBean<ControlPlaneHostFilter> controlPlaneHostFilterRegistration(
            ControlPlaneHostFilter controlPlaneHostFilter) {
        FilterRegistrationBean<ControlPlaneHostFilter> registration =
                new FilterRegistrationBean<>(controlPlaneHostFilter);
        registration.setOrder(35);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    // Order 40 (springSecurityFilterChain) is registered automatically by
    // Boot's SecurityFilterAutoConfiguration -- see application.properties.

    @Bean
    public BootstrapFilter bootstrapFilter(WebloggerProvider provider) {
        return new BootstrapFilter(provider);
    }

    @Bean
    public FilterRegistrationBean<BootstrapFilter> bootstrapFilterRegistration(BootstrapFilter bootstrapFilter) {
        FilterRegistrationBean<BootstrapFilter> registration = new FilterRegistrationBean<>(bootstrapFilter);
        registration.setOrder(50);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public PersistenceSessionFilter persistenceSessionFilter(WebloggerProvider provider) {
        return new PersistenceSessionFilter(provider);
    }

    @Bean
    public FilterRegistrationBean<PersistenceSessionFilter> persistenceSessionFilterRegistration(
            PersistenceSessionFilter persistenceSessionFilter) {
        FilterRegistrationBean<PersistenceSessionFilter> registration =
                new FilterRegistrationBean<>(persistenceSessionFilter);
        registration.setOrder(60);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public InitFilter initFilter(WebloggerProvider provider, @Lazy Weblogger weblogger) {
        return new InitFilter(provider, weblogger);
    }

    @Bean
    public FilterRegistrationBean<InitFilter> initFilterRegistration(InitFilter initFilter) {
        FilterRegistrationBean<InitFilter> registration = new FilterRegistrationBean<>(initFilter);
        registration.setOrder(70);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public RequestMappingFilter requestMappingFilter(WebloggerProvider provider, @Lazy Weblogger weblogger) {
        return new RequestMappingFilter(provider, weblogger);
    }

    @Bean
    public FilterRegistrationBean<RequestMappingFilter> requestMappingFilterRegistration(
            RequestMappingFilter requestMappingFilter) {
        FilterRegistrationBean<RequestMappingFilter> registration =
                new FilterRegistrationBean<>(requestMappingFilter);
        registration.setOrder(80);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    /**
     * Moved from {@code RollerApplication}'s temporary Task-2 placeholder
     * (formerly a {@code <listener>} in web.xml) now that this is the
     * permanent home for web.xml-derived registrations.
     */
    @Bean
    public ServletListenerRegistrationBean<RollerSession> rollerSessionListener() {
        return new ServletListenerRegistrationBean<>(new RollerSession());
    }
}
