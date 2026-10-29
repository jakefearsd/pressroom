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
import java.util.Set;

import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.controllers.PressroomViewResolver;
import com.wikantik.pressroom.ui.restapi.auth.ApiScopeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebMvcConfig} is a Java-config transcription of {@code
 * spring-mvc.xml}; every method here is either a plain bean-construction
 * method or a {@code WebMvcConfigurer} callback with no branching, so a
 * fresh instance and (where the Spring-provided registry type has no useful
 * public introspection) a mock of the registry it configures is enough to
 * pin every value transcribed from the XML.
 */
class WebMvcConfigTest {

    private final PressroomHandlerInterceptor interceptor = new PressroomHandlerInterceptor(
            mock(com.wikantik.pressroom.business.WebloggerProvider.class),
            mock(com.wikantik.pressroom.business.Weblogger.class));
    private final WebMvcConfig config = new WebMvcConfig(interceptor);

    @Test
    void addsThePressroomHandlerInterceptor() {
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(org.mockito.ArgumentMatchers.isA(ApiScopeInterceptor.class)))
                .thenReturn(registration);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(interceptor);
    }

    /**
     * The scope interceptor narrows the token's ceiling on top of whatever
     * PressroomHandlerInterceptor already decided, so it must run for /v1/**
     * -- and only there, since it has nothing to enforce on the JSP UI.
     *
     * <p><b>"/v1/**", not "/api/**".</b> ServletRegistrationConfig's
     * {@code API_URL_PATTERNS} ("/api/*") is a servlet-spec prefix mapping,
     * so the container strips "/api" before the request reaches Spring
     * MVC's lookup path -- {@code addPathPatterns} matches against that same
     * stripped path. "/api/**" shipped here once and matched nothing, ever;
     * this test only pins the string the code passes, which is exactly why
     * it did not catch that. {@link ApiScopeInterceptorDispatchTest} is the
     * real gate -- it dispatches an actual request through this exact
     * registration and would have failed.
     */
    @Test
    void addsTheApiScopeInterceptorScopedToApiPaths() {
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(org.mockito.ArgumentMatchers.isA(ApiScopeInterceptor.class)))
                .thenReturn(registration);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(org.mockito.ArgumentMatchers.isA(ApiScopeInterceptor.class));
        verify(registration).addPathPatterns("/v1/**");
    }

    @Test
    void registersTheWebjarsResourceHandlerAtItsClasspathLocation() {
        ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);
        ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
        when(registry.addResourceHandler("/webjars/**")).thenReturn(registration);
        when(registration.addResourceLocations("classpath:/META-INF/resources/webjars/"))
                .thenReturn(registration);

        config.addResourceHandlers(registry);

        verify(registry).addResourceHandler("/webjars/**");
        verify(registration).addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    @Test
    void messageSourceReadsApplicationResourcesAsUtf8() {
        ResourceBundleMessageSource messageSource = config.messageSource();

        assertEquals(Set.of("ApplicationResources"), messageSource.getBasenameSet());
    }

    @Test
    void pressroomViewResolverIsCheckedBeforeThePlainJspFallback() {
        PressroomViewResolver resolver = config.pressroomViewResolver();

        assertEquals(1, resolver.getOrder());
    }

    @Test
    void internalResourceViewResolverIsTheFallbackForPlainJsps() throws ReflectiveOperationException {
        InternalResourceViewResolver resolver = config.internalResourceViewResolver();

        assertEquals(2, resolver.getOrder());
        assertEquals("/WEB-INF/jsps/", stringFieldOf(resolver, "prefix"));
        assertEquals(".jsp", stringFieldOf(resolver, "suffix"));
    }

    /**
     * {@code UrlBasedViewResolver.getPrefix()}/{@code getSuffix()} are
     * {@code protected}, unreachable from this package -- the same
     * private-field-reflection pattern {@code RenderingTestSupport} and
     * {@code ServletRegistrationConfigTest} already use elsewhere in this
     * suite for a Spring/Boot type with no public getter.
     */
    private static String stringFieldOf(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(target);
    }
}
