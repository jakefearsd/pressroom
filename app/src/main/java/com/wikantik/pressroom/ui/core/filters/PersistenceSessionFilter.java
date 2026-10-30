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

package com.wikantik.pressroom.ui.core.filters;

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
import com.wikantik.pressroom.business.WebloggerProvider;


/**
 * Sole responsibility is to ensure that each request's Pressroom
 * persistence session is released at end of the request.
 *
 * <p>Takes the {@link WebloggerProvider} (a bean in
 * {@code ServletRegistrationConfig}; DI wave, plan Task 6b): before the tier
 * is up there is nothing to release, and afterwards the release goes to the
 * provider's facade rather than the static locator.
 *
 * @web.filter name="PersistenceSessionFilter"
 */
public class PersistenceSessionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(PersistenceSessionFilter.class);

    private final WebloggerProvider provider;

    public PersistenceSessionFilter(WebloggerProvider provider) {
        this.provider = provider;
    }

    /**
     * Release Pressroom persistence session at end of request processing.
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
        
        log.debug("Entered {}", request.getRequestURI());
        
        try {
            chain.doFilter(request, response);
        } finally {
            if (provider.isBootstrapped()) {
                log.debug("Releasing Pressroom Session");
                provider.getWeblogger().release();
            }
            
        }
        
        log.debug("Exiting {}", request.getRequestURI());
    }
    
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}
    
    @Override
    public void destroy() {}
    
}

