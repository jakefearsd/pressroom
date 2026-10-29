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

import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.controllers.PressroomViewResolver;
import com.wikantik.pressroom.ui.restapi.auth.ApiScopeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/**
 * Java-config transcription of {@code spring-mvc.xml}. Deliberately does
 * <b>not</b> carry an {@code @EnableWebMvc} annotation: that annotation
 * switches Spring MVC into fully-manual mode and disables Boot's own
 * {@code WebMvcAutoConfiguration}, which is exactly the auto-configuration
 * this class means to layer on top of (multipart resolver, message
 * converters, etc.), not replace. Controller component-scanning is likewise
 * not repeated here -- {@code PressroomApplication}'s
 * {@code scanBasePackages = "com.wikantik.pressroom"} already covers
 * {@code ui.controllers} (spring-mvc.xml's
 * {@code <context:component-scan base-package="com.wikantik.pressroom.ui.controllers"/>}).
 *
 * <p>{@code spring-mvc.xml}'s explicit {@code multipartResolver} bean
 * (a {@code StandardServletMultipartResolver}) is also dropped: Boot's
 * {@code MultipartAutoConfiguration} registers the same implementation
 * automatically whenever a {@code MultipartConfigElement} is present, which
 * {@link ServletRegistrationConfig#dispatcherServletRegistration} supplies.
 *
 * <p>{@code spring-mvc.xml}'s {@code <bean class="...WebloggerBeanConfig"/>}
 * business-tier import is not transcribed here either -- it is picked up by
 * the same component scan (it is itself annotated, per Task 1/2's setup),
 * not manually imported.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PressroomHandlerInterceptor rollerHandlerInterceptor;

    /**
     * @param rollerHandlerInterceptor the {@code @Component} interceptor,
     *        constructor-injected with its {@code WebloggerProvider} and a lazy
     *        {@code Weblogger}; registering the bean rather than a {@code new}
     *        instance is what lets it hold those dependencies at all.
     */
    public WebMvcConfig(PressroomHandlerInterceptor rollerHandlerInterceptor) {
        this.rollerHandlerInterceptor = rollerHandlerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rollerHandlerInterceptor);
        // "/v1/**", not "/api/**": ServletRegistrationConfig.API_URL_PATTERNS
        // ("/api/*") is a servlet-spec PREFIX mapping, so the container
        // strips "/api" before the request ever reaches Spring MVC's lookup
        // path -- the same reason every API controller is @RequestMapping'd
        // at "/v1/...", not "/api/v1/...". addPathPatterns matches against
        // that same stripped path, so "/api/**" here never matches anything
        // and the whole ceiling was inert. See ApiScopeInterceptorDispatchTest,
        // which dispatches a real request through this exact registration
        // (not a mock) and is what would have caught this.
        registry.addInterceptor(new ApiScopeInterceptor()).addPathPatterns("/v1/**");
    }

    /**
     * Transcribed from {@code spring-mvc.xml}, but currently unreachable in
     * practice: {@link ServletRegistrationConfig#dispatcherServletRegistration}
     * maps {@code DispatcherServlet} to {@code *.rol} only, so a request for
     * e.g. {@code /webjars/foo.js} never reaches this handler -- Tomcat's own
     * default servlet answers it directly from the webjars jars'
     * {@code META-INF/resources} (registered on the classpath, which the
     * default servlet also serves from per
     * {@code register-default-servlet=true}, added in Task 3). This handler
     * is left in place for when {@code DispatcherServlet} is re-scoped to
     * {@code /} in a future stage, at which point it becomes load-bearing
     * again rather than redundant.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * i18n message source for {@code ApplicationResources*.properties},
     * unchanged from {@code spring-mvc.xml}'s {@code messageSource} bean.
     * The bean name "messageSource" is significant -- Spring's own
     * {@code MessageSource}-aware infrastructure (e.g. JSTL's
     * {@code <fmt:message>} bridging) looks it up by that exact name.
     */
    @Bean
    public ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("ApplicationResources");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }

    /**
     * Lightweight Tiles replacement, order 1 (checked before the plain-JSP
     * fallback below). {@code initMethod = "init"} transcribes
     * {@code spring-mvc.xml}'s {@code init-method="init"}.
     */
    @Bean(initMethod = "init")
    public PressroomViewResolver rollerViewResolver() {
        PressroomViewResolver resolver = new PressroomViewResolver();
        resolver.setOrder(1);
        return resolver;
    }

    /**
     * Fallback for plain JSP views (redirect:, forward:, or direct paths),
     * order 2 -- consulted only when {@link #rollerViewResolver} declines to
     * resolve a view name.
     */
    @Bean
    public InternalResourceViewResolver internalResourceViewResolver() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("/WEB-INF/jsps/");
        resolver.setSuffix(".jsp");
        resolver.setOrder(2);
        return resolver;
    }
}
