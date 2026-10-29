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

package com.wikantik.pressroom.ui.rendering.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds Mockito-backed {@link HttpServletRequest} stubs for the *Request
 * parsing tests.
 *
 * <p>The parsing classes read only a handful of things off the request --
 * servlet path, path info, query parameters and the request URL used in error
 * messages -- so a stub is enough and no servlet container is needed. Two
 * details are easy to get wrong by hand and are therefore centralised here:
 *
 * <ul>
 *   <li>{@code getParameterMap()} must never be null. {@code WeblogPageRequest}
 *       copies it into its customParams map and would NPE on a bare mock.</li>
 *   <li>{@code getPathInfo()} is what the servlet container hands over
 *       <em>already percent-decoded</em>. Tests that want to exercise decoding
 *       therefore pass the decoded form, which is why several of them show a
 *       literal {@code %2B} surviving into a second round of decoding.</li>
 * </ul>
 */
final class MockRequest {

    private MockRequest() {
    }

    /**
     * @param servletPath value for {@code getServletPath()}, may be null to
     *                    simulate a request that never reached a servlet
     * @param pathInfo    value for {@code getPathInfo()}, already decoded
     * @param params      alternating parameter name/value pairs
     */
    static HttpServletRequest with(String servletPath, String pathInfo, String... params) {
        if (params.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "params must be name/value pairs but got " + params.length
                            + " values; check the call site");
        }

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
        when(request.getRequestURL()).thenReturn(
                new StringBuffer("http://localhost/roller" + servletPath + pathInfo));

        Map<String, String[]> parameterMap = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            String name = params[i];
            String value = params[i + 1];
            parameterMap.put(name, new String[]{value});
            when(request.getParameter(name)).thenReturn(value);
        }
        when(request.getParameterMap()).thenReturn(parameterMap);

        return request;
    }
}
