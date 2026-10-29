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

import java.io.UnsupportedEncodingException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CharEncodingFilter}.
 *
 * <p>UTF-8 is a Java-specified required encoding, so {@code
 * setCharacterEncoding("UTF-8")} cannot fail on a real JVM -- the failure
 * path exists only in case a servlet container implementation somehow
 * disagrees, which is why it is worth pinning: the cause must survive into
 * the rethrown {@link ServletException} rather than being discarded.
 */
class CharEncodingFilterTest {

    private final CharEncodingFilter filter = new CharEncodingFilter();

    @Test
    void aRequestAlreadyOnUtf8IsPassedThroughWithoutReSettingIt() throws Exception {
        ServletRequest request = mock(ServletRequest.class);
        when(request.getCharacterEncoding()).thenReturn("UTF-8");
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(request, never()).setCharacterEncoding(any(String.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void aRequestOnADifferentEncodingIsSwitchedToUtf8() throws Exception {
        ServletRequest request = mock(ServletRequest.class);
        when(request.getCharacterEncoding()).thenReturn("ISO-8859-1");
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(request).setCharacterEncoding("UTF-8");
        verify(chain).doFilter(request, response);
    }

    @Test
    void anUnsupportedEncodingFailureIsRethrownWithItsCause() throws Exception {
        ServletRequest request = mock(ServletRequest.class);
        when(request.getCharacterEncoding()).thenReturn("ISO-8859-1");
        UnsupportedEncodingException cause = new UnsupportedEncodingException("UTF-8");
        doThrow(cause).when(request).setCharacterEncoding("UTF-8");
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        ServletException thrown = assertThrows(ServletException.class,
                () -> filter.doFilter(request, response, chain));

        assertEquals(cause, thrown.getCause(),
                "the UnsupportedEncodingException must survive as the cause");
        verify(chain, never()).doFilter(any(), any());
    }
}
