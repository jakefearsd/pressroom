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

package com.wikantik.pressroom.ui.rendering.filters;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.ui.rendering.WeblogRequestMapper;

/**
 * Hands every incoming request to the {@link WeblogRequestMapper}, which
 * re-routes weblog urls ({@code /<handle>/...}, or a custom domain's root) to
 * the rendering servlets and declines everything else.
 *
 * <p>The mapper is constructed here, directly, with the {@link WebloggerProvider}
 * and facade this filter is given (a bean in {@code ServletRegistrationConfig};
 * DI wave, plan Task 6b). It used to be instantiated reflectively from the
 * {@code rendering.pressroomRequestMappers} / {@code rendering.userRequestMappers}
 * property lists -- which named exactly one class, had no user entries, and had
 * no way to hand a mapper a dependency. An extension point with one
 * implementation, no consumer and no injection is not an extension point, so
 * the lists are gone (spec Decision 3).
 *
 * @web.filter name="RequestMappingFilter"
 */
public class RequestMappingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestMappingFilter.class);

    private final WeblogRequestMapper mapper;

    public RequestMappingFilter(WebloggerProvider provider, Weblogger weblogger) {
        this.mapper = new WeblogRequestMapper(provider, weblogger);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("Request mapping filter initialized, mapper {}", mapper.getClass().getName());
    }


    /**
     * Inspect incoming urls and see if they should be routed.
     */
    @Override
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "This application is deployed exclusively over HTTP -- every filter is "
                    + "registered via a FilterRegistrationBean against Spring Boot's embedded Tomcat "
                    + "HTTP connector (ServletRegistrationConfig), and no other protocol connector is "
                    + "ever configured. The servlet container therefore guarantees doFilter is invoked "
                    + "only with HttpServletRequest/HttpServletResponse; the cast is unconfirmed only "
                    + "under the general, protocol-independent Filter contract, not in this deployment.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        log.debug("entering");

        if (mapper.handleRequest(request, response)) {
            // the mapper has handled the request, so we are done
            log.debug("request handled by {}", mapper.getClass().getName());
            log.debug("exiting");
            return;
        }

        log.debug("request not mapped");

        // the mapper declined the request, so let it continue as usual
        chain.doFilter(request, response);

        log.debug("exiting");
    }


    @Override
    public void destroy() {}

}
