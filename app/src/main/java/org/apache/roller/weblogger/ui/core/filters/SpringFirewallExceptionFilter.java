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

package org.apache.roller.weblogger.ui.core.filters;

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
import org.springframework.security.web.firewall.RequestRejectedException;

/**
 * <p>The spring default firewall ({@link org.springframework.security.web.firewall.StrictHttpFirewall}) 
 * is throwing exceptions if it decides to block a request. For example double slashes (//) in the request are
 * interpreted as non-normalized URL and rejected by throwing RequestRejectedExceptions.
 * Those exceptions are caught by the server and cause 500 errors which isn't very nice behavior.</p>
 * 
 * <p>The most straightforward way to handle this seems to be a servlet filter.</p>
 * 
 * @see org.springframework.security.web.firewall.StrictHttpFirewall
 */
public class SpringFirewallExceptionFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(SpringFirewallExceptionFilter.class);

    @Override
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "This application is deployed exclusively over HTTP -- every filter is "
                    + "registered via a FilterRegistrationBean against Spring Boot's embedded Tomcat "
                    + "HTTP connector (ServletRegistrationConfig), and no other protocol connector is "
                    + "ever configured. The servlet container therefore guarantees doFilter is invoked "
                    + "only with HttpServletRequest/HttpServletResponse; the cast is unconfirmed only "
                    + "under the general, protocol-independent Filter contract, not in this deployment.")
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (RequestRejectedException ex) {
            
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            
            // url seems to be dangerous -> 404. DEBUG, not WARN: scanners trip
            // the firewall constantly, and a WARN per probe buried real warnings.
            log.debug("request rejected: {} cause: {}", req.getRequestURL(), ex.getMessage());
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            
        }
    }

    @Override
    public void destroy() {}

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}
    

}
