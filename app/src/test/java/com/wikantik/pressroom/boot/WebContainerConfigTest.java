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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;

import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.JspConfigDescriptorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.error.ErrorPage;
import org.springframework.boot.web.error.ErrorPageRegistrar;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link WebContainerConfig}'s two beans are lambdas closing over web.xml's
 * {@code <error-page>}/{@code <welcome-file-list>}/{@code <jsp-config>}
 * elements; calling the {@code @Bean} method only builds the lambda, so
 * these tests additionally invoke it (as Boot's own infrastructure would) to
 * exercise the body: {@link ErrorPageRegistrar#registerErrorPages} directly,
 * and the {@code TomcatContextCustomizer} it hands to a real (not mocked --
 * {@code addContextCustomizers} is a concrete method with no test seam)
 * {@link TomcatServletWebServerFactory} against a mocked {@link Context}.
 */
class WebContainerConfigTest {

    private final WebContainerConfig config = new WebContainerConfig();

    @Test
    void errorPagesMatchWebXmlVerbatimIncludingThe400ReusesTheFileNotFoundPage() {
        ErrorPageRegistrar registrar = config.rollerErrorPageRegistrar();

        List<ErrorPage> registered = new ArrayList<>();
        registrar.registerErrorPages(pages -> registered.addAll(Arrays.asList(pages)));

        assertEquals(5, registered.size());
        assertTrue(registered.stream().anyMatch(p ->
                Exception.class.equals(p.getException()) && "/roller-ui/errors/error.jsp".equals(p.getPath())));
        assertTrue(registered.stream().anyMatch(p ->
                HttpStatus.INTERNAL_SERVER_ERROR.equals(p.getStatus())
                        && "/roller-ui/errors/error.jsp".equals(p.getPath())));
        assertTrue(registered.stream().anyMatch(p ->
                HttpStatus.FORBIDDEN.equals(p.getStatus()) && "/roller-ui/errors/403.jsp".equals(p.getPath())));
        assertTrue(registered.stream().anyMatch(p ->
                HttpStatus.BAD_REQUEST.equals(p.getStatus()) && "/roller-ui/errors/404.jsp".equals(p.getPath())),
                "400 must reuse the 404 template, same as web.xml");
        assertTrue(registered.stream().anyMatch(p ->
                HttpStatus.NOT_FOUND.equals(p.getStatus()) && "/roller-ui/errors/404.jsp".equals(p.getPath())));
    }

    @Test
    void tomcatCustomizerAddsTheOnlyRealWelcomeFileAndTheJspPropertyGroup() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        // The @Bean method returns a WebServerFactoryCustomizer<TomcatServletWebServerFactory>;
        // .customize(factory) is what Boot calls during context refresh, and it is
        // what actually registers the inner TomcatContextCustomizer this test needs
        // to invoke against a mocked Context.
        config.rollerTomcatCustomizer().customize(factory);

        assertEquals(1, factory.getContextCustomizers().size());
        TomcatContextCustomizer customizer = factory.getContextCustomizers().iterator().next();

        Context context = mock(Context.class);
        customizer.customize(context);

        verify(context).addWelcomeFile("index.jsp");

        org.mockito.ArgumentCaptor<JspConfigDescriptorImpl> captor =
                org.mockito.ArgumentCaptor.forClass(JspConfigDescriptorImpl.class);
        verify(context).setJspConfigDescriptor(captor.capture());

        JspConfigDescriptorImpl descriptor = captor.getValue();
        assertEquals(1, descriptor.getJspPropertyGroups().size());
        assertTrue(descriptor.getTaglibs().isEmpty());

        JspPropertyGroupDescriptor group = descriptor.getJspPropertyGroups().iterator().next();
        assertEquals(List.of("*.jsp"), List.copyOf(group.getUrlPatterns()));
        assertEquals("UTF-8", group.getPageEncoding());
        assertEquals("false", group.getTrimDirectiveWhitespaces(),
                "trim-directive-whitespaces deletes the whitespace between an action and the "
                        + "next tag outright, gluing words together (Main Menu's \"Entries2\")");
    }

    /**
     * Jasper splits template text at every tag, so the whitespace between
     * {@code <spring:message/>} and a following {@code <span>} is a text node
     * of its own; trimming deleted it, and Main Menu read "Entries2" and the
     * entry editor "...first.Open weblog settings". {@code trimSpaces=single}
     * keeps the output compact (each whitespace-only node becomes one space)
     * without joining words.
     */
    @Test
    void whitespaceOnlyTemplateTextCollapsesToOneSpaceRatherThanVanishing() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        config.rollerTomcatCustomizer().customize(factory);

        assertEquals("single", factory.getSettings().getJsp().getInitParameters().get("trimSpaces"));
    }
}
