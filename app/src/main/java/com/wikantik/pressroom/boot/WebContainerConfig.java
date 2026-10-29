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

import java.util.Collections;
import java.util.List;

import org.apache.tomcat.util.descriptor.web.JspConfigDescriptorImpl;
import org.apache.tomcat.util.descriptor.web.JspPropertyGroup;
import org.apache.tomcat.util.descriptor.web.JspPropertyGroupDescriptorImpl;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.error.ErrorPage;
import org.springframework.boot.web.error.ErrorPageRegistrar;
import org.springframework.boot.web.error.ErrorPageRegistry;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * Java-config transcription of web.xml's {@code <error-page>},
 * {@code <welcome-file-list>}, and {@code <jsp-config>} elements -- none of
 * which Boot's embedded container reads from web.xml either.
 */
@Configuration
public class WebContainerConfig {

    /**
     * Transcribed verbatim from web.xml's five {@code <error-page>}
     * elements, including the 400-reuses-404 comment.
     */
    @Bean
    public ErrorPageRegistrar rollerErrorPageRegistrar() {
        return (ErrorPageRegistry registry) -> registry.addErrorPages(
                new ErrorPage(Exception.class, "/roller-ui/errors/error.jsp"),
                new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/roller-ui/errors/error.jsp"),
                new ErrorPage(HttpStatus.FORBIDDEN, "/roller-ui/errors/403.jsp"),
                // 400 reuses the 404 template, same as web.xml.
                new ErrorPage(HttpStatus.BAD_REQUEST, "/roller-ui/errors/404.jsp"),
                new ErrorPage(HttpStatus.NOT_FOUND, "/roller-ui/errors/404.jsp"));
    }

    /**
     * Welcome files and {@code <jsp-config>}, applied via a Tomcat context
     * customizer since neither has a dedicated
     * {@code ConfigurableServletWebServerFactory} setter.
     *
     * <p>Welcome files: web.xml listed {@code home.jsp}, {@code index.jsp},
     * {@code index.html} in that order, but the webapp root
     * ({@code app/src/main/webapp/}) only actually contains {@code
     * index.jsp} -- no {@code home.jsp}, no {@code index.html} -- so only
     * the file that exists is registered here; the other two names would
     * have been silently-unreachable fallbacks anyway.
     *
     * <p>jsp-config: {@code Context} has no {@code addJspPropertyGroup}
     * method (only {@code get/setJspConfigDescriptor}), so the single
     * {@code *.jsp} property group is wrapped in a
     * {@code JspConfigDescriptorImpl} and installed via
     * {@code setJspConfigDescriptor} -- verified against
     * {@code tomcat-embed-core-11.0.22.jar} (the version this project's
     * {@code spring-boot-tomcat:4.1.0} pulls in) via {@code javap}, since
     * {@code Context.addJspPropertyGroup} does not exist on Tomcat 11's
     * {@code org.apache.catalina.Context}.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> rollerTomcatCustomizer() {
        return factory -> {
            // Whitespace. Jasper splits template text at every tag, so the
            // line break between <spring:message/> and a following <span> is
            // a whitespace-only text node of its own. web.xml's
            // trim-directive-whitespaces deleted every such node, which
            // glued words together ("Entries2" on Main Menu). Jasper's
            // trimSpaces=single keeps the output just as compact -- each
            // whitespace-only node becomes one space -- without joining
            // words. Only nodes that are entirely whitespace change, and
            // every JSP renders HTML, where a run of whitespace outside
            // <pre>/<textarea> is one space anyway. This customizer runs
            // after Boot's own (unordered beans sort last), so
            // server.servlet.jsp cannot replace it.
            factory.getSettings().getJsp().getInitParameters().put("trimSpaces", "single");
            factory.addContextCustomizers(WebContainerConfig::customizeContext);
        };
    }

    private static void customizeContext(org.apache.catalina.Context context) {
        context.addWelcomeFile("index.jsp");

        JspPropertyGroup group = new JspPropertyGroup();
        group.addUrlPattern("*.jsp");
        group.setPageEncoding("UTF-8");
        // Off: trimSpaces=single (above) replaces it.
        group.setTrimWhitespace("false");

        context.setJspConfigDescriptor(new JspConfigDescriptorImpl(
                List.of(new JspPropertyGroupDescriptorImpl(group)),
                Collections.emptyList()));
    }
}
