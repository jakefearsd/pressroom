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
package com.wikantik.pressroom.ui.rendering.velocity;

import org.apache.velocity.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WebappResourceLoader}.
 *
 * <p>{@code getResourceReader} used to catch-and-rethrow a {@code
 * NullPointerException} as a diagnostic technique when {@code
 * servletContext} was never set (see CLAUDE.md's DCN_NULLPOINTER_EXCEPTION
 * note). It now checks explicitly and reports the same {@link
 * ResourceNotFoundException} every other failure in this method produces.
 */
class WebappResourceLoaderTest {

    @Test
    void aMissingServletContextIsReportedAsResourceNotFoundRatherThanAnNpe() {
        // No init() call: servletContext stays at its field default (null).
        WebappResourceLoader loader = new WebappResourceLoader();

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("weblog.vm", "UTF-8"));

        assertTrue(thrown.getMessage().contains("ServletContext not initialized"),
                "got: " + thrown.getMessage());
    }
}
