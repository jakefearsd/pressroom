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
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerConfig;


/**
 * Redirects clients to install page when app is not bootstrapped and install
 * type is "auto", otherwise does nothing.
 *
 * <p>Whether the tier is bootstrapped is asked of the {@link WebloggerProvider}
 * this filter is constructed with (a bean in {@code ServletRegistrationConfig};
 * DI wave, plan Task 6b) rather than the static locator.
 */
public class BootstrapFilter implements Filter {
    private ServletContext context = null;
    private static final Logger log = LoggerFactory.getLogger(BootstrapFilter.class);

    private final WebloggerProvider provider;

    public BootstrapFilter(WebloggerProvider provider) {
        this.provider = provider;
    }


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
        
        if ("auto".equals(WebloggerConfig.getProperty("installation.type"))
                && !provider.isBootstrapped()
                && !isInstallUrl(request.getRequestURI())) {
                    
            log.debug("Forwarding to install page");
            
            // we doing an install, so forward to installer
            RequestDispatcher rd = context.getRequestDispatcher(
                "/pressroom-ui/install/install.rol");
            rd.forward(req, res);
            
        } else {
            chain.doFilter(request, response);
        }
        
        log.debug("Exiting {}", request.getRequestURI());
    }
    
    
    private boolean isInstallUrl(String uri) {
        return uri != null && (
                   uri.endsWith("bootstrap.rol")
                || uri.endsWith("create.rol")
                || uri.endsWith("upgrade.rol")
                || uri.endsWith(".js")
                || uri.endsWith(".css"));
    }
    
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        context = filterConfig.getServletContext();
    }

    @Override
    public void destroy() {}    
}
